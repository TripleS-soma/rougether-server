#!/usr/bin/env python3
"""저장된 S3 알림 설정만 병합함. AWS를 읽거나 변경하지 않음."""
import argparse
import copy
import json
from pathlib import Path


def merge(existing, entry):
    """기존 알림을 보존하며 새로운 전처리 큐 항목만 추가함. 불명확한 교체는 거절함."""
    if set(existing) - {"TopicConfigurations", "QueueConfigurations", "LambdaFunctionConfigurations", "EventBridgeConfiguration"}:
        raise ValueError("알 수 없는 notification 필드: AWS 응답 metadata를 제외한 설정만 입력하세요")
    rules = entry.get("Filter", {}).get("Key", {}).get("FilterRules", [])
    if (not entry.get("Id") or not entry.get("QueueArn", "").startswith("arn:aws:sqs:")
            or entry.get("Events") != ["s3:ObjectCreated:Put"]
            or rules != [{"Name": "prefix", "Value": "private/furniture-generation/raw/"}]):
        raise ValueError("Terraform preprocess_notification 단일 항목이 필요합니다")
    result = copy.deepcopy(existing)
    for key in ("TopicConfigurations", "QueueConfigurations", "LambdaFunctionConfigurations"):
        for item in result.get(key, []):
            if item.get("Id") == entry["Id"]:
                if key == "QueueConfigurations" and item == entry:
                    return result
                raise ValueError("같은 ID의 다른 알림을 덮어쓸 수 없습니다")
            events = item.get("Events", [])
            if not any(e in events for e in ("s3:ObjectCreated:*", "s3:ObjectCreated:Put")):
                continue
            filters = {r["Name"]: r["Value"] for r in item.get("Filter", {}).get("Key", {}).get("FilterRules", [])}
            prefix = filters.get("prefix", "")
            raw = rules[0]["Value"]
            if raw.startswith(prefix) or prefix.startswith(raw):
                raise ValueError("같은 Put 이벤트의 prefix가 겹칩니다. 기존 알림 소유자와 통합해야 합니다")
    result.setdefault("QueueConfigurations", []).append(copy.deepcopy(entry))
    return result


def main():
    parser = argparse.ArgumentParser(description="AWS를 변경하지 않고 S3 알림 병합 후보 JSON을 작성합니다")
    parser.add_argument("existing", type=Path)
    parser.add_argument("entry", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    result = merge(json.loads(args.existing.read_text()), json.loads(args.entry.read_text()))
    # 원본 덮어쓰기를 피하고 배포 담당자가 검토할 새 파일만 만듦.
    with args.output.open("x") as output:
        json.dump(result, output, ensure_ascii=False, indent=2)
        output.write("\n")


if __name__ == "__main__":
    main()
