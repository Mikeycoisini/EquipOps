package com.inventory.transfer;

import com.auth.model.TenantAccess;
import com.auth.scope.SiteWriteAccessService;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.tenant.WarehouseUpsertDto;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class WarehouseCatalogService {
    private final JdbcTemplate jdbc;
    private final CurrentAccessService access;
    private final SiteWriteAccessService sites;
    private final TransferService transfers;

    public record Warehouse(String id, String siteId, String warehouseCode, String name, String location,
                            boolean enabled, boolean isDefault) {}

    @Transactional(readOnly = true)
    public List<Warehouse> catalog() {
        // 1. 取得可信企业及成员范围；2. 返回该范围内的仓库目录。
        return visible(access.requireTenantAccess(), null);
    }

    @Transactional(readOnly = true)
    public Warehouse detail(long id) {
        // 1. 按可信范围查询；2. 不可见对象统一返回 404。
        var rows = visible(access.requireTenantAccess(), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "仓库不存在");
        return rows.get(0);
    }

    @Transactional
    public Warehouse update(long id, WarehouseUpsertDto request) {
        // 1. 与调拨使用同一企业锁，重新校验管理员并锁定目标仓库。
        var actor = transfers.lockAdministrator();
        long tenant = actor.getTenant().getId();
        var rows = jdbc.query("SELECT * FROM warehouses WHERE tenant_id=? AND id=? FOR UPDATE",
                WarehouseCatalogService::map, tenant, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "仓库不存在");
        var warehouse = rows.get(0);
        long oldSite = Long.parseLong(warehouse.siteId());
        long newSite = request.getSiteId();
        boolean enabled = request.getEnabled() == null ? warehouse.enabled() : request.getEnabled();
        boolean moving = oldSite != newSite;
        // 2. 启用或迁移前校验目标厂区，并保护默认仓与业务历史。
        if (enabled || moving) sites.lockSite(newSite);
        if (warehouse.isDefault() && (!enabled || moving)) {
            throw conflict("默认仓库不能停用或变更厂区");
        }
        if (!enabled || moving) {
            boolean hasBalance = exists("""
                SELECT id FROM warehouse_stock WHERE tenant_id=? AND warehouse_id=? AND quantity>0 LIMIT 1 FOR UPDATE
                """, tenant, id);
            boolean hasTransit = exists("""
                SELECT id FROM warehouse_transfers WHERE tenant_id=?
                    AND (source_warehouse_id=? OR target_warehouse_id=?)
                    AND status IN ('IN_TRANSIT','EXCEPTION') LIMIT 1 FOR UPDATE
                """, tenant, id, id);
            if (hasBalance || hasTransit) throw conflict("仓库仍有库存或在途调拨");
        }
        if (moving && (exists("""
                SELECT id FROM inventory_transactions WHERE tenant_id=? AND warehouse_id=? LIMIT 1 FOR UPDATE
                """, tenant, id) || exists("""
                SELECT id FROM warehouse_transfers WHERE tenant_id=?
                    AND (source_warehouse_id=? OR target_warehouse_id=?) LIMIT 1 FOR UPDATE
                """, tenant, id, id))) {
            throw conflict("已有流水或调拨历史的仓库不能变更厂区");
        }
        // 3. 更新仓库配置，数据库约束处理并发重号。
        String code = request.getWarehouseCode().trim();
        String name = request.getName().trim();
        String location = request.getLocation();
        location = location == null || location.isBlank() ? null : location.trim();
        try {
            jdbc.update("""
                UPDATE warehouses SET site_id=?,warehouse_code=?,name=?,location=?,enabled=? WHERE tenant_id=? AND id=?
                """, newSite, code, name, location, enabled, tenant, id);
        } catch (DuplicateKeyException e) {
            throw conflict("仓库编号已存在");
        }
        return new Warehouse(warehouse.id(), Long.toString(newSite), code, name, location, enabled, warehouse.isDefault());
    }

    private List<Warehouse> visible(TenantAccess actor, Long id) {
        var args = new ArrayList<Object>();
        args.add(actor.getTenant().getId());
        String sql = "SELECT w.* FROM warehouses w WHERE w.tenant_id=?";
        if (!actor.getRoleCodes().contains("TENANT_ADMIN")) {
            sql += """
                 AND EXISTS (SELECT 1 FROM tenant_member_site_scopes g
                    WHERE g.tenant_id=w.tenant_id AND g.membership_id=? AND g.site_id=w.site_id)
                """;
            args.add(actor.getMembershipId());
        }
        if (id != null) {
            sql += " AND w.id=?";
            args.add(id);
        }
        return jdbc.query(sql + " ORDER BY w.id", WarehouseCatalogService::map, args.toArray());
    }

    private boolean exists(String sql, Object... args) {
        return !jdbc.queryForList(sql, Long.class, args).isEmpty();
    }

    private static Warehouse map(ResultSet rs, int index) throws SQLException {
        return new Warehouse(rs.getString("id"), rs.getString("site_id"), rs.getString("warehouse_code"),
                rs.getString("name"), rs.getString("location"), rs.getBoolean("enabled"), rs.getBoolean("is_default"));
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
