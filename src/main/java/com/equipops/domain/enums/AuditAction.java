package com.equipops.domain.enums;

/**
 * 审计动作编码。
 * 数据库列使用 VARCHAR(64) 保存，后续模块进入对应迭代时再追加，不复用工单日志事件类型。
 */
public enum AuditAction {
    /** 企业成员加入。 */
    MEMBER_ADDED,
    /** 企业成员角色变更。 */
    MEMBER_ROLES_CHANGED,
    /** 企业成员启用或停用。 */
    MEMBER_STATUS_CHANGED,
    /** 企业成员显式厂区范围变更。 */
    MEMBER_SITE_SCOPES_CHANGED,
    /** 企业厂区创建。 */
    SITE_CREATED,
    /** 企业厂区配置修改。 */
    SITE_UPDATED,
    TEAM_CREATED,
    TEAM_UPDATED,
    TEAM_MEMBERS_CHANGED,
    TEAM_SITE_SCOPES_CHANGED,
    WORK_ORDER_REASSIGNED
}
