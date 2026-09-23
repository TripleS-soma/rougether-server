FROM python:3.12-slim
RUN pip install --no-cache-dir uv==0.10.12
WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project
COPY rougether_ai ./rougether_ai
RUN useradd --uid 10001 --no-create-home app
USER 10001
ENV PATH="/app/.venv/bin:$PATH" PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
EXPOSE 8090
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8090/health/ready', timeout=2)"
CMD ["uvicorn", "rougether_ai.app:create_app", "--factory", "--host", "0.0.0.0", "--port", "8090", "--workers", "1", "--limit-concurrency", "32", "--no-access-log", "--timeout-graceful-shutdown", "100"]
