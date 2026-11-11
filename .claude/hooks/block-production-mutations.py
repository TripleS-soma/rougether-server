#!/usr/bin/env python3
"""에이전트의 직접 원격 변경 금지. 정식 CI 배포만 허용한다."""
import json
import re
import sys

def blocked(command):
    patterns = [
        r"\b(?:send-command|start-session|start-automation-execution|send_command|start_session|start_automation_execution)\b",
        r"(?:^|[\s;&|(/])(?:ssh|scp|sftp|session-manager-plugin)(?:[\s\"'()]|$)",
        r"\bsystemctl\s+(?:restart|stop|start|reload|kill|disable|enable)\b",
        r"\bnginx\s+-s\b",
        r"\b(?:paramiko|fabric)\b",
    ]
    return any(re.search(pattern, command) for pattern in patterns)

if __name__ == "__main__":
    try:
        payload = json.load(sys.stdin)
        tool = payload.get("tool_input", {})
        command = tool.get("command", tool.get("cmd", tool.get("code", "")))
        if not isinstance(command, str):
            raise ValueError("invalid command payload")
    except (ValueError, TypeError):
        print("BLOCKED: cannot inspect command", file=sys.stderr)
        sys.exit(2)
    if blocked(command):
        print("BLOCKED: 직접 SSM/SSH/서비스 변경 금지. 정식 GitHub Actions 배포를 사용하세요. 배포 중 수동 복구도 금지합니다.", file=sys.stderr)
        sys.exit(2)
