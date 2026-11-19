"""词面检索命中、空结果与排序。"""

from app.retrieval import Chunk, search


def chunk(chunk_id: str, text: str, section: str = "") -> Chunk:
    return Chunk(chunk_id=chunk_id, version_id="1", section=section, text=text)


def test_keyword_hit_ranks_first():
    hits = search("轴承 更换", [
        chunk("1", "检查滤芯"),
        chunk("2", "轴承更换步骤", "维修"),
    ], top_k=5)
    assert [hit.chunk.chunk_id for hit in hits] == ["2"]


def test_no_hit_returns_empty():
    assert search("液压油", [chunk("1", "轴承更换步骤")], top_k=5) == []


def test_only_returns_top_k():
    hits = search("轴承", [chunk(str(index), "轴承") for index in range(10)], top_k=3)
    assert len(hits) == 3
