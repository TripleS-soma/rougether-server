"""이미지 빌드 때 공개 모델 파일만 준비함. 사용자 텍스트는 읽거나 전송하지 않음."""
import os

os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
os.environ["HF_HUB_DISABLE_IMPLICIT_TOKEN"] = "1"

import onnxruntime
from fastembed import TextEmbedding

onnxruntime.disable_telemetry_events()
TextEmbedding(model_name="sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2",
              cache_dir=os.environ.get("AI_USER_EMBEDDING_CACHE", "/opt/ai-models"), threads=1)
