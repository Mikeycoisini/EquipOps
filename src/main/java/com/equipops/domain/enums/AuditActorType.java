package com.equipops.domain.enums;

/** 审计操作者类型；平台事件和系统任务不能伪装成普通企业用户。 */
public enum AuditActorType {
    USER, SYSTEM, PLATFORM
}
