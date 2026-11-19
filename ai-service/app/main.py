"""FastAPI入口；只应绑定127.0.0.1。"""

from fastapi import FastAPI

from app.api import router


def create_app() -> FastAPI:
    """构造应用，便于测试复用。"""
    application = FastAPI(title="EquipOps M7 Agent", version="0.1.0")
    application.include_router(router)
    return application


app = create_app()
