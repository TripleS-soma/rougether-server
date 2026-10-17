FROM python:3.13-slim
LABEL com.rougether.role=ai-user-worker
WORKDIR /app
COPY ai-user-worker/worker.py /app/worker.py
COPY ai-service/rougether_ai/ai_user_policy.py /app/rougether_ai/ai_user_policy.py
USER 65534:65534
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
CMD ["python", "/app/worker.py"]
