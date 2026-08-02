#!/usr/bin/env python3
"""Run Codex review with the repository-owned prompt and schema."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

from review_state import format_prompt_section, load_review_state


SENSITIVE_ENV_PREFIXES = ("OPENAI_",)
SENSITIVE_ENV_KEYS = {
    "CODEX_API_KEY",
    "CODEX_ACCESS_TOKEN",
    "OPENAI_API_KEY",
    "OPENAI_ADMIN_KEY",
}


def _run(
    args: list[str],
    cwd: Path,
    env: dict[str, str],
    input_text: str,
    timeout_seconds: int,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        args,
        cwd=str(cwd),
        env=env,
        check=True,
        text=True,
        input=input_text,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout_seconds,
    )


def _clean_env() -> dict[str, str]:
    env = os.environ.copy()
    for key in list(env):
        if key in SENSITIVE_ENV_KEYS or key.startswith(SENSITIVE_ENV_PREFIXES):
            env.pop(key, None)
    return env


def _read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _validate_json(path: Path) -> None:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        raise RuntimeError(f"Codex review output is not valid JSON: {path}") from exc
    if not isinstance(payload, dict):
        raise RuntimeError("Codex review output must be a JSON object")
    if "findings" not in payload or not isinstance(payload["findings"], list):
        raise RuntimeError("Codex review output must include findings array")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--subject-dir", required=True)
    parser.add_argument("--spec-dir", required=True)
    parser.add_argument("--base-ref", required=True)
    parser.add_argument("--head-sha", required=True)
    parser.add_argument("--pr-number", required=True)
    parser.add_argument("--schema", required=True)
    parser.add_argument("--prompt", required=True)
    parser.add_argument("--review-state", default="")
    parser.add_argument("--output", required=True)
    parser.add_argument("--model", default="gpt-5.6-sol")
    parser.add_argument(
        "--reasoning-effort",
        choices=("low", "medium", "high", "xhigh"),
        default="high",
    )
    parser.add_argument("--timeout-seconds", type=int, default=1500)
    args = parser.parse_args()

    subject_dir = Path(args.subject_dir).resolve()
    spec_dir = Path(args.spec_dir).resolve()
    schema = Path(args.schema).resolve()
    prompt_path = Path(args.prompt).resolve()
    output = Path(args.output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)

    if not subject_dir.is_dir():
        raise RuntimeError(f"subject directory not found: {subject_dir}")
    if not spec_dir.is_dir():
        raise RuntimeError(f"spec directory not found: {spec_dir}")

    base_prompt = _read_text(prompt_path)
    maintainer_decisions = format_prompt_section(load_review_state(args.review_state))
    prompt = f"""{base_prompt}

{maintainer_decisions}

## Runtime Context

- PR: #{args.pr_number}
- Base ref: `{args.base_ref}`
- Head SHA: `{args.head_sha}`
- Spec repository path: `{spec_dir}`

Use `git diff --stat {args.base_ref}...HEAD`, `git diff --name-only {args.base_ref}...HEAD`,
and targeted file reads to review only this PR's changes. Return only JSON matching the schema.
"""

    env = _clean_env()
    cmd = [
        "codex",
        "exec",
        "--sandbox",
        "read-only",
        "--ephemeral",
        "--ignore-user-config",
        "--ignore-rules",
        "-c",
        f'model_reasoning_effort="{args.reasoning_effort}"',
        "--model",
        args.model,
        "--add-dir",
        str(spec_dir),
        "--output-schema",
        str(schema),
        "--output-last-message",
        str(output),
        "-",
    ]

    print("Running Codex review with saved Codex CLI auth", file=sys.stderr)
    try:
        _run(
            cmd,
            cwd=subject_dir,
            env=env,
            input_text=prompt,
            timeout_seconds=args.timeout_seconds,
        )
    except subprocess.TimeoutExpired as exc:
        if exc.stdout:
            print(exc.stdout, file=sys.stderr)
        if exc.stderr:
            print(exc.stderr, file=sys.stderr)
        raise RuntimeError(
            f"Codex review timed out after {args.timeout_seconds} seconds"
        ) from exc
    except subprocess.CalledProcessError as exc:
        if exc.stdout:
            print(exc.stdout, file=sys.stderr)
        if exc.stderr:
            print(exc.stderr, file=sys.stderr)
        raise

    if not output.exists() or output.stat().st_size == 0:
        raise RuntimeError(f"Codex did not write review output: {output}")
    _validate_json(output)
    print(f"Codex review output written: {output}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
