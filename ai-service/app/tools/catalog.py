"""只读工具目录；工具名与Java注册表保持一致。"""

import re
from dataclasses import dataclass

from pydantic import BaseModel, ConfigDict, Field

TOOL_NAME = re.compile(r"[a-z][a-z0-9_]{0,63}")


class EquipmentIdArgs(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    equipmentId: int = Field(gt=0)


class SopSearchArgs(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    query: str = Field(min_length=1, max_length=100)
    limit: int | None = Field(default=None, ge=1, le=10)


class InventoryStockArgs(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    sparePartId: int = Field(gt=0)
    warehouseId: int | None = Field(default=None, gt=0)


class ActiveAlarmsArgs(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


@dataclass(frozen=True)
class ToolSpec:
    name: str
    description: str
    input_model: type[BaseModel]


TOOLS = {
    "get_equipment_info": ToolSpec("get_equipment_info", "查询设备档案", EquipmentIdArgs),
    "get_equipment_history": ToolSpec("get_equipment_history", "查询设备维修履历", EquipmentIdArgs),
    "search_maintenance_sop": ToolSpec("search_maintenance_sop", "检索维修规范片段", SopSearchArgs),
    "search_spare_parts": ToolSpec("search_spare_parts", "查询设备适配备件", EquipmentIdArgs),
    "get_inventory_stock": ToolSpec("get_inventory_stock", "查询备件实时库存", InventoryStockArgs),
    "get_active_alarms": ToolSpec("get_active_alarms", "查询活动告警", ActiveAlarmsArgs),
}


def validate_arguments(name: str, arguments: dict) -> dict:
    """本地严格校验；未知工具或未知参数不发起HTTP。"""
    spec = TOOLS.get(name)
    if spec is None:
        raise ValueError("UNKNOWN_TOOL")
    if not isinstance(arguments, dict):
        raise ValueError("INVALID_TOOL_ARGUMENTS")
    return spec.input_model.model_validate(arguments).model_dump(exclude_none=True)
