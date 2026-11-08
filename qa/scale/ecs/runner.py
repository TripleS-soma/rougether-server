"""기존 ECS k6 실행기에 MySQL QPS를 추가한다."""

from pathlib import Path

import baseline_k6_runner as baseline
import qps

# root_cause_runner도 같은 모듈에서 DB 진단 함수와 기본 실행기를 가져온다.
runner = baseline.runner
connection_rows = baseline.connection_rows
diagnostic_connection = baseline.diagnostic_connection
safe_digest = baseline.safe_digest


def snapshot(config):
    with baseline.diagnostic_connection(config) as connection:
        return qps.sample(lambda sql: baseline.connection_rows(connection, sql))


class QpsSession(baseline.DiagnosticSession):
    def __init__(self, config, directory, token):
        super().__init__(config, directory, token)
        self.before_load = snapshot(config)

    def stop(self):
        # k6 종료 직후, 큰 원시 응답 파일을 파싱하기 전에 카운터를 읽는다.
        try:
            result = qps.rate(self.before_load, snapshot(self.config))
            baseline.runner.write_json(self.directory / "db-qps.json", result)
        finally:
            super().stop()


def install(module=baseline.runner):
    baseline.DiagnosticSession = QpsSession
    state = baseline.install(module)
    instrumented_load = module.run_load

    def measured_load(config, directory, warmup=False, sampler=None):
        # 회차마다 새 디렉터리를 사용한다. 오래된 증거가 검증을 통과하지 않게 한다.
        artifact = Path(directory) / "db-qps.json"
        if not warmup and artifact.exists():
            raise RuntimeError("QPS 파일이 이미 있는 측정 경로")
        status = instrumented_load(config, directory, warmup=warmup, sampler=sampler)
        if not warmup and not artifact.is_file():
            raise RuntimeError("본부하 QPS 수집 실패")
        return status

    module.run_load = measured_load
    names = module.UPLOAD_ALLOWLIST
    module.UPLOAD_ALLOWLIST = tuple(
        name for name in names if name != "verdict.json"
    ) + ("db-qps.json", "verdict.json")
    return state


if __name__ == "__main__":
    install()
    raise SystemExit(baseline.runner.main())
