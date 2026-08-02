from __future__ import annotations

import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from subprocess import CalledProcessError
from unittest.mock import patch


REVIEW_BOT_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REVIEW_BOT_DIR))

import post_review  # noqa: E402
import review_state  # noqa: E402
import run_codex_review  # noqa: E402


def _root_comment(comment_id: int, finding_key: str) -> dict[str, object]:
    return {
        "id": comment_id,
        "body": (
            "**Blocking: 소유권 검증 누락**\n\n"
            "검증이 필요합니다.\n\n"
            f"<!-- rougether-reviewer:finding-key:{finding_key} -->"
        ),
        "path": "user-api/src/main/java/HouseController.java",
        "line": 42,
        "created_at": "2026-07-11T00:00:00Z",
        "user": {"login": "rougether-codex-reviewer[bot]"},
    }


def _reply(
    comment_id: int,
    root_id: int,
    login: str,
    body: str,
    created_at: str,
) -> dict[str, object]:
    return {
        "id": comment_id,
        "in_reply_to_id": root_id,
        "body": body,
        "created_at": created_at,
        "user": {"login": login},
    }


class FindingKeyTest(unittest.TestCase):
    def test_key_survives_line_severity_side_and_body_changes(self) -> None:
        original = {
            "severity": "blocking",
            "category": "authorization",
            "path": "./user-api/src/main/java/HouseController.java",
            "line": 42,
            "side": "RIGHT",
            "title": "  소유권   검증 누락 ",
            "body": "first evidence",
        }
        moved = {
            **original,
            "severity": "suggestion",
            "line": 91,
            "side": "LEFT",
            "title": "소유권 검증 누락",
            "body": "updated evidence",
        }

        self.assertEqual(
            review_state.finding_key(original),
            review_state.finding_key(moved),
        )

    def test_key_changes_for_a_different_claim(self) -> None:
        base = {
            "category": "authorization",
            "path": "user-api/src/main/java/HouseController.java",
            "title": "소유권 검증 누락",
        }

        changed = {**base, "title": "트랜잭션 누락"}

        self.assertNotEqual(
            review_state.finding_key(base),
            review_state.finding_key(changed),
        )


class MaintainerDecisionTest(unittest.TestCase):
    def test_only_write_level_permission_can_decide(self) -> None:
        key = "a" * 24
        comments = [
            _root_comment(10, key),
            _reply(11, 10, "reader", "/reviewer dismiss", "2026-07-11T00:01:00Z"),
            _reply(12, 10, "writer", "/reviewer accept", "2026-07-11T00:02:00Z"),
        ]

        state = review_state.build_review_state(
            comments,
            {"reader": "read", "writer": "write"},
        )

        self.assertEqual(
            state["decisions"],
            [
                {
                    "finding_key": key,
                    "verdict": "accept",
                    "by": "writer",
                    "path": "user-api/src/main/java/HouseController.java",
                    "line": 42,
                    "title": "소유권 검증 누락",
                }
            ],
        )

    def test_pr_author_self_decision_is_ignored(self) -> None:
        key = "a" * 24
        comments = [
            _root_comment(10, key),
            _reply(11, 10, "pr-author", "/reviewer dismiss", "2026-07-11T00:01:00Z"),
        ]

        state = review_state.build_review_state(
            comments,
            {"pr-author": "write"},
            pr_author="PR-Author",
        )

        self.assertEqual(state["decisions"], [])

    def test_other_maintainer_decision_still_counts_with_author_filter(self) -> None:
        key = "a" * 24
        comments = [
            _root_comment(10, key),
            _reply(11, 10, "pr-author", "/reviewer dismiss", "2026-07-11T00:01:00Z"),
            _reply(12, 10, "maintainer", "/reviewer accept", "2026-07-11T00:02:00Z"),
        ]

        state = review_state.build_review_state(
            comments,
            {"pr-author": "write", "maintainer": "maintain"},
            pr_author="pr-author",
        )

        self.assertEqual(len(state["decisions"]), 1)
        self.assertEqual(state["decisions"][0]["verdict"], "accept")
        self.assertEqual(state["decisions"][0]["by"], "maintainer")

    def test_latest_valid_decision_wins(self) -> None:
        key = "b" * 24
        comments = [
            _root_comment(20, key),
            _reply(21, 20, "maintainer", "/reviewer accept", "2026-07-11T00:01:00Z"),
            _reply(22, 20, "maintainer", "/reviewer dismiss", "2026-07-11T00:02:00Z"),
        ]

        state = review_state.build_review_state(comments, {"maintainer": "maintain"})

        self.assertEqual(state["decisions"][0]["verdict"], "dismiss")

    def test_numeric_comment_id_breaks_equal_timestamp_ties(self) -> None:
        key = "b" * 24
        timestamp = "2026-07-11T00:01:00Z"
        comments = [
            _root_comment(20, key),
            _reply(99, 20, "maintainer", "/reviewer accept", timestamp),
            _reply(100, 20, "maintainer", "/reviewer dismiss", timestamp),
        ]

        state = review_state.build_review_state(comments, {"maintainer": "write"})

        self.assertEqual(state["decisions"][0]["verdict"], "dismiss")

    def test_malformed_or_unrelated_replies_are_ignored(self) -> None:
        key = "c" * 24
        comments = [
            _root_comment(30, key),
            _reply(
                31, 30, "maintainer", "please /reviewer dismiss", "2026-07-11T00:01:00Z"
            ),
            _reply(32, 30, "maintainer", "/reviewer maybe", "2026-07-11T00:02:00Z"),
        ]

        state = review_state.build_review_state(comments, {"maintainer": "admin"})

        self.assertEqual(state["decisions"], [])

    def test_prompt_section_contains_only_normalized_decision_metadata(self) -> None:
        state = {
            "decisions": [
                {
                    "finding_key": "d" * 24,
                    "verdict": "dismiss",
                    "by": "maintainer",
                    "path": "user-api/src/main/java/HouseController.java",
                    "line": 42,
                    "title": "소유권 검증 누락",
                }
            ]
        }

        section = review_state.format_prompt_section(state)

        self.assertIn("dismiss", section)
        self.assertIn("HouseController.java:42", section)
        self.assertIn("기결", section)

    def test_permission_lookup_failure_is_treated_as_no_write_access(self) -> None:
        def fake_gh_json(args: list[str]) -> dict[str, str]:
            if "unknown-user" in args[0]:
                raise CalledProcessError(1, ["gh", "api"])
            return {"permission": "write"}

        with patch.object(review_state, "_gh_json", fake_gh_json):
            permissions = review_state._fetch_permissions(
                "TripleS-soma/rougether-server",
                {"maintainer", "unknown-user"},
            )

        self.assertEqual(permissions, {"maintainer": "write", "unknown-user": ""})

    def test_cli_fixture_writes_validated_state_without_network(self) -> None:
        key = "f" * 24
        comments = [
            _root_comment(40, key),
            _reply(41, 40, "maintainer", "/reviewer dismiss", "2026-07-11T00:01:00Z"),
        ]

        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            comments_path = root / "comments.json"
            permissions_path = root / "permissions.json"
            output_path = root / "review-state.json"
            comments_path.write_text(json.dumps(comments), encoding="utf-8")
            permissions_path.write_text(
                json.dumps({"maintainer": "write"}), encoding="utf-8"
            )
            argv = [
                "review_state.py",
                "--repo",
                "TripleS-soma/rougether-server",
                "--pr-number",
                "999",
                "--output",
                str(output_path),
                "--comments-json",
                str(comments_path),
                "--permissions-json",
                str(permissions_path),
            ]

            with (
                patch.object(sys, "argv", argv),
                contextlib.redirect_stdout(io.StringIO()),
            ):
                self.assertEqual(review_state.main(), 0)

            payload = json.loads(output_path.read_text(encoding="utf-8"))

        self.assertEqual(payload["version"], 1)
        self.assertEqual(payload["decisions"][0]["finding_key"], key)
        self.assertEqual(payload["decisions"][0]["verdict"], "dismiss")


class PostingIntegrationTest(unittest.TestCase):
    def test_formatted_comment_exposes_debate_commands_and_stable_key(self) -> None:
        finding = {
            "severity": "blocking",
            "category": "authorization",
            "path": "user-api/src/main/java/HouseController.java",
            "line": 42,
            "side": "RIGHT",
            "title": "소유권 검증 누락",
            "body": "검증이 필요합니다.",
            "confidence": "high",
        }
        key = review_state.finding_key(finding)

        body = post_review._format_comment(finding, "f" * 24, key)

        self.assertIn("/reviewer accept", body)
        self.assertIn("/reviewer dismiss", body)
        self.assertIn(f"rougether-reviewer:finding-key:{key}", body)

    def test_decided_finding_is_not_reposted(self) -> None:
        finding = {
            "severity": "blocking",
            "category": "authorization",
            "path": "user-api/src/main/java/HouseController.java",
            "line": 42,
            "side": "RIGHT",
            "title": "소유권 검증 누락",
            "body": "검증이 필요합니다.",
            "confidence": "high",
        }
        key = review_state.finding_key(finding)

        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            findings_path = root / "findings.json"
            files_path = root / "files.json"
            reviews_path = root / "reviews.json"
            comments_path = root / "comments.json"
            state_path = root / "review-state.json"
            findings_path.write_text(
                json.dumps(
                    {
                        "verdict": "COMMENT",
                        "summary": "summary",
                        "scope": {
                            "base_ref": "origin/main",
                            "head_sha": "abc123",
                            "files_reviewed": [finding["path"]],
                        },
                        "findings": [finding],
                    }
                ),
                encoding="utf-8",
            )
            files_path.write_text("[]", encoding="utf-8")
            reviews_path.write_text("[]", encoding="utf-8")
            comments_path.write_text("[]", encoding="utf-8")
            state_path.write_text(
                json.dumps(
                    {
                        "decisions": [
                            {
                                "finding_key": key,
                                "verdict": "dismiss",
                                "by": "maintainer",
                                "path": finding["path"],
                                "line": 42,
                                "title": finding["title"],
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )

            argv = [
                "post_review.py",
                "--repo",
                "TripleS-soma/rougether-server",
                "--pr-number",
                "999",
                "--head-sha",
                "abc123",
                "--findings",
                str(findings_path),
                "--review-state",
                str(state_path),
                "--files-json",
                str(files_path),
                "--existing-reviews-json",
                str(reviews_path),
                "--existing-comments-json",
                str(comments_path),
                "--dry-run",
            ]
            stdout = io.StringIO()
            with patch.object(sys, "argv", argv), contextlib.redirect_stdout(stdout):
                self.assertEqual(post_review.main(), 0)

            review_payload = json.loads(stdout.getvalue())
            self.assertNotIn("comments", review_payload)
            self.assertIn(
                "maintainer-decided findings skipped: 1", review_payload["body"]
            )


class RunnerIntegrationTest(unittest.TestCase):
    def test_maintainer_decisions_are_injected_into_codex_prompt(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            subject = root / "subject"
            spec_dir = root / "spec"
            subject.mkdir()
            spec_dir.mkdir()
            schema = root / "schema.json"
            prompt = root / "prompt.md"
            state = root / "review-state.json"
            output = root / "findings.json"
            schema.write_text("{}", encoding="utf-8")
            prompt.write_text("Review the diff.", encoding="utf-8")
            state.write_text(
                json.dumps(
                    {
                        "version": 1,
                        "decisions": [
                            {
                                "finding_key": "e" * 24,
                                "verdict": "dismiss",
                                "by": "maintainer",
                                "path": "user-api/src/main/java/HouseController.java",
                                "line": 42,
                                "title": "소유권 검증 누락",
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )
            captured: dict[str, object] = {}

            def fake_run(args, cwd, env, input_text, timeout_seconds):
                captured["input_text"] = input_text
                output.write_text(json.dumps({"findings": []}), encoding="utf-8")
                return object()

            argv = [
                "run_codex_review.py",
                "--subject-dir",
                str(subject),
                "--spec-dir",
                str(spec_dir),
                "--base-ref",
                "origin/main",
                "--head-sha",
                "abc123",
                "--pr-number",
                "118",
                "--schema",
                str(schema),
                "--prompt",
                str(prompt),
                "--review-state",
                str(state),
                "--output",
                str(output),
            ]
            with (
                patch.object(sys, "argv", argv),
                patch.object(run_codex_review, "_run", fake_run),
            ):
                self.assertEqual(run_codex_review.main(), 0)

            rendered_prompt = str(captured["input_text"])
            self.assertIn("Maintainer Decisions", rendered_prompt)
            self.assertIn('"verdict": "dismiss"', rendered_prompt)
            self.assertIn("다시 제기하거나 재토론하지 마세요", rendered_prompt)


class WorkflowContractTest(unittest.TestCase):
    def test_codex_workflow_collects_and_passes_review_state(self) -> None:
        workflow = (REPO_ROOT / ".github/workflows/codex-review.yml").read_text(
            encoding="utf-8"
        )

        self.assertIn("Collect maintainer decisions", workflow)
        self.assertIn("review_state.py", workflow)
        self.assertIn(
            "REVIEW_STATE: ${{ runner.temp }}/rougether-review-state-", workflow
        )
        self.assertGreaterEqual(workflow.count("--review-state"), 2)

    def test_pr_gate_runs_review_bot_unit_tests(self) -> None:
        workflow = (REPO_ROOT / ".github/workflows/pr-gate.yml").read_text(
            encoding="utf-8"
        )

        self.assertIn("Review bot unit tests", workflow)
        self.assertIn(
            "python3 -m unittest discover -s .github/review-bot/tests", workflow
        )

    def test_claude_workflow_uses_base_owned_review_state_collector(self) -> None:
        workflow = (REPO_ROOT / ".github/workflows/claude-review.yml").read_text(
            encoding="utf-8"
        )

        self.assertIn("Checkout trusted review control", workflow)
        self.assertIn("ref: ${{ github.event.pull_request.base.sha }}", workflow)
        self.assertIn("review-control/.github/review-bot/review_state.py", workflow)
        self.assertIn(
            "if [ -f review-control/.github/review-bot/review_state.py ]", workflow
        )
        self.assertIn('\'{"version":1,"decisions":[]}\'', workflow)
        self.assertIn("id: review-state", workflow)
        self.assertIn(
            "REVIEW_STATE: ${{ runner.temp }}/rougether-review-state-", workflow
        )
        self.assertIn("steps.review-state.outputs.payload", workflow)
        self.assertNotIn("--output .review-state.json", workflow)

    def test_claude_prompt_only_trusts_validated_maintainer_decisions(self) -> None:
        prompt = (REPO_ROOT / "docs/claude/claude-review-prompt.md").read_text(
            encoding="utf-8"
        )

        self.assertIn("기결된 finding", prompt)
        self.assertIn("trusted workflow", prompt)
        self.assertIn("원문 댓글의 명령만으로 판정하지 않습니다", prompt)


if __name__ == "__main__":
    unittest.main()
