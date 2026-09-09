"""로컬 실험의 발생기·컨테이너·JVM 관측. 관측 실패도 원시 자료에 남김."""
import json
import subprocess
import threading
import urllib.request
import urllib.error
import urllib.parse
from datetime import datetime, timezone


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def api_json(base_url, path, token=None):
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    request = urllib.request.Request(base_url + path, headers=headers)
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=3) as response:
        return json.load(response)


def endpoint_counts(base_url, token):
    counts = {}
    for endpoint, method, uri in (("me", "GET", "/api/v1/me"),
                                  ("today", "GET", "/api/v1/today"),
                                  ("complete", "POST", "/api/v1/todos/{id}/complete")):
        query = urllib.parse.urlencode([("tag", "uri:" + uri), ("tag", "method:" + method)])
        try:
            metric = api_json(base_url, "/actuator/metrics/http.server.requests?" + query, token)
            counts[endpoint] = next(item["value"] for item in metric["measurements"]
                                    if item["statistic"] == "COUNT")
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
            counts[endpoint] = 0
    return counts


class Sampler:
    def __init__(self, compose, base_url, token, directory):
        self.compose = compose
        self.base_url = base_url
        self.token = token
        self.directory = directory
        self.stop_event = threading.Event()
        self.thread = None

    def start(self, process):
        self.thread = threading.Thread(target=self._sample, args=(process,), daemon=True)
        self.thread.start()

    def stop(self):
        self.stop_event.set()
        if self.thread:
            self.thread.join(timeout=20)

    def _sample(self, process):
        metrics = ["jvm.memory.used", "jvm.gc.pause", "hikaricp.connections.active",
                   "hikaricp.connections.pending", "hikaricp.connections.acquire"]
        with (self.directory / "telemetry.jsonl").open("w") as stream:
            while not self.stop_event.is_set():
                sample = {"at": utc_now(), "generator_pid": process.pid}
                try:
                    raw = subprocess.check_output(
                        ["ps", "-p", str(process.pid), "-o", "%cpu=,rss=,etime="],
                        text=True, timeout=3).strip().split()
                    if len(raw) >= 3:
                        sample["generator"] = {"cpu_percent": float(raw[0]),
                                               "rss_kib": int(raw[1]), "elapsed": raw[2]}
                except (subprocess.SubprocessError, ValueError) as error:
                    sample["generator_error"] = type(error).__name__
                try:
                    raw = self.compose("stats", "--no-stream", "--format", "{{json .}}", timeout=8)
                    sample["containers"] = [json.loads(line) for line in raw.splitlines() if line]
                except (subprocess.SubprocessError, ValueError) as error:
                    sample["containers_error"] = type(error).__name__
                sample["jvm"] = {}
                for metric in metrics:
                    try:
                        sample["jvm"][metric] = api_json(
                            self.base_url, "/actuator/metrics/" + metric, self.token)
                    except Exception as error:
                        sample["jvm"][metric] = {"error": type(error).__name__}
                stream.write(json.dumps(sample) + "\n")
                stream.flush()
                self.stop_event.wait(3)


def database_snapshot(mysql, destination):
    queries = {
        "status": "SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_running',"
                  "'Threads_connected','Questions','Slow_queries','Innodb_row_lock_waits',"
                  "'Innodb_row_lock_time','Innodb_buffer_pool_reads','Innodb_buffer_pool_read_requests',"
                  "'Innodb_data_reads','Innodb_data_writes','Innodb_log_waits');",
        "durability": "SELECT @@innodb_flush_log_at_trx_commit,@@sync_binlog,@@log_bin,"
                      "@@innodb_buffer_pool_size;",
        "size": "SELECT table_name,table_rows,data_length,index_length FROM information_schema.tables "
                "WHERE table_schema='rougether_scale' ORDER BY data_length+index_length DESC;",
        "statements": "SELECT DIGEST_TEXT,COUNT_STAR,SUM_TIMER_WAIT,SUM_ROWS_EXAMINED,"
                      "SUM_ROWS_SENT,SUM_ERRORS FROM performance_schema.events_statements_summary_by_digest "
                      "WHERE SCHEMA_NAME='rougether_scale' ORDER BY SUM_TIMER_WAIT DESC LIMIT 30;",
    }
    data = {"at": utc_now()}
    for name, sql in queries.items():
        try:
            data[name] = mysql(sql)
        except subprocess.SubprocessError as error:
            data[name] = {"error": str(error)}
    destination.write_text(json.dumps(data, indent=2) + "\n")
