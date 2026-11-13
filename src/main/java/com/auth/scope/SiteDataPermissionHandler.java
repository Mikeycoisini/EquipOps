package com.auth.scope;

import com.auth.model.TenantAccess;
import com.auth.service.CurrentAccessService;
import com.baomidou.mybatisplus.extension.plugins.handler.MultiDataPermissionHandler;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;

/** AST级企业及厂区边界；仅后台专用Mapper自带固定任务主体授权，其他无主体访问均拒绝。 */
public final class SiteDataPermissionHandler implements MultiDataPermissionHandler {
    private static final Set<String> DIRECT = Set.of("warehouses", "equipment", "work_orders",
            "maintenance_plans", "maintenance_executions", "equipment_runtime_readings", "equipment_runtime_maintenance");
    private static final Set<String> INDIRECT = Set.of("warehouse_stock", "inventory_transactions",
            "equipment_spare_parts", "work_order_logs", "work_order_parts");
    private static final Set<String> BACKGROUND = Set.of("com.inventory.mapper.InventoryExportTaskMapper",
            "com.inventory.mapper.InventoryExportDownloadMapper");
    private final Supplier<CurrentAccessService> access;

    public SiteDataPermissionHandler(Supplier<CurrentAccessService> access) {
        this.access = access; // 延迟取得可信身份服务，避免其Mapper依赖导致Bean循环。
    }

    /** 1.识别精确表/后台入口；2.实时校验身份；3.组合企业和父对象厂区条件；4.解析为AST。 */
    @Override
    public Expression getSqlSegment(Table table, Expression where, String mappedStatementId) {
        String name = table.getName().replace("`", "").toLowerCase(Locale.ROOT); // 表名只来自已解析SQL。
        if (!DIRECT.contains(name) && !INDIRECT.contains(name) && !Set.of("sites", "spare_parts").contains(name)) {
            return null; // 身份、授权及非厂区系统表沿用各自显式企业规则。
        }
        int split = mappedStatementId == null ? -1 : mappedStatementId.lastIndexOf('.'); // 精确提取Mapper类型。
        if (split > 0 && BACKGROUND.contains(mappedStatementId.substring(0, split))) {
            return null; // 专用任务SQL已按固定任务主体、快照和当前授权显式检查。
        }
        TenantAccess actor;
        try {
            actor = access.get().requireTenantAccess(); // 每次受保护SQL重新验证账号、企业、成员与角色。
        } catch (RuntimeException unavailable) {
            return expression("1 = 0"); // 无HTTP主体/身份失效/查询异常均不得自动成为系统权限。
        }
        if (actor == null || actor.getTenant() == null || actor.getTenant().getId() == null
                || actor.getTenant().getId() <= 0 || actor.getMembershipId() == null
                || actor.getMembershipId() <= 0 || actor.getUserId() == null || actor.getUserId() <= 0) {
            return expression("1 = 0"); // 不完整可信上下文同样关闭访问。
        }
        String alias = table.getAlias() == null ? table.getFullyQualifiedName() : table.getAlias().getName(); // 保留引用别名。
        long tenant = actor.getTenant().getId(); // 仅可信Long进入SQL常量，无客户端正文拼接。
        boolean admin = actor.getRoleCodes() != null && actor.getRoleCodes().contains("TENANT_ADMIN"); // 管理员仅豁免厂区。
        String predicate;
        if (name.equals("work_order_logs") || name.equals("work_order_parts")) {
            predicate = parent("work_orders", "_scope_order", alias + ".work_order_id", tenant, actor, admin); // 子表没有tenant列，按父工单隔离。
        } else {
            predicate = alias + ".tenant_id = " + tenant; // 所有带企业列的业务表始终限定当前企业。
            if (name.equals("sites")) {
                predicate += " AND " + site(alias + ".id", tenant, actor, admin); // 厂区自身按主键授权。
            } else if (DIRECT.contains(name)) {
                predicate += " AND " + site(alias + ".site_id", tenant, actor, admin); // 直接业务表按归属厂区授权。
            } else if (name.equals("equipment_spare_parts")) {
                predicate += " AND " + parent("equipment", "_scope_equipment", alias + ".equipment_id", tenant, actor, admin); // 反向适配关系同样受设备范围约束。
            } else if (name.equals("warehouse_stock") || name.equals("inventory_transactions")) {
                predicate += " AND " + parent("warehouses", "_scope_warehouse", alias + ".warehouse_id", tenant, actor, admin); // 主流水自身授权，不能依赖LEFT JOIN。
                if (name.equals("inventory_transactions")) {
                    predicate += " AND (" + alias + ".work_order_id IS NULL OR "
                            + parent("work_orders", "_scope_order", alias + ".work_order_id", tenant, actor, admin) + ")"; // 跨厂区领料必须同时可见仓库及工单。
                }
            }
        }
        return expression(predicate); // AST插件负责与原OR/分页/统计条件正确组合。
    }

    /** 1.管理员保留企业边界；2.普通成员要求显式授权且厂区启用，空集合自然恒假。 */
    private String site(String column, long tenant, TenantAccess actor, boolean admin) {
        if (admin) return "1 = 1"; // 全企业厂区管理权不依赖显式分配表。
        return "EXISTS (SELECT 1 FROM tenant_member_site_scopes _scope_grant JOIN sites _scope_site"
                + " ON _scope_site.tenant_id = _scope_grant.tenant_id AND _scope_site.id = _scope_grant.site_id"
                + " WHERE _scope_grant.tenant_id = " + tenant + " AND _scope_grant.membership_id = "
                + actor.getMembershipId() + " AND _scope_grant.site_id = " + column
                + " AND _scope_site.enabled = 1)"; // 子查询本身含完整企业范围，不依赖二次插件递归。
    }

    /** 1.定位同企业父对象；2.将父对象厂区加入当前表自己的WHERE条件。 */
    private String parent(String table, String alias, String id, long tenant, TenantAccess actor, boolean admin) {
        return "EXISTS (SELECT 1 FROM " + table + " " + alias + " WHERE " + alias + ".id = " + id
                + " AND " + alias + ".tenant_id = " + tenant + " AND "
                + site(alias + ".site_id", tenant, actor, admin) + ")"; // UPDATE/DELETE根表也获得独立父对象边界。
    }

    /** 1.把内部固定模板变为AST；2.解析异常终止查询，禁止忽略授权后重试。 */
    private Expression expression(String sql) {
        try {
            return CCJSqlParserUtil.parseCondExpression(sql); // 只解析内部模板及可信数值。
        } catch (Exception invalidTemplate) {
            throw new IllegalStateException("厂区权限条件生成失败", invalidTemplate); // 失败关闭访问。
        }
    }
}
