package com.equipops.domain.enums;

/** 审计对象类型；首版只接入企业成员，其他模块按迭代再追加。 */
public enum AuditObjectType {
    MEMBER,
    SITE,
    TEAM,
    WORK_ORDER
}
