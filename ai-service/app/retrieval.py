"""词面检索：中文双字词与英文单词命中，不接模型、不跨企业。"""

import re
from dataclasses import dataclass

TOKEN = re.compile(r"[A-Za-z0-9_]+|[\u4e00-\u9fff]{1,2}")


@dataclass(frozen=True)
class Chunk:
    """检索输入片段。"""

    chunk_id: str
    version_id: str
    section: str
    text: str


@dataclass(frozen=True)
class Hit:
    """命中片段及分数。"""

    chunk: Chunk
    score: float


def tokens(text: str) -> list[str]:
    """提取检索词，保持顺序并去重。"""
    seen: set[str] = set()
    result: list[str] = []
    for value in TOKEN.findall((text or "").lower()):
        if value not in seen:
            seen.add(value)
            result.append(value)
    return result


def score(query: str, chunk: Chunk) -> float:
    """命中词数除以查询词数，章节命中给予少量加权。"""
    query_tokens = tokens(query)
    if not query_tokens:
        return 0.0
    body = (chunk.section or "") + "\n" + (chunk.text or "")
    matched = sum(1 for token in query_tokens if token in body.lower())
    if matched == 0:
        return 0.0
    section_bonus = 0.05 if any(token in (chunk.section or "").lower() for token in query_tokens) else 0.0
    return matched / len(query_tokens) + section_bonus


def search(question: str, chunks: list[Chunk], top_k: int, min_score: float = 0.01) -> list[Hit]:
    """返回按分数倒序的命中；没有命中返回空列表。"""
    hits = [Hit(chunk=chunk, score=score(question, chunk)) for chunk in chunks]
    hits = [hit for hit in hits if hit.score >= min_score]
    hits.sort(key=lambda hit: (-hit.score, hit.chunk.chunk_id))
    return hits[:top_k]
