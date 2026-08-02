#!/usr/bin/env python3
"""Collect maintainer decisions attached to Codex review finding threads."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path
from typing import Any
from urllib.parse import quote


BOT_PREFIX = "rougether-reviewer"
FINDING_KEY_RE = re.compile(rf"{BOT_PREFIX}:finding-key:([0-9a-f]{{24}})")
DECISION_COMMAND_RE = re.compile(r"^/reviewer\s+(accept|dismiss)$")
WRITE_PERMISSIONS = {"admin", "maintain", "write"}
TITLE_RE = re.compile(r"^\*\*[^:]+:\s*(?P<title>.+?)\*\*$")


def _gh_json(args: list[str]) -> Any:
    completed = subprocess.run(
        ["gh", "api", *args],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if not completed.stdout.strip():
        return None
    return json.loads(completed.stdout)


def _paginate(route: str) -> list[dict[str, Any]]:
    data = _gh_json(["--paginate", "--slurp", route])
    if not isinstance(data, list):
        return []
    rows: list[dict[str, Any]] = []
    for page in data:
        if isinstance(page, list):
            rows.extend(item for item in page if isinstance(item, dict))
        elif isinstance(page, dict):
            rows.append(page)
    return rows


def _load_json(path: str) -> Any:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _normalize_path(value: Any) -> str:
    text = str(value or "").strip()
    while text.startswith("./"):
        text = text[2:]
    return text


def _normalize_title(value: Any) -> str:
    return " ".join(str(value or "").split()).casefold()


def finding_key(finding: dict[str, Any]) -> str:
    raw = json.dumps(
        {
            "category": str(finding.get("category") or "other").strip().casefold(),
            "path": _normalize_path(finding.get("path")),
            "title": _normalize_title(finding.get("title")),
        },
        ensure_ascii=False,
        sort_keys=True,
    )
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:24]


def _extract_title(body: str) -> str:
    first_line = body.strip().splitlines()[0] if body.strip() else "Review finding"
    match = TITLE_RE.match(first_line)
    return match.group("title").strip() if match else first_line[:200]


def _decision_command(body: Any) -> str | None:
    match = DECISION_COMMAND_RE.fullmatch(str(body or "").strip())
    return match.group(1) if match else None


def _login(comment: dict[str, Any]) -> str:
    user = comment.get("user")
    return str(user.get("login") or "") if isinstance(user, dict) else ""


def _comment_sort_key(comment: dict[str, Any]) -> tuple[str, int]:
    try:
        comment_id = int(comment.get("id") or 0)
    except (TypeError, ValueError):
        comment_id = 0
    return str(comment.get("created_at") or ""), comment_id


def build_review_state(
    comments: list[dict[str, Any]],
    permissions: dict[str, str],
    pr_author: str = "",
) -> dict[str, Any]:
    roots: dict[str, dict[str, Any]] = {}
    for comment in comments:
        if comment.get("in_reply_to_id") is not None:
            continue
        body = str(comment.get("body") or "")
        match = FINDING_KEY_RE.search(body)
        if not match or comment.get("id") is None:
            continue
        roots[str(comment["id"])] = {
            "finding_key": match.group(1),
            "path": _normalize_path(comment.get("path")),
            "line": comment.get("line")
            if isinstance(comment.get("line"), int)
            else None,
            "title": _extract_title(body),
        }

    decisions: dict[str, dict[str, Any]] = {}
    ordered = sorted(comments, key=_comment_sort_key)
    author = pr_author.strip().casefold()
    for comment in ordered:
        root = roots.get(str(comment.get("in_reply_to_id") or ""))
        verdict = _decision_command(comment.get("body"))
        login = _login(comment)
        permission = str(permissions.get(login) or "").casefold()
        if not root or not verdict or permission not in WRITE_PERMISSIONS:
            continue
        # 리뷰 대상 PR 작성자는 write 권한자일 수밖에 없으므로(비-fork PR 전제),
        # 판정자 == 작성자인 셀프 판정은 제3자 판정이 아니라서 인정하지 않는다.
        if author and login.casefold() == author:
            continue
        key = str(root["finding_key"])
        decisions[key] = {
            "finding_key": key,
            "verdict": verdict,
            "by": login,
            "path": root["path"],
            "line": root["line"],
            "title": root["title"],
        }

    return {"version": 1, "decisions": list(decisions.values())}


def load_review_state(path: str | Path | None) -> dict[str, Any]:
    if not path:
        return {"version": 1, "decisions": []}
    payload = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(payload, dict) or not isinstance(payload.get("decisions"), list):
        raise RuntimeError("review state must contain a decisions array")
    return payload


def decided_finding_keys(state: dict[str, Any]) -> set[str]:
    return {
        str(item.get("finding_key"))
        for item in state.get("decisions", [])
        if isinstance(item, dict) and item.get("finding_key")
    }


def format_prompt_section(state: dict[str, Any]) -> str:
    decisions = [item for item in state.get("decisions", []) if isinstance(item, dict)]
    lines = [
        "## Maintainer Decisions",
        "",
        "아래 항목은 권한 있는 메인테이너가 기결한 finding입니다.",
        "`accept`와 `dismiss` 모두 다시 제기하거나 재토론하지 마세요.",
    ]
    if not decisions:
        lines.append("- 없음")
        return "\n".join(lines)
    for decision in decisions:
        safe = {
            "finding_key": decision.get("finding_key"),
            "verdict": decision.get("verdict"),
            "by": decision.get("by"),
            "location": (
                f"{decision.get('path')}:{decision.get('line')}"
                if decision.get("path") and decision.get("line")
                else decision.get("path") or "diff line 없음"
            ),
            "title": decision.get("title"),
        }
        lines.append(f"- {json.dumps(safe, ensure_ascii=False, sort_keys=True)}")
    return "\n".join(lines)


def _candidate_logins(comments: list[dict[str, Any]]) -> set[str]:
    return {
        _login(comment)
        for comment in comments
        if comment.get("in_reply_to_id") is not None
        and _decision_command(comment.get("body"))
    } - {""}


def _fetch_permissions(repo: str, logins: set[str]) -> dict[str, str]:
    permissions: dict[str, str] = {}
    for login in sorted(logins):
        try:
            payload = _gh_json(
                [f"repos/{repo}/collaborators/{quote(login, safe='')}/permission"]
            )
        except subprocess.CalledProcessError:
            permissions[login] = ""
            continue
        if isinstance(payload, dict):
            permissions[login] = str(payload.get("permission") or "")
    return permissions


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--pr-number", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--pr-author", default="")
    parser.add_argument("--comments-json", default="")
    parser.add_argument("--permissions-json", default="")
    args = parser.parse_args()

    comments = (
        _load_json(args.comments_json)
        if args.comments_json
        else _paginate(f"repos/{args.repo}/pulls/{args.pr_number}/comments")
    )
    if not isinstance(comments, list):
        raise RuntimeError("comments input must be an array")
    permissions = (
        _load_json(args.permissions_json)
        if args.permissions_json
        else _fetch_permissions(args.repo, _candidate_logins(comments))
    )
    if not isinstance(permissions, dict):
        raise RuntimeError("permissions input must be an object")

    pr_author = args.pr_author
    if not pr_author and not args.comments_json:
        # 워크플로가 --pr-author 를 빼먹어도 셀프 판정 필터가 조용히 꺼지지 않도록
        # 네트워크 모드에서는 PR 작성자를 직접 조회한다.
        payload = _gh_json([f"repos/{args.repo}/pulls/{args.pr_number}"])
        if isinstance(payload, dict) and isinstance(payload.get("user"), dict):
            pr_author = str(payload["user"].get("login") or "")

    state = build_review_state(
        [item for item in comments if isinstance(item, dict)],
        {str(key): str(value) for key, value in permissions.items()},
        pr_author=pr_author,
    )
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"review state written: {len(state['decisions'])} maintainer decisions")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
