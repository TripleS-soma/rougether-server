import json
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

from worker import Worker, Settings, parse_plan, code_plan, planning_actions


class FakeHttp:
    def __init__(self, jobs=None, plan=None):
        self.jobs = list(jobs or [])
        self.plan = plan or {"action": "WAIT", "payload": {}, "memory": ""}
        self.calls = []
        self.fail_execute = 0
        self.fail_recall = False
        self.candidates, self.accepted = [], []
        self.pending = []

    def post(self, url, body, headers, timeout=60):
        self.calls.append((url, body, headers))
        if url.endswith("/claim"):
            return {"job": self.jobs.pop(0)} if self.jobs else {}
        if url.endswith("/snapshot"):
            return {"aiUserId": 1, "actions": ["WAIT", "FEED_COMMENT"], "source": {"type": "FEED_COMMENT", "comment": {"author_id":42,"content": "독서 기록은?"}}}
        if url.endswith("/inventory"):
            return {"records": [], "nextCursor": None}
        if url.endswith("/memory/check"):
            return []
        if url.endswith("/recall"):
            if self.fail_recall:
                raise HTTPError(url, 503, "test", {}, None)
            return self.candidates
        if url.endswith("/memory-context"):
            return self.accepted
        if url.endswith("/decide"):
            return self.plan
        if url.endswith("/execute") and self.fail_execute:
            self.fail_execute -= 1
            raise OSError("response lost")
        if url.endswith("/memory/pending"):
            return self.pending
        return {}


def job(number=1):
    return {"id": number, "userId": 1, "fencingToken": number}


class WorkerTest(unittest.TestCase):
    def settings(self, **kwargs):
        return Settings("https://control.test", "c"*32, "https://ai.test", "a"*32, owner="test-worker", **kwargs)

    def test_idle_burst_exits_and_checks_pending_memory_without_model(self):
        http = FakeHttp()
        self.assertEqual(Worker(self.settings(), http).run(), 0)
        self.assertEqual(len(http.calls), 2)

    def test_burst_handles_at_most_ten_jobs_then_exits(self):
        http = FakeHttp([job(i) for i in range(12)])
        self.assertEqual(Worker(self.settings(), http).run(), 10)
        self.assertEqual(len(http.jobs), 2)

    def test_deadline_stops_before_claiming_another_job(self):
        http = FakeHttp([job()])
        with patch("worker.time.monotonic", side_effect=[0, 11, 11]):
            self.assertEqual(Worker(self.settings(max_seconds=10), http).run(), 0)
        self.assertEqual(http.calls, [])

    def test_replay_uses_same_body_without_regenerating_decision(self):
        http = FakeHttp([job()])
        http.fail_execute = 1
        with patch("worker.time.sleep"):
            Worker(self.settings(), http).tick()
        executed = [body for url, body, _ in http.calls if url.endswith("/execute")]
        self.assertEqual(executed[0], executed[1])
        self.assertEqual(sum(url.endswith("/decide") for url,_,_ in http.calls), 1)
        self.assertEqual(executed[0]["owner"], "test-worker")

    def test_only_authorized_graph_facts_are_sent_to_ai_service(self):
        http = FakeHttp([job()])
        http.candidates = [{"text": "철회된 기억", "sources": [], "episodes": ["old"]}]
        Worker(self.settings(), http).tick()
        decision = next(body for url, body,_ in http.calls if url.endswith("/decide"))
        self.assertEqual(decision["facts"], [])
        recall = next(body for url, body,_ in http.calls if url.endswith("/recall"))
        self.assertEqual(recall["counterpartUserId"],42)
        self.assertTrue(any(url.endswith("/forget") for url,_,_ in http.calls))
        self.assertFalse(any("anthropic.com" in url for url,_,_ in http.calls))

    def test_transient_memory_failure_defers_business_job(self):
        http = FakeHttp([job()])
        http.fail_recall = True
        Worker(self.settings(), http).tick()
        self.assertTrue(http.calls[-1][0].endswith("/defer"))
        self.assertFalse(any(url.endswith("/execute") for url,_,_ in http.calls))

    def test_invalid_action_cannot_reach_execution(self):
        http = FakeHttp([job()], {"action": "SQL", "payload": {}, "memory": ""})
        Worker(self.settings(), http).tick()
        self.assertTrue(http.calls[-1][0].endswith("/fail"))
        self.assertFalse(any(url.endswith("/execute") for url,_,_ in http.calls))

    def test_actor_override_rejected(self):
        response = {"stop_reason": "end_turn", "content": [{"type": "text", "text": json.dumps({
            "action": "WAIT", "payload": {"userId": 99}, "memory": ""})}]}
        with self.assertRaises(ValueError):
            parse_plan(response, ["WAIT"])

    def test_explicit_service_credentials_and_no_model_key_in_worker(self):
        http = FakeHttp([job()])
        Worker(self.settings(), http).tick()
        for url,_,headers in http.calls:
            expected = "a"*32 if url.startswith("https://ai.test") else "c"*32
            self.assertEqual(headers, {"Authorization": "Bearer "+expected})
        self.assertNotIn("c"*32, repr(self.settings()))

    def test_insecure_remote_origins_are_rejected_by_default(self):
        with self.assertRaises(ValueError):
            Settings("http://remote.test", "c"*32, "https://ai.test", "a"*32)

    def test_memory_sync_ack_does_not_reexecute_business_action(self):
        http = FakeHttp()
        http.pending = [{"jobId": 7, "aiUserId": 1, "body": "synthetic"}]
        Worker(self.settings(), http).run()
        self.assertTrue(any(url.endswith("/memory/7/ack") and body["success"] for url,body,_ in http.calls))
        self.assertFalse(any(url.endswith("/execute") for url,_,_ in http.calls))

    def test_bootstrap_gates_keep_human_action_choices(self):
        state = {"source": {"type": "SCHEDULE"}, "actions": ["WAIT", "ONBOARD_GOALS", "ONBOARD_CHARACTER", "ROUTINE_CREATE"],
            "onboarding": {"goals": [], "selectedCharacterId": None}, "goals": {"items": [{"id": 1}]}}
        self.assertEqual(planning_actions(state), ["ONBOARD_GOALS"])
        state["source"]["type"] = "CHAT"
        self.assertEqual(planning_actions(state), state["actions"])

    def test_sleep_uses_no_model_and_human_events_bypass_sleep(self):
        state = {"source": {"type": "SCHEDULE"}, "localTime": "03:00", "personaKey": "steady-walk", "actions": ["WAIT"]}
        self.assertEqual(code_plan(state)["action"], "WAIT")
        state["source"]["type"] = "CHAT"
        self.assertIsNone(code_plan(state))


if __name__ == "__main__":
    unittest.main()
