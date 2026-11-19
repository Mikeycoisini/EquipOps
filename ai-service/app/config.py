"""服务配置；服务只绑定127.0.0.1，任何密钥不写入代码。"""

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    """运行参数，全部可由环境变量覆盖。"""

    max_chunks: int = 500
    top_k: int = 5
    min_score: float = 0.01


def load_settings() -> Settings:
    """读取环境变量；非法值回落到安全默认值。"""
    try:
        max_chunks = max(1, min(1000, int(os.getenv("AI_MAX_CHUNKS", "500"))))
    except ValueError:
        max_chunks = 500
    try:
        top_k = max(1, min(20, int(os.getenv("AI_TOP_K", "5"))))
    except ValueError:
        top_k = 5
    return Settings(max_chunks=max_chunks, top_k=top_k)


settings = load_settings()
