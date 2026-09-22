"""스레드 점유 기전 비교. ai-service/.venv/bin/python qa/ai-thread-isolation/run.py"""
import asyncio
import json
import math
import os
from pathlib import Path
import socket
import subprocess
import time

import httpx

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
OUTPUT = HERE / "build" / time.strftime("run-%Y%m%d-%H%M%S")
OUTPUT.mkdir(parents=True)
TOKEN = "isolation-test-token-12345678901234567890"
DURATION = 5
NORMAL_RPS = 20
AI_RPS = 8
MODES = ["sync-direct", "sync-remote-wide", "async-direct", "sync-direct-bounded", "sync-remote-two"]


def port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def percentile(values, p):
    values = sorted(values)
    return round(values[max(0, math.ceil(len(values) * p) - 1)], 2) if values else None


async def main():
    runtime = json.loads((HERE / "build/runtime.json").read_text())
    ports = [port() for _ in range(5)]
    assert len(set(ports)) == 5
    api, metrics, provider, remote_two, remote_wide = [f"http://127.0.0.1:{p}" for p in ports]
    env = {k: v for k, v in os.environ.items() if not k.startswith(("AI_", "LLM_"))}
    env.update(AI_SERVICE_TOKEN=TOKEN, AI_PROVIDER_API_KEY="probe-provider-key", AI_PROVIDER_BASE_URL=provider + "/v1",
               AI_CHAT_MODEL="probe-chat", AI_EMBEDDING_MODEL="probe-embedding", AI_EMBEDDING_DIMENSIONS="2",
               AI_MAX_RETRIES="0")
    processes, logs = [], []

    def start(name, command, cwd, settings):
        log = (OUTPUT / f"{name}.log").open("w")
        logs.append(log)
        process = subprocess.Popen(command, cwd=cwd, env=settings, stdout=log, stderr=subprocess.STDOUT)
        processes.append(process)

    python = str(ROOT / "ai-service/.venv/bin/python")
    try:
        start("provider", [python, "-m", "uvicorn", "provider:app", "--host", "127.0.0.1", "--port", str(ports[2]),
              "--no-access-log"], HERE, env)
        for name, target_port, cap in [("remote-two", ports[3], 2), ("remote-wide", ports[4], 16)]:
            start(name, [python, "-m", "uvicorn", "rougether_ai.app:create_app", "--factory", "--host", "127.0.0.1",
                  "--port", str(target_port), "--workers", "1", "--limit-concurrency", "32", "--no-access-log"],
                  ROOT / "ai-service", {**env, "AI_EMBEDDING_CONCURRENCY": str(cap)})
        start("java", [runtime["java"], "-XX:ActiveProcessorCount=2", "-Xmx256m", "-cp", runtime["classpath"],
              "qa.isolation.Probe", str(ports[0]), str(ports[1]), provider, remote_two, remote_wide], ROOT, env)
        async with httpx.AsyncClient(trust_env=False, timeout=2) as control:
            for url in [api + "/normal", metrics + "/metrics", provider + "/metrics",
                        remote_two + "/health/ready", remote_wide + "/health/ready"]:
                for _ in range(100):
                    if any(p.poll() is not None for p in processes):
                        raise RuntimeError("fixture process exited; inspect build logs")
                    try:
                        response = await control.get(url)
                        response.raise_for_status()
                        break
                    except (httpx.RequestError, httpx.HTTPStatusError):
                        await asyncio.sleep(0.1)
                else:
                    raise RuntimeError("fixture readiness timeout: " + url)
            # 모든 경로를 같은 빠른 공급자로 예열해 최초 연결/클래스 로딩 영향을 줄인다.
            for mode in MODES:
                for _ in range(3):
                    response = await control.get(api + "/ai/" + mode)
                    assert response.json()["embeddingApplied"] is True
            for _ in range(30):
                assert (await control.get(api + "/normal")).status_code == 200
            rows = []
            cases = [(0, "baseline", 0.02), (0, "sync-direct", 0.02)]
            cases += [(1, mode, 3.0) for mode in MODES]
            cases += [(2, mode, 3.0) for mode in reversed(MODES)]
            for index, (repeat, mode, delay) in enumerate(cases):
                (await control.post(provider + "/configure", json={"delay": delay})).raise_for_status()
                records, samples, peak_snapshot = [], [], {}
                done = asyncio.Event()
                limits = httpx.Limits(max_connections=200, max_keepalive_connections=200)
                async with httpx.AsyncClient(trust_env=False, timeout=25, limits=limits) as normal_client, \
                        httpx.AsyncClient(trust_env=False, timeout=25, limits=limits) as ai_client:
                    start_at = time.perf_counter() + 0.1

                    async def request(kind, number, rps, client, path):
                        scheduled = start_at + number / rps
                        await asyncio.sleep(max(0, scheduled - time.perf_counter()))
                        sent = time.perf_counter()
                        record = {"kind": kind, "id": number, "sendLagMs": (sent - scheduled) * 1000}
                        try:
                            response = await client.get(api + path)
                            record.update(status=response.status_code, body=response.json())
                        except (httpx.RequestError, ValueError) as error:
                            record.update(status=0, error=type(error).__name__)
                        completed = time.perf_counter()
                        record.update(latencyMs=(completed - sent) * 1000, arrivalToResponseMs=(completed - scheduled) * 1000)
                        records.append(record)

                    async def observe():
                        nonlocal peak_snapshot
                        while not done.is_set():
                            snapshot = (await control.get(metrics + "/metrics")).json()
                            if snapshot["tomcatBusy"] > peak_snapshot.get("tomcatBusy", -1):
                                peak_snapshot = snapshot
                            samples.append({k: v for k, v in snapshot.items() if k != "threads"})
                            await asyncio.sleep(0.2)

                    observer = asyncio.create_task(observe())
                    tasks = [request("normal", i, NORMAL_RPS, normal_client, "/normal") for i in range(DURATION * NORMAL_RPS)]
                    if mode != "baseline":
                        tasks += [request("ai", i, AI_RPS, ai_client, "/ai/" + mode) for i in range(DURATION * AI_RPS)]
                    try:
                        await asyncio.gather(*tasks)
                    finally:
                        done.set()
                        await observer
                    wall = time.perf_counter() - start_at
                upstream = (await control.get(provider + "/metrics")).json()
                normal = [r for r in records if r["kind"] == "normal"]
                ai = [r for r in records if r["kind"] == "ai"]
                ai_ok = sum(r.get("body", {}).get("embeddingApplied") is True for r in ai)
                summary = {"repeat": repeat, "mode": mode, "providerDelaySeconds": delay,
                    "normalScheduled": DURATION * NORMAL_RPS, "normalSent": len(normal),
                    "normalHttp200": sum(r["status"] == 200 for r in normal),
                    "normalP50Ms": percentile([r["arrivalToResponseMs"] for r in normal], 0.5),
                    "normalP95Ms": percentile([r["arrivalToResponseMs"] for r in normal], 0.95),
                    "normalMaxMs": round(max(r["arrivalToResponseMs"] for r in normal), 2),
                    "aiScheduled": 0 if mode == "baseline" else DURATION * AI_RPS, "aiSent": len(ai),
                    "aiApplied": ai_ok, "aiFallback": sum(r.get("body", {}).get("embeddingApplied") is False for r in ai),
                    "transportOrHttpErrors": sum(r["status"] != 200 for r in records),
                    "sendLagMaxMs": round(max(r["sendLagMs"] for r in records), 2),
                    "tomcatBusyMax": max(s["tomcatBusy"] for s in samples),
                    "aiActiveMax": max(s["aiActive"] for s in samples),
                    "providerPeak": upstream["peak"], "providerStarted": upstream["started"],
                    "providerCompleted": upstream["completed"], "wallSeconds": round(wall, 2)}
                assert upstream["active"] == 0 and upstream["started"] == upstream["completed"] == ai_ok
                assert summary["normalSent"] == summary["normalScheduled"] and summary["aiSent"] == summary["aiScheduled"]
                assert summary["transportOrHttpErrors"] == 0
                rows.append(summary)
                (OUTPUT / f"{index:02d}-{mode}.json").write_text(json.dumps(
                    {"summary": summary, "requests": records, "samples": samples, "peakThreadSnapshot": peak_snapshot}, indent=2))
                (OUTPUT / "summary.json").write_text(json.dumps(rows, indent=2))
                print(json.dumps(summary), flush=True)
            print("RESULT_DIR=" + str(OUTPUT), flush=True)
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
        for process in processes:
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        for log in logs:
            log.close()


asyncio.run(main())
