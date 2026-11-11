#!/usr/bin/env python3
"""승인된 배포에서만 LLM 키를 교체하고 가구 Lambda가 새 버전을 읽게 한다."""
import json
import os
import subprocess
import sys

PARAMETER = "/rougether-dev/llm/api-key"
FUNCTION = "rougether-dev-furniture-ai"


class RotationError(Exception):
    pass


def aws(service, action, payload):
    result = subprocess.run(
        ["aws", service, *action.split(), "--cli-input-json", "file:///dev/stdin", "--output", "json", "--no-cli-pager"],
        input=json.dumps(payload), text=True, capture_output=True, timeout=90,
    )
    if result.returncode:
        # SDK/CLI 오류 본문에는 요청 값이 포함될 수 있으므로 노출하지 않는다.
        raise RotationError(service + ":" + action + " failed")
    return json.loads(result.stdout) if result.stdout.strip() else {}


def rotate(key):
    if not key or not key.startswith("sk-") or len(key) > 512 or any(c.isspace() for c in key):
        raise RotationError("Rotation secret missing or invalid")
    before = aws("lambda", "get-function-configuration", {"FunctionName": FUNCTION})
    if before.get("State") != "Active" or before.get("LastUpdateStatus") != "Successful":
        raise RotationError("Furniture Lambda is not ready for rotation")
    variables = dict(before.get("Environment", {}).get("Variables", {}))
    if variables.get("LLM_API_KEY_PARAMETER") != PARAMETER:
        raise RotationError("Furniture Lambda credential source differs")
    result = aws("ssm", "put-parameter", {
        "Name": PARAMETER, "Type": "SecureString", "Value": key, "Overwrite": True,
    })
    version = str(result["Version"])
    variables["LLM_API_KEY_VERSION"] = version
    aws("lambda", "update-function-configuration", {
        "FunctionName": FUNCTION, "RevisionId": before["RevisionId"],
        "Environment": {"Variables": variables},
    })
    aws("lambda", "wait function-updated-v2", {"FunctionName": FUNCTION})
    after = aws("lambda", "get-function-configuration", {"FunctionName": FUNCTION})
    if (after.get("LastUpdateStatus") != "Successful" or after.get("State") != "Active"
            or after.get("Environment", {}).get("Variables") != variables):
        raise RotationError("Furniture Lambda rotation verification failed")
    return {"parameter": PARAMETER, "version": int(version), "function": FUNCTION, "state": after["State"]}


if __name__ == "__main__":
    try:
        print(json.dumps(rotate(os.environ.pop("LLM_API_KEY_ROTATION", ""))))
    except Exception as error:
        print(str(error) if isinstance(error, RotationError) else type(error).__name__, file=sys.stderr)
        sys.exit(1)
