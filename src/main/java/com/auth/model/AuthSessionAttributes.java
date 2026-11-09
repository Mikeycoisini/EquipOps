package com.auth.model;

/** 认证会话键，客户端不能通过请求参数直接写入这些属性。 */
public final class AuthSessionAttributes {
    /** 保存在 Session，跨请求记住用户已验证的企业选择。 */
    public static final String ACTIVE_TENANT_ID = "EQUIPOPS_ACTIVE_TENANT_ID";
    /** 仅保存在当前请求中，固定该请求执行期间的企业选择。 */
    public static final String REQUEST_TENANT_ID = AuthSessionAttributes.class.getName() + ".requestTenantId";
    /** 约定的企业管理员角色编码，需要由真实角色数据及成员关联赋予。 */
    public static final String TENANT_ADMIN_ROLE = "TENANT_ADMIN";

    /** 常量工具类不需要创建实例。 */
    private AuthSessionAttributes() {
    }
}
