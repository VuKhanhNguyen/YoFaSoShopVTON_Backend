"""FastAPI HTTP thật ở mode mock, chỉ dùng bởi ActualAiIntegrationTest."""
import os
import re
import uvicorn
from api.application import create_app
from api.core.config import Settings

url = os.environ.get("DB_URL", "")
match = re.fullmatch(r"jdbc:mysql://127\.0\.0\.1:(\d+)/yofaso_test\?.+", url)
if not match:
    raise RuntimeError("Integration AI chỉ được dùng MySQL disposable của test_mysql.py")
settings = Settings(
    _env_file=None,
    app_env="test",
    ai_mode="mock",
    ai_internal_api_key=os.environ["TEST_AI_KEY"],
    runtime_dir=os.environ["TEST_AI_RUNTIME"],
    embedded_worker=True,
    rate_limit_per_minute=2000,
)
uvicorn.run(create_app(settings), host="127.0.0.1", port=int(os.environ["TEST_AI_PORT"]), log_level="warning")
