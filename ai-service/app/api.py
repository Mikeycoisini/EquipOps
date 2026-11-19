"""内部任务接口：Java下发片段快照，Python只回chunkId与建议文本。"""

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, ConfigDict, Field

from app.config import settings
from app.retrieval import Chunk, search

router = APIRouter(prefix="/internal/ai")


class ChunkIn(BaseModel):
    """Java发送的片段。"""

    model_config = ConfigDict(extra="forbid")

    chunkId: str = Field(min_length=1, max_length=64)
    section: str = Field(default="", max_length=500)
    text: str = Field(min_length=1, max_length=4000)


class DocumentIn(BaseModel):
    """按版本分组的片段。"""

    model_config = ConfigDict(extra="forbid")

    versionId: str = Field(min_length=1, max_length=64)
    chunks: list[ChunkIn] = Field(min_length=1, max_length=500)


class TaskIn(BaseModel):
    """任务请求；身份与工具凭证由Java签发。"""

    model_config = ConfigDict(extra="forbid")

    taskId: str = Field(min_length=1, max_length=64)
    tenantId: str = Field(min_length=1, max_length=64)
    question: str = Field(min_length=1, max_length=2000)
    documents: list[DocumentIn] = Field(min_length=1, max_length=20)
    toolToken: str | None = Field(default=None, max_length=8192)
    tools: list[str] = Field(default_factory=list, max_length=20)


class CitationOut(BaseModel):
    """只回传可核对的chunkId。"""

    chunkId: str
    score: float


class TaskOut(BaseModel):
    """四分区中的三部分；businessFacts由后续编排填充。"""

    state: str
    answer: str
    citations: list[CitationOut] = Field(default_factory=list)
    businessFacts: list[dict] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)


@router.get("/health")
def health() -> dict:
    """探活不读取任何外部资源。"""
    return {"status": "ok", "version": "a9-rebuild"}


@router.post("/tasks", response_model=TaskOut)
def task(request: TaskIn) -> TaskOut:
    """执行流程 1限制总量 2展平片段 3词面检索 4无命中拒答 5组装引用。"""
    # 防止任务请求无限放大。
    chunks: list[Chunk] = []
    for document in request.documents:
        for chunk in document.chunks:
            if len(chunks) >= settings.max_chunks:
                break
            chunks.append(Chunk(chunk_id=chunk.chunkId, version_id=document.versionId,
                                section=chunk.section, text=chunk.text))
    # 没有可检索片段时明确拒答。
    if not chunks:
        return TaskOut(state="INSUFFICIENT_EVIDENCE", answer="")
    # 词面检索只在本任务下发的片段内进行。
    hits = search(request.question, chunks, settings.top_k, settings.min_score)
    # 无命中不编造结论。
    if not hits:
        return TaskOut(state="INSUFFICIENT_EVIDENCE", answer="")
    # 固定句式拼接命中片段，明确这是资料摘录。
    snippets = []
    for hit in hits[:3]:
        text = hit.chunk.text.strip().replace("\n", " ")
        snippets.append(text[:200])
    answer = "根据已选资料：" + "；".join(snippets)
    # 只返回chunkId与分数，原文由Java重取。
    citations = [CitationOut(chunkId=hit.chunk.chunk_id, score=round(hit.score, 4)) for hit in hits]
    return TaskOut(state="SUCCEEDED", answer=answer, citations=citations)


@router.get("/tasks/{task_id}")
def task_status(task_id: str) -> dict:
    """同步接口不保存任务；异步状态以Java为准。"""
    raise HTTPException(status_code=404, detail="AI_TASK_NOT_FOUND")
