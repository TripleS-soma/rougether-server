import argparse
import base64
import datetime
import hashlib
import json
import os
import secrets
import subprocess as sp
import tempfile
import time
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--jars", required=True, type=Path)
parser.add_argument("--work-dir", required=True, type=Path)
parser.add_argument("--host-probe", action="store_true")
args = parser.parse_args()
runtime = Path(__file__).resolve().parent
jars = args.jars.resolve()
root = args.work_dir.resolve()
receipt = {}
project = "rougether-private-api-" + secrets.token_hex(4)


def run(args, *, data=None, allow=False):
    p = sp.run(args, input=data, text=True, stdout=sp.PIPE, stderr=sp.PIPE)
    if p.returncode and not allow:
        raise RuntimeError("failed: " + str(args[:3]) + " " + p.stderr[-400:])
    return p


with tempfile.TemporaryDirectory(dir=root) as temp:
    d = Path(temp)
    conf = d / "config"
    os.environ.update(RUNTIME_JARS=str(jars), RUNTIME_CONFIG=str(conf))
    lines = [
        "2d0f2b7b33cd00ad7201ae2a7504f9c94c363374e915a27f900bbeeb0a92677b  admin-api.jar",
        "d0cddb42c2d814425c3830358f1014e5548d89054fc25fcfc139307856313024  batch.jar",
        "8ae9387d6b4c8bb0741a058170c43c15518db6c00116c03fc77d4dfb91f094a2  user-api.jar",
    ]
    for line in lines:
        sha, name = line.split()
        digest = hashlib.sha256()
        with (jars / name).open("rb") as payload:
            for block in iter(lambda: payload.read(1048576), b""):
                digest.update(block)
        assert digest.hexdigest() == sha
    receipt["artifactChecksums"] = dict(reversed(line.split()) for line in lines)
    os.umask(0o077)
    rootpw = secrets.token_hex(30)
    apppw = secrets.token_hex(30)
    (d / "root-password").write_text(rootpw)
    (d / "root-password").chmod(0o444)
    common = {
        "DB_URL": "jdbc:mysql://mysql:3306/rougether?serverTimezone=UTC&allowPublicKeyRetrieval=true&sslMode=DISABLED",
        "DB_USERNAME": "app",
        "DB_PASSWORD": apppw,
    }
    for service in ["user-api", "admin-api", "batch"]:
        folder = conf / service
        folder.mkdir(parents=True)
        vals = dict(common)
        if service == "user-api":
            vals.update(
                JWT_SECRET=secrets.token_hex(40),
                APPLE_REFRESH_TOKEN_ENC_KEY=base64.b64encode(
                    secrets.token_bytes(32)
                ).decode(),
            )
        if service == "admin-api":
            vals["ADMIN_ORIGIN_SECRET"] = secrets.token_hex(32)
        for k, v in vals.items():
            (folder / k).write_text(v)
    ext = d / "mysql.yaml"
    ext.write_text(f"""services:
  mysql:
    image: mysql@sha256:8c233bad33d520a7aae1c1e619c3a9775f4a1ba2d463d692fada04ec7f40be00
    command: [--event-scheduler=DISABLED, --innodb-buffer-pool-size=128M]
    mem_limit: 768m
    environment:
      MYSQL_DATABASE: rougether
      MYSQL_ROOT_PASSWORD_FILE: /run/secrets/test-password
    volumes:
      - {d}/root-password:/run/secrets/test-password:ro
""")
    compose = [
        "docker",
        "compose",
        "-p",
        project,
        "-f",
        str(runtime / "compose.apps.yaml"),
        "-f",
        str(runtime / "compose.standby.yaml"),
        "-f",
        str(ext),
    ]
    mysql = project + "-mysql-1"

    def sql(query, allow=False):
        return run(
            [
                "docker",
                "exec",
                "-i",
                mysql,
                "sh",
                "-c",
                'export MYSQL_PWD="$(cat /run/secrets/test-password)"; exec mysql --protocol=socket -uroot -N -B rougether',
            ],
            data=query,
            allow=allow,
        )

    def health(service, path, port, expected):
        probe = """import http.client,sys,time,json
for i in range(150):
 try:
  c=http.client.HTTPConnection('127.0.0.1',int(sys.argv[1]),timeout=3)
  c.request('GET',sys.argv[2]);r=c.getresponse();body=r.read();c.close()
  if str(r.status) in sys.argv[3:] and json.loads(body)['status']=='ok': print(r.status);sys.exit(0)
 except (OSError,ValueError,KeyError): pass
 time.sleep(1)
sys.exit(1)
"""
        container = project + "-" + service + "-1"
        if args.host_probe:
            pid = run(
                ["docker", "inspect", "-f", "{{.State.Pid}}", container]
            ).stdout.strip()
            assert pid.isdigit() and int(pid) > 1
            launcher = ["nsenter", "-t", pid, "-n", "python3"]
        else:
            launcher = [
                "docker",
                "run",
                "--rm",
                "--network",
                "container:" + container,
                "python:3.13-slim",
                "python",
            ]
        out = run(launcher + ["-c", probe, str(port), path, *expected], allow=True)
        if out.returncode:
            raise RuntimeError(f"{service} {path} failed health check")
        return int(out.stdout.strip())

    try:
        run(compose + ["up", "-d", "mysql"])
        for i in range(100):
            if (
                sql("SELECT 1", allow=True).stdout.strip() == "1"
                and run(["docker", "exec", mysql, "cat", "/proc/1/comm"]).stdout.strip()
                == "mysqld"
            ):
                break
            time.sleep(1)
        sql(
            f"CREATE USER 'app'@'%' IDENTIFIED BY '{apppw}'; GRANT ALL ON rougether.* TO 'app'@'%';"
        )
        # Migrate only this synthetic database with the same artifact that handles user traffic.
        migration = d / "schema.yaml"
        migration.write_text(
            "services:\n  user-api:\n    environment:\n      SPRING_FLYWAY_ENABLED: 'true'\n"
        )
        run(compose + ["-f", str(migration), "up", "-d", "user-api"])
        health("user-api", "/api/v1/health", 8080, {"200"})
        run(compose + ["stop", "user-api"])
        sql(
            "REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'app'@'%'; GRANT SELECT, SHOW VIEW ON rougether.* TO 'app'@'%'; SET PERSIST super_read_only=ON;"
        )
        run(compose + ["restart", "mysql"])
        for _ in range(90):
            if sql("SELECT @@global.super_read_only", allow=True).stdout.strip() == "1":
                break
            time.sleep(1)
        assert (
            sql(
                "SELECT @@global.super_read_only AND EXISTS (SELECT 1 FROM performance_schema.persisted_variables WHERE VARIABLE_NAME='super_read_only' AND VARIABLE_VALUE='ON')"
            ).stdout.strip()
            == "1"
        )
        receipt["readOnlyAfterDatabaseRestart"] = True
        denied = sql("CREATE TABLE migration_write_probe(id BIGINT)", allow=True)
        assert denied.returncode and "1290" in denied.stderr
        receipt["writeRejectedAfterRestart"] = True
        run(
            compose
            + ["up", "-d", "--force-recreate", "--no-deps", "user-api", "admin-api"]
        )
        receipt["userHealth"] = health("user-api", "/api/v1/health", 8080, {"200"})
        receipt["adminHealth"] = health("admin-api", "/admin/health", 8081, {"200"})
        receipt["mysqlSuperReadOnly"] = (
            sql("SELECT @@global.super_read_only").stdout.strip() == "1"
        )
        assert receipt["mysqlSuperReadOnly"]
        receipt["flywayLatest"] = sql(
            "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1"
        ).stdout.strip()
        assert receipt["flywayLatest"] == "109"
        assert (
            run(
                [
                    "docker",
                    "network",
                    "inspect",
                    "-f",
                    "{{.Internal}}",
                    project + "_default",
                ]
            ).stdout.strip()
            == "true"
        )
        for service in ["user-api", "admin-api", "mysql"]:
            s = json.loads(
                run(["docker", "inspect", project + "-" + service + "-1"]).stdout
            )[0]
            assert not s["HostConfig"]["PortBindings"] and set(
                s["NetworkSettings"]["Networks"]
            ) == {project + "_default"}
        assert (
            "batch"
            not in run(
                compose + ["ps", "--services", "--status", "running"]
            ).stdout.splitlines()
        )
        receipt.update(
            at=datetime.datetime.now().astimezone().isoformat(),
            synthetic=True,
            networkInternal=True,
            hostPorts=0,
            batchRunning=False,
        )
    finally:
        run(compose + ["down", "--volumes", "--remove-orphans"])
receipt["cleanup"] = True
print(json.dumps(receipt))
