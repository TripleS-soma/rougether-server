#!/usr/bin/env python3
"""Post Codex review findings as an advisory GitHub pull request review."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any


BOT_PREFIX = "rougether-reviewer"
HEAD_MARKER = "<!-- rougether-reviewer:reviewed:"
FINGERPRINT_RE = re.compile(r"rougether-reviewer:fingerprint:([0-9a-f]{16,64})")
HUNK_RE = re.compile(r"^@@ -(?P<old>\d+)(?:,\d+)? \+(?P<new>\d+)(?:,\d+)? @@")


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


def _gh_api_input(route: str, payload: dict[str, Any]) -> Any:
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False) as fp:
        json.dump(payload, fp, ensure_ascii=False)
        path = fp.name
    try:
        return _gh_json(["-X", "POST", route, "--input", path])
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass


def _paginate(route: str) -> list[dict[str, Any]]:
    data = _gh_json(["--paginate", "--slurp", route])
    if isinstance(data, list):
        rows: list[dict[str, Any]] = []
        for page in data:
            if isinstance(page, list):
                rows.extend(item for item in page if isinstance(item, dict))
            elif isinstance(page, dict):
                rows.append(page)
        return rows
    return []


def _load_findings(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise RuntimeError("findings file must contain a JSON object")
    payload.setdefault("findings", [])
    if not isinstance(payload["findings"], list):
        raise RuntimeError("findings must be an array")
    return payload


def _load_rows(path: str) -> list[dict[str, Any]]:
    if not path:
        return []
    payload = json.loads(Path(path).read_text(encoding="utf-8"))
    if isinstance(payload, list):
        return [item for item in payload if isinstance(item, dict)]
    raise RuntimeError(f"fixture must contain a JSON array: {path}")


def _normalize_path(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    text = value.strip()
    if not text:
        return None
    while text.startswith("./"):
        text = text[2:]
    return text


def _commentable_lines(files: list[dict[str, Any]]) -> dict[str, dict[str, set[int]]]:
    result: dict[str, dict[str, set[int]]] = {}
    for file_info in files:
        path = _normalize_path(file_info.get("filename"))
        patch = file_info.get("patch")
        if not path or not isinstance(patch, str):
            continue
        sides = {"RIGHT": set(), "LEFT": set()}
        old_line: int | None = None
        new_line: int | None = None
        for raw in patch.splitlines():
            match = HUNK_RE.match(raw)
            if match:
                old_line = int(match.group("old"))
                new_line = int(match.group("new"))
                continue
            if old_line is None or new_line is None:
                continue
            if raw.startswith("+"):
                sides["RIGHT"].add(new_line)
                new_line += 1
            elif raw.startswith("-"):
                sides["LEFT"].add(old_line)
                old_line += 1
            elif raw.startswith(" "):
                sides["LEFT"].add(old_line)
                sides["RIGHT"].add(new_line)
                old_line += 1
                new_line += 1
            elif raw.startswith("\\"):
                continue
        result[path] = sides
    return result


def _fingerprint(finding: dict[str, Any]) -> str:
    raw = json.dumps(
        {
            "severity": finding.get("severity"),
            "category": finding.get("category"),
            "path": finding.get("path"),
            "line": finding.get("line"),
            "side": finding.get("side"),
            "title": finding.get("title"),
        },
        ensure_ascii=False,
        sort_keys=True,
    )
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:24]


def _fingerprints_from_comments(comments: list[dict[str, Any]]) -> set[str]:
    found: set[str] = set()
    for comment in comments:
        body = str(comment.get("body") or "")
        found.update(FINGERPRINT_RE.findall(body))
    return found


def _existing_fingerprints(repo: str, pr_number: str) -> set[str]:
    comments = _paginate(f"repos/{repo}/pulls/{pr_number}/comments")
    return _fingerprints_from_comments(comments)


def _reviewed_head_from_reviews(reviews: list[dict[str, Any]], head_sha: str) -> bool:
    marker = f"{HEAD_MARKER}{head_sha} -->"
    return any(marker in str(review.get("body") or "") for review in reviews)


def _already_reviewed_head(repo: str, pr_number: str, head_sha: str) -> bool:
    reviews = _paginate(f"repos/{repo}/pulls/{pr_number}/reviews")
    return _reviewed_head_from_reviews(reviews, head_sha)


def _format_comment(finding: dict[str, Any], fingerprint: str) -> str:
    severity = str(finding.get("severity") or "suggestion")
    label = {
        "blocking": "Blocking",
        "suggestion": "Suggestion",
        "info": "Info",
    }.get(severity, severity)
    category = str(finding.get("category") or "other")
    confidence = str(finding.get("confidence") or "medium")
    title = str(finding.get("title") or "Review finding").strip()
    body = str(finding.get("body") or "").strip()
    return (
        f"**{label}: {title}**\n\n"
        f"{body}\n\n"
        f"_category: `{category}`, confidence: `{confidence}`_\n\n"
        f"<!-- {BOT_PREFIX}:fingerprint:{fingerprint} -->"
    )


def _format_summary(
    payload: dict[str, Any],
    inline_count: int,
    fallback: list[dict[str, Any]],
    run_url: str | None,
    head_sha: str,
) -> str:
    verdict = str(payload.get("verdict") or "COMMENT")
    summary = str(payload.get("summary") or "").strip()
    lines = [
        f"<!-- {BOT_PREFIX}:reviewed:{head_sha} -->",
        "자동 리뷰 (Codex OAuth, advisory). 사람 리뷰를 대체하지 않습니다.",
        "",
        f"## 결과: {verdict}",
        "",
        summary or "요약 없음",
        "",
        f"- inline comments: {inline_count}",
        f"- summary-only findings: {len(fallback)}",
    ]
    if run_url:
        lines.append(f"- run: {run_url}")
    if fallback:
        lines.extend(["", "## Summary-only findings"])
        for item in fallback:
            severity = str(item.get("severity") or "suggestion")
            title = str(item.get("title") or "Review finding").strip()
            body = str(item.get("body") or "").strip()
            path = item.get("path")
            line = item.get("line")
            location = f"{path}:{line}" if path and line else "diff line 없음"
            lines.append(f"- [{severity}] {location} {title} - {body}")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--pr-number", required=True)
    parser.add_argument("--head-sha", required=True)
    parser.add_argument("--findings", required=True)
    parser.add_argument("--run-url", default="")
    parser.add_argument("--max-inline", type=int, default=15)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--files-json", default="", help="Local fixture for PR files API rows")
    parser.add_argument(
        "--existing-comments-json",
        default="",
        help="Local fixture for existing PR review comments",
    )
    parser.add_argument(
        "--existing-reviews-json",
        default="",
        help="Local fixture for existing PR reviews",
    )
    args = parser.parse_args()

    payload = _load_findings(Path(args.findings))
    existing_reviews = _load_rows(args.existing_reviews_json)
    if (
        _reviewed_head_from_reviews(existing_reviews, args.head_sha)
        if args.existing_reviews_json
        else _already_reviewed_head(args.repo, args.pr_number, args.head_sha)
    ):
        print(f"review already posted for head {args.head_sha}")
        return 0

    files = (
        _load_rows(args.files_json)
        if args.files_json
        else _paginate(f"repos/{args.repo}/pulls/{args.pr_number}/files")
    )
    line_map = _commentable_lines(files)
    existing = (
        _fingerprints_from_comments(_load_rows(args.existing_comments_json))
        if args.existing_comments_json
        else _existing_fingerprints(args.repo, args.pr_number)
    )

    comments: list[dict[str, Any]] = []
    fallback: list[dict[str, Any]] = []

    for finding in payload.get("findings", []):
        if not isinstance(finding, dict):
            continue
        fingerprint = _fingerprint(finding)
        if fingerprint in existing:
            continue
        path = _normalize_path(finding.get("path"))
        line = finding.get("line")
        side = finding.get("side") or "RIGHT"
        if (
            len(comments) < args.max_inline
            and path
            and isinstance(line, int)
            and side in {"RIGHT", "LEFT"}
            and line in line_map.get(path, {}).get(side, set())
        ):
            comments.append(
                {
                    "path": path,
                    "line": line,
                    "side": side,
                    "body": _format_comment(finding, fingerprint),
                }
            )
        else:
            fallback.append(finding)

    body = _format_summary(
        payload=payload,
        inline_count=len(comments),
        fallback=fallback,
        run_url=args.run_url or None,
        head_sha=args.head_sha,
    )
    review_payload = {
        "commit_id": args.head_sha,
        "event": "COMMENT",
        "body": body,
    }
    if comments:
        review_payload["comments"] = comments

    if args.dry_run:
        print(json.dumps(review_payload, ensure_ascii=False, indent=2))
        return 0

    _gh_api_input(f"repos/{args.repo}/pulls/{args.pr_number}/reviews", review_payload)
    print(f"posted Codex review: {len(comments)} inline, {len(fallback)} summary-only")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
