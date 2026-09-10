#!/usr/bin/env python3
"""자기 compose project만 소유하는, 종료 시간이 제한된 로컬 부하 실행기."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import time
from datetime import datetime
from zoneinfo import ZoneInfo

from telemetry import Sampler, api_json, database_snapshot, endpoint_counts, utc_now
import index_experiment

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
COMPOSE = HERE / "compose.yml"


def command(arguments, **kwargs):
    return subprocess.check_output(arguments, text=True, **kwargs)


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def kst_date():
    return datetime.now(ZoneInfo("Asia/Seoul")).date().isoformat()


def check_port_available(port):
    # 종료된 연결의 TIME_WAIT는 허용하되 실제 listener와의 충돌은 거부한다.
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        probe.bind(("127.0.0.1", port))
        probe.listen(1)


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scenario", choices=["read", "mixed", "write", "contention"])
    parser.add_argument("--rate", type=int, default=100)
    parser.add_argument("--duration", type=int, default=10)
    parser.add_argument("--users", type=int, default=1000)
    parser.add_argument("--todos", type=int)
    parser.add_argument("--vus", type=int, default=200)
    parser.add_argument("--port", type=int, default=19080)
    parser.add_argument("--warmup", type=int, default=10)
    parser.add_argument("--history-per-user", type=int, default=0,
                        help="인덱스 실험용 과거 무보상 완료 이력; mixed만 지원")
    parser.add_argument("--todo-indexes", choices=["baseline", "candidate"], default="baseline")
    parser.add_argument("--warm-todo-indexes", action="store_true",
                        help="별도 대조 조건: 부하 전 todos의 모든 인덱스를 한 번 순차 읽기")
    parser.add_argument("--skip-build", action="store_true", help="이미 빌드된 JAR 사용; hash를 기록함")
    parser.add_argument("--jar", type=Path, help="전후 비교용 보관 JAR; --skip-build와 함께 사용")
    args = parser.parse_args()
    if args.jar is not None and (not args.skip_build or not args.jar.is_file()):
        parser.error("--jar는 존재하는 파일과 --skip-build가 필요함")
    if not (1 <= args.rate <= 100000 and 1 <= args.duration <= 7200
            and 1 <= args.users <= 1000000 and 1 <= args.vus <= 10000
            and 1024 <= args.port <= 65535 and 0 <= args.warmup <= 300):
        parser.error("rate/duration/users/vus/port/warmup 범위를 벗어남")
    writes = {"read": 0, "mixed": args.rate * args.duration // 5,
              "write": args.rate * args.duration,
              "contention": math.ceil(args.rate * args.duration / 5)}[args.scenario]
    if args.todos is None:
        args.todos = max(args.users * 10, writes)
    if args.todos < max(writes, 1) or args.todos > 100000000:
        parser.error("todo fixture 수가 부족하거나 1억 건 상한을 넘음")
    if not (0 <= args.history_per_user <= 1000 and args.history_per_user * args.users <= 20000000):
        parser.error("history-per-user 0~1000, 과거 이력 총 2000만 건 이내여야 함")
    if (args.history_per_user or args.todo_indexes != "baseline" or args.warm_todo_indexes) and args.scenario != "mixed":
        parser.error("누적 이력 인덱스 실험은 mixed만 지원함")
    return args


def main():
    args = arguments()
    for executable in ("docker", "k6", "git", "ps"):
        if not shutil.which(executable):
            raise RuntimeError(f"필수 도구 없음: {executable}")
    docker_host = os.environ.get("DOCKER_HOST") or command([
        "docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"]).strip()
    if not docker_host.startswith("unix://"):
        raise RuntimeError("로컬 Unix socket Docker context만 허용함")
    free_bytes = shutil.disk_usage(HERE).free
    estimated_bytes = ((args.todos + args.users * args.history_per_user) * 1500 + args.users * 5000) * 2
    if free_bytes < estimated_bytes + 5 * 1024**3:
        raise RuntimeError("합성 데이터·인덱스·binlog·SQL 임시 파일을 위한 디스크 여유 부족")
    # 포트 충돌 시 타 프로세스를 종료하지 않고 실행 자체를 거부함.
    check_port_available(args.port)
    run_id = datetime.now().strftime("%Y%m%dT%H%M%S") + "-" + secrets.token_hex(3)
    directory = HERE / "results" / (run_id + "-" + args.scenario)
    directory.mkdir(parents=True, mode=0o700)
    project = "rougether-scale-" + run_id.lower()
    env = os.environ.copy()
    env.update({"SCALE_API_PORT": str(args.port), "SCALE_RESULT_DIR": str(directory),
                "SCALE_JWT_SECRET": secrets.token_hex(32)})
    manifest = {"schema_version": 1, "run_id": run_id, "scenario": args.scenario,
                "project": project, "rate": args.rate, "duration_seconds": args.duration,
                "expected_requests": args.rate * args.duration, "users": args.users,
                "todos": args.todos, "preallocated_vus": args.vus, "max_vus": args.vus,
                "seed_date": kst_date(), "created_at": utc_now(), "source_sha":
                command(["git", "rev-parse", "HEAD"], cwd=ROOT).strip(),
                "source_diff_sha256": hashlib.sha256(command(
                    ["git", "diff", "HEAD"], cwd=ROOT).encode()).hexdigest(),
                "k6_version": command(["k6", "version"]).strip(),
                "host_cpu_count": os.cpu_count(), "warmup_seconds": args.warmup,
                "cache_state": "재기동 후 지정 시간 동안 읽기 warmup; 전체 키 warm 보장 없음",
                "disk_free_bytes_before": free_bytes,
                "environment": "single-host Docker Desktop; loopback generator",
                "fixture_profile": "minimal users/wallets/personal_rooms/todos; no routines/onboarding/history",
                "limits": {key: env.get(key, default) for key, default in {
                    "SCALE_API_CPUS": "2", "SCALE_API_MEMORY": "2g", "SCALE_API_HEAP": "1024m",
                    "SCALE_DB_CPUS": "2", "SCALE_DB_MEMORY": "2g", "SCALE_DB_POOL": "10",
                    "SCALE_DB_BUFFER_BYTES": "1073741824"}.items()},
                "measurement_limits": ["단일 물리 호스트의 합성 부하",
                    "외부 OAuth/AI 및 실제 사용자 트래픽 제외",
                    "JFR profile과 약 3~8초마다 관리 지표 5개 조회의 관측 비용 포함"]}
    for path in HERE.rglob("*"):
        if path.is_file() and "results" not in path.parts and "__pycache__" not in path.parts:
            manifest.setdefault("harness_sha256", {})[str(path.relative_to(HERE))] = hashlib.sha256(path.read_bytes()).hexdigest()
    write_json(directory / "manifest.json", manifest)
    compose_started = False
    load_process = None
    sampler = None

    def compose(*parts, timeout=90, input_text=None, stdin=None):
        stream_args = {"stdin": stdin} if stdin is not None else ({"input": input_text} if input_text is not None else {})
        return command(["docker", "compose", "--project-name", project,
                        "--env-file", "/dev/null", "--file", str(COMPOSE), *parts], env=env, timeout=timeout,
                       stderr=subprocess.STDOUT, **stream_args)

    def mysql(sql):
        if isinstance(sql, Path):
            with sql.open() as stream:
                return compose("exec", "-T", "-e", "MYSQL_PWD=scale-root", "mysql", "mysql",
                               "-uroot", "--batch", "--raw", "--skip-column-names", "rougether_scale",
                               stdin=stream, timeout=600)
        return compose("exec", "-T", "-e", "MYSQL_PWD=scale-root", "mysql", "mysql",
                       "-uroot", "--batch", "--raw", "--skip-column-names", "rougether_scale", input_text=sql, timeout=120)

    def interrupt(signum, _frame):
        raise KeyboardInterrupt(f"signal {signum}")

    signal.signal(signal.SIGTERM, interrupt)
    signal.signal(signal.SIGINT, interrupt)
    try:
        if not args.skip_build:
            with (directory / "build.log").open("w") as stream:
                subprocess.run([str(ROOT / "gradlew"), "--no-daemon", ":user-api:bootJar"],
                               cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT,
                               timeout=600, check=True)
        jars = [args.jar.resolve()] if args.jar else [
            jar for jar in (ROOT / "user-api/build/libs").glob("*.jar")
            if not jar.name.endswith("-plain.jar")]
        if len(jars) != 1:
            raise RuntimeError("bootJar 한 개가 필요함")
        env["SCALE_JAR"] = str(jars[0])
        manifest["jar_path"] = str(jars[0])
        manifest["jar_sha256"] = hashlib.sha256(jars[0].read_bytes()).hexdigest()
        manifest["docker"] = json.loads(command(["docker", "info", "--format",
            '{"cpus":{{.NCPU}},"memory_bytes":{{.MemTotal}},"architecture":"{{.Architecture}}",'
            '"version":"{{.ServerVersion}}"}']))
        print(f"run={directory}\nproject={project}", flush=True)
        compose_started = True
        (directory / "startup.log").write_text(compose("up", "--detach", "--wait", timeout=180))
        manifest["containers"] = {}
        for service in ("api", "mysql"):
            container_id = compose("ps", "--all", "-q", service).strip()
            details = json.loads(command(["docker", "inspect", "--format",
                '{"image_id":"{{.Image}}","memory_bytes":{{.HostConfig.Memory}},'
                '"nano_cpus":{{.HostConfig.NanoCpus}}}', container_id]))
            details["image"] = json.loads(command(["docker", "image", "inspect", "--format",
                '{"architecture":"{{.Architecture}}","os":"{{.Os}}",'
                '"repo_digests":{{json .RepoDigests}}}', details["image_id"]]))
            manifest["containers"][service] = details
        base_url = f"http://127.0.0.1:{args.port}"
        api_id = compose("ps", "--all", "-q", "api").strip()
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            try:
                api_json(base_url, "/api/v1/health")
                break
            except Exception:
                state = command(["docker", "inspect", "--format", "{{.State.Status}}", api_id]).strip()
                if state in ("exited", "dead"):
                    raise RuntimeError("API 컨테이너가 준비되기 전에 종료됨; api.log 확인")
                time.sleep(1)
        else:
            raise RuntimeError("API가 120초 이내 준비되지 않음")
        # fixture 준비와 DB 최종 검사는 seed/의 공개 CLI를 사용함.
        manifest["seed_date"] = kst_date()
        prepare_fixture(args, directory, env, mysql, manifest)
        fixture = json.loads((directory / "fixtures.json").read_text())
        if args.history_per_user or args.todo_indexes != "baseline" or args.warm_todo_indexes:
            index_experiment.prepare(directory, mysql, fixture, args.history_per_user,
                                     args.todo_indexes, manifest, args.warm_todo_indexes)
        token = fixture["users"][0]["token"]
        me = api_json(base_url, "/api/v1/me", token)
        if me.get("userId") != fixture["users"][0]["id"]:
            raise RuntimeError("실제 JWT 인증/사용자 응답 불일치")
        # 로그인 우회나 공개 endpoint로 잘못 측정하지 않도록 무인증 요청도 확인함.
        import urllib.error
        try:
            api_json(base_url, "/api/v1/me")
            raise RuntimeError("무인증 회원 조회가 허용됨")
        except urllib.error.HTTPError as error:
            if error.code != 401:
                raise

        def k6_command(scenario, seconds, rate, output):
            return ["k6", "run", "--quiet", "--env", f"BASE_URL={base_url}",
                    "--env", f"RATE={rate}", "--env", f"DURATION_SECONDS={seconds}",
                    "--env", f"PREALLOCATED_VUS={args.vus}", "--env", f"MAX_VUS={args.vus}",
                    "--env", f"FIXTURE_PATH={directory / 'fixtures.json'}",
                    "--env", f"SUMMARY_PATH={directory / output}",
                    str(HERE / "scenarios" / (scenario + ".js"))]
        if args.warmup:
            with (directory / "warmup.log").open("w") as stream:
                warmup = subprocess.run(k6_command("read", args.warmup, min(args.rate, 100),
                                                   "warmup-summary.json"),
                                        stdout=stream, stderr=subprocess.STDOUT,
                                        timeout=args.warmup + 45)
                manifest["warmup_exit_code"] = warmup.returncode
        if kst_date() != manifest["seed_date"]:
            raise RuntimeError("seed 후 KST 날짜가 바뀌었음; 새 회차 필요")
        database_snapshot(mysql, directory / "db-before.json")
        manifest["server_requests_before"] = endpoint_counts(base_url, token)
        for name in ("http.server.requests", "hikaricp.connections.acquire", "hikaricp.connections.usage"):
            write_json(directory / (name + "-before.json"),
                       api_json(base_url, "/actuator/metrics/" + name, token))
        manifest["started_at"] = utc_now()
        write_json(directory / "manifest.json", manifest)
        print(f"load: {args.scenario} {args.rate} RPS × {args.duration}s", flush=True)
        with (directory / "console.txt").open("w") as stream:
            load_process = subprocess.Popen(k6_command(args.scenario, args.duration, args.rate,
                                                       "summary.json"), stdout=stream,
                                            stderr=subprocess.STDOUT)
            sampler = Sampler(compose, base_url, token, directory)
            sampler.start(load_process)
            manifest["k6_exit_code"] = load_process.wait(timeout=args.duration + 60)
            sampler.stop()
        manifest["completed_at"] = utc_now()
        manifest["finished_date"] = kst_date()
        manifest["server_requests_after"] = endpoint_counts(base_url, token)
        manifest["server_arrivals"] = {key: value - manifest["server_requests_before"][key]
                                       for key, value in manifest["server_requests_after"].items()}
        database_snapshot(mysql, directory / "db-after.json")
        for name in ("http.server.requests", "hikaricp.connections.acquire", "hikaricp.connections.usage"):
            write_json(directory / (name + "-after.json"),
                       api_json(base_url, "/actuator/metrics/" + name, token))
        audit_fixture(directory, env, mysql, manifest)
    except BaseException as error:
        manifest["execution_error"] = str(error)
        print(f"실행 실패: {error}", file=sys.stderr, flush=True)
    finally:
        if load_process and load_process.poll() is None:
            load_process.terminate()
            try:
                load_process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                load_process.kill()
                load_process.wait()
        if sampler:
            sampler.stop()
        if compose_started:
            cleanup_errors = []
            try:
                compose("stop", "api", timeout=45)
            except subprocess.SubprocessError as error:
                cleanup_errors.append(str(error))
            # API 관측이 실패해도 DB가 남아 있을 때 최종 상태를 수집함.
            if (directory / "summary.json").exists() and (directory / "fixtures.json").exists():
                try:
                    if not (directory / "db-after.json").exists():
                        database_snapshot(mysql, directory / "db-after.json")
                    if not (directory / "db-audit.json").exists():
                        audit_fixture(directory, env, mysql, manifest)
                except Exception as error:
                    manifest["audit_error"] = str(error)
                if "history" in manifest:
                    try:
                        index_experiment.finish(directory, mysql, manifest)
                    except Exception as error:
                        manifest["executed_audit_error"] = str(error)
            try:
                (directory / "api.log").write_text(compose("logs", "--no-color", "api"))
            except subprocess.SubprocessError as error:
                manifest["api_log_error"] = str(error)
            try:
                compose("down", "--volumes", "--remove-orphans", timeout=60)
            except subprocess.SubprocessError as error:
                cleanup_errors.append(str(error))
            manifest["cleanup_completed"] = not cleanup_errors
            if cleanup_errors:
                manifest["cleanup_error"] = cleanup_errors
        else:
            manifest["cleanup_completed"] = True
        manifest["finished_at"] = utc_now()
        write_json(directory / "manifest.json", manifest)
    verifier = subprocess.run([sys.executable, str(HERE / "verify-results.py"),
                               "--run-dir", str(directory)])
    print(f"evidence: {directory}", flush=True)
    return verifier.returncode


def prepare_fixture(args, directory, env, mysql, manifest):
    started = time.monotonic()
    subprocess.run([sys.executable, str(HERE / "seed/prepare.py"),
                    "--users", str(args.users), "--todo-count", str(args.todos),
                    "--date", manifest["seed_date"], "--output", str(directory),
                    "--jwt-secret", env["SCALE_JWT_SECRET"]], check=True, timeout=300)
    fixture_path = directory / "fixtures.json"
    fixture = json.loads(fixture_path.read_text())
    if "users" not in fixture:
        fixture["users"] = json.loads((directory / "tokens.json").read_text())["users"]
        write_json(fixture_path, fixture)
    manifest["todos"] = fixture["todoCount"]
    manifest["seed_sql_bytes"] = (directory / "seed.sql").stat().st_size
    # MySQL CLI에 파일을 직접 전달해 대량 SQL을 Python 메모리에 복제하지 않음.
    (directory / "seed-result.txt").write_text(mysql(directory / "seed.sql"))
    manifest["seed_seconds"] = time.monotonic() - started


def audit_fixture(directory, env, mysql, manifest):
    arguments = [sys.executable, str(HERE / "seed/audit.py"),
                 "--fixture", str(directory / "fixtures.json"),
                 "--summary", str(directory / "summary.json")]
    sql = command([*arguments, "--emit-snapshot-sql"])
    (directory / "audit-snapshot.tsv").write_text(mysql(sql))
    process = subprocess.run([*arguments, "--snapshot", str(directory / "audit-snapshot.tsv"),
                              "--out", str(directory / "db-audit.json")])
    manifest["audit_exit_code"] = process.returncode


if __name__ == "__main__":
    sys.exit(main())
