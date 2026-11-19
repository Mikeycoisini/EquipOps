"""内部任务接口：命中、拒答与健康检查。"""

from fastapi.testclient import TestClient

from app.main import create_app

CLIENT = TestClient(create_app())


def payload(question: str) -> dict:
    return {
        "taskId": "1",
        "tenantId": "1",
        "question": question,
        "documents": [{
            "versionId": "2",
            "chunks": [{"chunkId": "3", "section": "维修", "text": "轴承更换步骤"}],
        }],
        "toolToken": "token",
        "tools": ["get_equipment_info"],
    }


def test_health():
    response = CLIENT.get("/internal/ai/health")
    assert response.status_code == 200
    assert response.json()["status"] == "ok"


def test_task_returns_chunk_id():
    response = CLIENT.post("/internal/ai/tasks", json=payload("轴承怎么换"))
    body = response.json()
    assert response.status_code == 200
    assert body["state"] == "SUCCEEDED"
    assert body["citations"][0]["chunkId"] == "3"


def test_task_refuses_without_hit():
    response = CLIENT.post("/internal/ai/tasks", json=payload("液压油型号"))
    body = response.json()
    assert body["state"] == "INSUFFICIENT_EVIDENCE"
    assert body["citations"] == []
    assert body["answer"] == ""
