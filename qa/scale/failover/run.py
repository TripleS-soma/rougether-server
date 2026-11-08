"""One Fargate API service, one isolated schema, and restoration after the run."""

import hashlib
import copy
import datetime
import json
import os
import secrets
import signal
import re
import tarfile
import tempfile
import time
from pathlib import Path
import boto3
from botocore.config import Config

from observe import observe

HERE = Path(__file__).resolve().parent

TASK_FIELDS = (
    "taskRoleArn",
    "executionRoleArn",
    "networkMode",
    "containerDefinitions",
    "volumes",
    "requiresCompatibilities",
    "cpu",
    "memory",
    "runtimePlatform",
    "ephemeralStorage",
)
LOAD = r"""import boto3,os,tarfile,io
from pathlib import Path
p=Path(os.environ.get('WORK_ROOT','/tmp/mix'));p.mkdir(exist_ok=True)
c=boto3.client('s3')
b=os.environ['EVIDENCE_BUCKET'];prefix=os.environ['MIX_PREFIX']
with tarfile.open(fileobj=io.BytesIO(c.get_object(Bucket=b,Key=prefix+'/bundle.tgz')['Body'].read()),mode='r:gz') as archive:
 archive.extractall(p,filter='data')
"""
DOWNLOAD_JAR = "\nc.download_file(b,prefix+'/api.jar','/mix/app.jar');os.chmod('/mix/app.jar',0o444)"
RUN = (
    LOAD
    + "\nimport subprocess,sys\nsys.exit(subprocess.call([sys.executable,str(p/'remote.py'),sys.argv[1]]))"
)


def stamp():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def say(*msg):
    print(stamp(), *msg, flush=True)


def wait(check, desc, deadline=1800, interval=10):
    end = time.monotonic() + deadline
    while time.monotonic() < end:
        res = check()
        if res:
            return res
        time.sleep(interval)
    raise RuntimeError(desc + " timeout")


def main():
    os.umask(0o077)
    if not __debug__:
        raise RuntimeError("실험 환경 검증을 생략할 수 없습니다.")
    cfg = json.loads(Path(os.environ["ROUGETHER_SCALE_OUTPUTS"]).read_text())[
        "scale_test"
    ]["value"]
    out = Path(os.environ["DRILL_OUTPUT"]).resolve()
    jar = Path(os.environ["DRILL_JAR"]).resolve()
    digest = os.environ["DRILL_RUNTIME_DIGEST"]
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", digest) or not jar.is_file():
        raise ValueError("실험 JAR 또는 런타임 digest 확인 필요")
    runid = str(int(time.time()))
    ident = f"api-loss-{runid}"
    schema = "lossqa_" + runid
    session = boto3.Session(profile_name="rougether-isb", region_name=cfg["region"])
    assert (
        session.client("sts").get_caller_identity()["Account"]
        == cfg["account_id"]
        == "776158585524"
        and cfg["cluster_name"] == "rougether-scale-test"
    )
    ec = session.client("ecs")
    rds = session.client("rds")
    s3 = session.client("s3", config=Config(signature_version="s3v4"))
    cluster = cfg["cluster_name"]
    service = cfg["service_name"]
    bucket = cfg["evidence_bucket"]
    prefix = "scale-test/" + ident

    def svc():
        return ec.describe_services(cluster=cluster, services=[service])["services"][0]

    def database():
        return rds.describe_db_instances(DBInstanceIdentifier="rougether-scale-test")[
            "DBInstances"
        ][0]

    initial = svc()
    d = database()
    old = initial["taskDefinition"]
    net = initial["networkConfiguration"]["awsvpcConfiguration"]
    assert (
        old == cfg["task_definitions"]["api"]
        and initial["runningCount"] == initial["desiredCount"] == 1
        and len(initial["deployments"]) == 1
        and initial["deployments"][0]["rolloutState"] == "COMPLETED"
    )
    assert (
        d["DBInstanceClass"] == "db.t4g.medium"
        and d["DBInstanceStatus"] == "available"
        and not d["PendingModifiedValues"]
        and d["DbiResourceId"] == "db-VNB4SIBHFGFPH44ULM7C2RMNPA"
    )
    api = ec.describe_task_definition(taskDefinition=old)["taskDefinition"]
    runner = ec.describe_task_definition(
        taskDefinition=cfg["task_definitions"]["runner"]
    )["taskDefinition"]
    state = {
        "id": ident,
        "schema": schema,
        "start": stamp(),
        "defs": [],
        "tasks": [],
        "service_changed": False,
        "jar_sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
        "created_schema": False,
        "cleanup_errors": [],
    }
    state_path = out / (ident + ".json")
    out.mkdir(parents=True, exist_ok=False)

    def save():
        state_path.write_text(json.dumps(state, indent=2, default=str))

    save()

    def dbready(cls):
        x = database()
        return (
            x["DBInstanceClass"] == cls
            and x["DBInstanceStatus"] == "available"
            and not x["PendingModifiedValues"]
        )

    def stable(defn):
        x = svc()
        return (
            x["taskDefinition"] == defn
            and len(x["deployments"]) == 1
            and x["desiredCount"] == x["runningCount"] == (1 if defn == old else 2)
            and x["pendingCount"] == 0
            and x["deployments"][0].get("rolloutState") == "COMPLETED"
        )

    def register(base, family, containers, cpu, mem, volumes=None):
        req = {k: copy.deepcopy(base[k]) for k in TASK_FIELDS if k in base}
        req.update(
            family=family,
            containerDefinitions=containers,
            cpu=str(cpu),
            memory=str(mem),
        )
        if volumes is not None:
            req["volumes"] = volumes
        arn = ec.register_task_definition(
            **req, tags=[{"key": "Purpose", "value": "scale-test"}]
        )["taskDefinition"]["taskDefinitionArn"]
        state["defs"].append(arn)
        save()
        return arn

    baseRunner = runner["containerDefinitions"][0]
    runnerEnv = {x["name"]: x["value"] for x in baseRunner["environment"]}
    assert (
        runnerEnv["DB_HOST"] == d["Endpoint"]["Address"]
        and runnerEnv["DB_INSTANCE_ID"] == "rougether-scale-test"
    )
    commonenv = {
        **runnerEnv,
        "MIX_PREFIX": prefix,
        "SCHEMA": schema,
        "BASE_URL": cfg["base_url"],
        "EVIDENCE_BUCKET": bucket,
    }

    def envlist(data):
        return [{"name": k, "value": str(v)} for k, v in data.items()]

    def remote(mode, arn, timeout=1500):
        response = ec.run_task(
            cluster=cluster,
            taskDefinition=arn,
            launchType="FARGATE",
            count=1,
            startedBy=ident,
            tags=[{"key": "Purpose", "value": "scale-test"}],
            networkConfiguration={
                "awsvpcConfiguration": {
                    **net,
                    "securityGroups": [cfg["security_group_ids"]["runner"]],
                }
            },
            overrides={
                "containerOverrides": [{"name": "runner", "command": [RUN, mode]}]
            },
        )
        if response.get("failures") or not response["tasks"]:
            raise RuntimeError(str(response.get("failures")))
        task = response["tasks"][0]["taskArn"]
        state["tasks"].append(task)
        save()
        say(mode, "task submitted")

        def check():
            t = ec.describe_tasks(cluster=cluster, tasks=[task])["tasks"][0]
            return t if t["lastStatus"] == "STOPPED" else None

        if mode == "load":
            observe(
                ec,
                session.client("elbv2"),
                s3,
                cluster,
                service,
                apidef,
                bucket,
                prefix,
                out / "timeline.json",
                check,
            )
        t = wait(check, mode, timeout, 5)
        result = [
            (x["name"], x.get("exitCode"), x.get("reason", "")) for x in t["containers"]
        ]
        say(mode, "finished", result)
        state[mode + "_exit"] = result
        save()
        if not all(x[1] == 0 for x in result):
            raise RuntimeError(f"{mode} failed {result} {t.get('stoppedReason')}")

    def signal_exit(*_):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, signal_exit)
    bucket_touched = False
    runnerdef = None
    try:
        # Host and credentials come from the existing scale-test runner, with a separate DB name.
        qa_jwt = secrets.token_urlsafe(48)
        with tempfile.TemporaryDirectory(prefix="rt-aws-mix-") as tmp:
            tmp = Path(tmp)
            bundle = tmp / "bundle.tgz"
            with tarfile.open(bundle, "w:gz") as tar:
                for path in (
                    HERE / "remote.py",
                    HERE / "workload.py",
                    HERE.parent / "qps.py",
                    HERE.parent / "seed/seed_scale_db.py",
                    HERE.parent / "seed/mint_test_jwts.py",
                ):
                    tar.add(path, arcname=path.name)
            bucket_touched = True
            s3.upload_file(
                str(bundle),
                bucket,
                prefix + "/bundle.tgz",
                ExtraArgs={"ServerSideEncryption": "AES256"},
            )
            s3.upload_file(
                str(jar),
                bucket,
                prefix + "/api.jar",
                ExtraArgs={"ServerSideEncryption": "AES256"},
            )
        # Only QA data/token in task environment. Never copy provider credentials from the dev app.
        rc = copy.deepcopy(baseRunner)
        rc.update(
            entryPoint=["python", "-c"],
            command=[RUN, "load"],
            environment=envlist({**commonenv, "QA_JWT": qa_jwt}),
            cpu=0,
        )
        for k in ["memory", "memoryReservation", "healthCheck"]:
            rc.pop(k, None)
        rc["secrets"] = [x for x in rc.get("secrets", []) if x["name"] == "DB_PASSWORD"]
        runnerdef = register(runner, ident + "-runner", [rc], 8192, 16384)
        remote("create", runnerdef)
        state["created_schema"] = True
        save()
        init = dict(
            name="prepare",
            image=rc["image"],
            essential=False,
            entryPoint=["python", "-c"],
            command=[LOAD + DOWNLOAD_JAR],
            user="0",
            environment=envlist({**commonenv, "WORK_ROOT": "/mix"}),
            mountPoints=[dict(sourceVolume="mix", containerPath="/mix")],
            logConfiguration=rc["logConfiguration"],
        )
        app = copy.deepcopy(api["containerDefinitions"][0])
        app["image"] = rc["image"].split("@")[0] + "@" + digest
        app["logConfiguration"] = rc["logConfiguration"]
        aenv = {x["name"]: x["value"] for x in app["environment"]}
        url = aenv["DB_URL"]
        assert "/rougether_scale" in url and runnerEnv["DB_HOST"] in url
        aenv.update(
            DB_URL=url.replace("/rougether_scale", "/" + schema),
            JAVA_TOOL_OPTIONS="-Xmx2g",
            SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE="20",
            SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE="20",
            KAKAO_API_BASE_URL="http://127.0.0.1:18081",
            KAKAO_APP_ID="8427",
            GOOGLE_CERTS_URL="http://127.0.0.1:18081/google-keys",
            GOOGLE_ALLOWED_CLIENT_IDS="qa-google",
            AWS_ENDPOINT_URL_S3="http://127.0.0.1:18081",
            ASSET_S3_BUCKET="qa-assets",
            AWS_ACCESS_KEY_ID="qa-local-only",
            AWS_SECRET_ACCESS_KEY="qa-local-only",
            JWT_SECRET=qa_jwt,
            MANAGEMENT_SERVER_PORT="8081",
        )
        # Baseline diagnostic overrides can shadow explicit pool/feature properties.
        aenv.pop("SPRING_APPLICATION_JSON", None)
        if "SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT" in aenv:
            aenv["SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT"] = aenv.pop(
                "SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT"
            )

        for key in list(aenv):
            if "autocommit" in key.lower().replace("_", "").replace("-", ""):
                raise RuntimeError("conflicting environment: " + key)
        app.update(
            entryPoint=["java", "-jar", "/mix/app.jar"],
            command=[],
            environment=envlist(aenv),
            secrets=[x for x in app["secrets"] if x["name"] == "DB_PASSWORD"],
            dependsOn=[
                {"containerName": "prepare", "condition": "SUCCESS"},
            ],
            mountPoints=init["mountPoints"],
            cpu=0,
        )
        for k in ["memory", "memoryReservation", "healthCheck"]:
            app.pop(k, None)
        apidef = register(
            runner, ident + "-api", [init, app], 4096, 8192, [{"name": "mix"}]
        )
        state["service_changed"] = True
        save()
        ec.update_service(
            cluster=cluster, service=service, taskDefinition=apidef, desiredCount=2
        )
        say("deploying QA API")
        wait(lambda: stable(apidef), "API stable", 1200, 8)
        remote("seed", runnerdef)
        assert dbready("db.t4g.medium")
        remote("load", runnerdef, 2000)
        for name in ("result.json", "requests.json.gz"):
            (out / name).write_bytes(
                s3.get_object(Bucket=bucket, Key=prefix + "/" + name)["Body"].read()
            )
        state["measured"] = True
        save()
    finally:

        def clean(label, fn):
            try:
                fn()
                state[label] = True
            except Exception as e:
                state["cleanup_errors"].append([label, str(e)[:500]])
                say("cleanup issue", label, e)
            save()

        def stoptasks():
            for task in state["tasks"]:
                t = ec.describe_tasks(cluster=cluster, tasks=[task])["tasks"][0]
                if t["lastStatus"] != "STOPPED":
                    ec.stop_task(
                        cluster=cluster, task=task, reason="Clean up mobile mix test"
                    )
                    wait(
                        lambda: (
                            ec.describe_tasks(cluster=cluster, tasks=[task])["tasks"][
                                0
                            ]["lastStatus"]
                            == "STOPPED"
                        ),
                        "task stop",
                        300,
                        5,
                    )

        clean("stopped_tasks", stoptasks)
        if state["service_changed"]:

            def restore_service():
                ec.update_service(
                    cluster=cluster, service=service, taskDefinition=old, desiredCount=1
                )
                wait(lambda: stable(old), "original API stable", 1500, 8)

            clean("original_service", restore_service)
        if state["created_schema"] and runnerdef:
            clean("schema_removed", lambda: remote("drop", runnerdef))
        for arn in state["defs"]:
            clean(
                "reclaimed_" + arn.split("/")[-1],
                lambda a=arn: ec.deregister_task_definition(taskDefinition=a),
            )
        if bucket_touched:

            def erase():
                pages = s3.get_paginator("list_object_versions").paginate(
                    Bucket=bucket, Prefix=prefix + "/"
                )
                for page in pages:
                    keys = [
                        {"Key": o["Key"], "VersionId": o["VersionId"]}
                        for k in ("Versions", "DeleteMarkers")
                        for o in page.get(k, [])
                    ]
                    if keys:
                        result = s3.delete_objects(
                            Bucket=bucket, Delete={"Objects": keys}
                        )
                        if result.get("Errors"):
                            raise RuntimeError("S3 cleanup failed")

            clean("artifacts_removed", erase)
        state["finished"] = stamp()
        save()
        say("cleanup finished", state["cleanup_errors"])
        if state["cleanup_errors"]:
            raise RuntimeError("test resources require cleanup")
    assert state["measured"] and state["schema_removed"] and state["artifacts_removed"]
    assert stable(old) and dbready("db.t4g.medium")


if __name__ == "__main__":
    import argparse

    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--execute", action="store_true", required=True)
    cli.parse_args()
    main()
