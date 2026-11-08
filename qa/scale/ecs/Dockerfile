ARG BASE
FROM ${BASE} AS baseline
FROM ${BASE}
COPY --from=baseline /opt/rougether/qa/ecs-scale/diagnostics/instrumented_runner.py /opt/rougether/qa/ecs-scale/diagnostics/baseline_k6_runner.py
COPY ecs/runner.py /opt/rougether/qa/ecs-scale/diagnostics/instrumented_runner.py
COPY qps.py /opt/rougether/qa/ecs-scale/diagnostics/
COPY qps.py /opt/rougether/qa/scale/
ENTRYPOINT ["python", "/opt/rougether/qa/ecs-scale/diagnostics/instrumented_runner.py"]
