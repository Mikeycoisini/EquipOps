package com.equipops.domain.enums;

/** 人工操作类型；仅预留数据定义，权限及状态条件尚未实现。 */
public enum AlertActionType {
    /** 确认接手。 */
    ACKNOWLEDGE,
    /** 补充处置记录。 */
    ADD_NOTE,
    /** 按后续结案规则提交处置结果。 */
    RESOLVE
}
