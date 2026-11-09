package com.auth.service;

import com.auth.model.TenantAccess;

/**
 * 业务模块统一使用的可信身份与企业范围入口，具体检查写在 CurrentAccessServiceImpl。
 * 使用方式：先取得可信 userId/tenantId，再把 tenantId 加入自己的查询和写入条件。
 * 调用这些方法不会自动替所有 Mapper 的 SQL 添加企业条件。
 */
public interface CurrentAccessService {
    /** 返回当前认证且仍启用的用户 ID；未登录或账号已失效返回 401。 */
    Long requireEnabledUserId();

    /** 验证当前企业和成员关系后返回企业 ID；没有选择企业返回 409。 */
    Long requireActiveTenantId();

    /** 验证当前企业和成员关系，并实时读取企业角色。 */
    TenantAccess requireTenantAccess();

    /** 验证当前用户能否进入拟选择的企业；不修改会话。 */
    TenantAccess validateTenantSelection(Long tenantId);

    /** 验证当前企业管理员角色，返回可信当前企业 ID。 */
    Long requireTenantAdminTenantId();
}
