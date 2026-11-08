"""격리 서비스의 대상 한 개만 정지하고 ALB/ECS 상태 변화를 기록한다."""

import time


def observe(
    ecs, alb, s3, cluster, service, definition, bucket, prefix, state_path, runner_done
):
    import json

    if cluster != "rougether-scale-test" or not definition.split("/")[-1].startswith(
        "api-loss-"
    ):
        raise RuntimeError("격리 실험 대상 불일치")
    svc = ecs.describe_services(cluster=cluster, services=[service])["services"][0]
    if svc["desiredCount"] != 2 or svc["taskDefinition"] != definition:
        raise RuntimeError("대상은 두 대로 배포된 실험 서비스여야 함")
    group = svc["loadBalancers"][0]["targetGroupArn"]

    def snapshot():
        ids = ecs.list_tasks(
            cluster=cluster, serviceName=service, desiredStatus="RUNNING"
        )["taskArns"]
        tasks = ecs.describe_tasks(cluster=cluster, tasks=ids)["tasks"] if ids else []
        nodes = {}
        for t in tasks:
            ips = [
                x["value"]
                for att in t["attachments"]
                for x in att["details"]
                if x["name"] == "privateIPv4Address"
            ]
            if ips and t["taskDefinitionArn"] == definition:
                nodes[ips[0]] = dict(task=t["taskArn"], status=t["lastStatus"])
        targets = {
            h["Target"]["Id"]: h["TargetHealth"]["State"]
            for h in alb.describe_target_health(TargetGroupArn=group)[
                "TargetHealthDescriptions"
            ]
        }
        return nodes, targets

    def now():
        return time.time()

    def has(key):
        return bool(
            s3.list_objects_v2(Bucket=bucket, Prefix=prefix + "/" + key, MaxKeys=1).get(
                "KeyCount"
            )
        )

    deadline = now() + 420
    while not has("ready"):
        if runner_done() or now() > deadline:
            raise RuntimeError("본부하 시작 표식 없음")
        time.sleep(2)
    time.sleep(20)
    nodes, targets = snapshot()
    if (
        len(nodes) != 2
        or {ip for ip, status in targets.items() if status == "healthy"} != set(nodes)
        or any(status not in {"draining", "healthy"} for status in targets.values())
    ):
        raise RuntimeError("두 실험 태스크가 모두 healthy 상태여야 함")
    victim, survivor = sorted(nodes)
    baseline = nodes.copy()
    event = {
        "before": baseline,
        "victim_ip": victim,
        "survivor_ip": survivor,
        "timeline": [],
    }

    def save():
        state_path.write_text(json.dumps(event, indent=2) + "\n")

    event["stop_sent"] = now()
    save()
    ecs.stop_task(
        cluster=cluster,
        task=baseline[victim]["task"],
        reason="QA isolated user-api one-task loss",
    )
    event["stop_ack"] = now()
    save()
    previous = None
    deadline = now() + 600
    while now() < deadline:
        nodes, targets = snapshot()
        t = now()
        if (nodes, targets) != previous:
            event["timeline"].append({"at": t, "nodes": nodes, "targets": targets})
            previous = (nodes, targets)
        if targets.get(victim) in (None, "draining", "unused", "unhealthy"):
            event.setdefault("victim_unhealthy_or_draining_at", t)
        if victim not in targets:
            event.setdefault("victim_absent_at", t)
        for ip, node in nodes.items():
            if (
                node["task"]
                not in (baseline[victim]["task"], baseline[survivor]["task"])
                and node["status"] == "RUNNING"
            ):
                event.setdefault("replacement_running_at", t)
                if targets.get(ip) == "healthy" and targets.get(survivor) == "healthy":
                    event.setdefault("replacement_healthy_at", t)
        save()
        if (
            "replacement_healthy_at" in event
            and "victim_unhealthy_or_draining_at" in event
            and t - event["replacement_healthy_at"] >= 20
        ):
            if runner_done():
                raise RuntimeError("부하가 복구 확인 전에 종료됨")
            s3.put_object(
                Bucket=bucket,
                Key=prefix + "/finish",
                Body=b"1",
                ServerSideEncryption="AES256",
            )
            event["finish_sent"] = now()
            save()
            # drain이 길면 본부하는 예정대로 종료하고 등록 해제만 관찰한다.
            until = now() + 330
            while "victim_absent_at" not in event and now() < until:
                time.sleep(2)
                _, targets = snapshot()
                if victim not in targets:
                    event["victim_absent_at"] = now()
                    save()
            return
        if runner_done():
            raise RuntimeError("복구 전 부하가 종료됨")
        time.sleep(1)
    raise RuntimeError("600초 안에 두 healthy 대상으로 복구되지 않음")
