package com.inventory.transfer;

import com.audit.service.AuditEventService;
import com.auth.model.TenantAccess;
import com.auth.scope.SiteWriteAccessService;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.audit.AuditAppendCommand;
import com.equipops.domain.enums.AuditAction;
import com.equipops.domain.enums.AuditObjectType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class TransferService {
    private final JdbcTemplate jdbc;
    private final CurrentAccessService access;
    private final SiteWriteAccessService sites;
    private final AuditEventService audit;

    public enum Status { REQUESTED, IN_TRANSIT, RECEIVED, CANCELLED, EXCEPTION }

    public enum Action {
        DISPATCH(Status.REQUESTED, Status.IN_TRANSIT, AuditAction.TRANSFER_DISPATCHED),
        RECEIVE(Status.IN_TRANSIT, Status.RECEIVED, AuditAction.TRANSFER_RECEIVED),
        CANCEL(Status.REQUESTED, Status.CANCELLED, AuditAction.TRANSFER_CANCELLED),
        EXCEPTION(Status.IN_TRANSIT, Status.EXCEPTION, AuditAction.TRANSFER_EXCEPTION_RECORDED),
        RESUME(Status.EXCEPTION, Status.IN_TRANSIT, AuditAction.TRANSFER_RESUMED);

        final Status from;
        final Status to;
        final AuditAction auditAction;

        Action(Status from, Status to, AuditAction auditAction) {
            this.from = from;
            this.to = to;
            this.auditAction = auditAction;
        }
    }

    public record Transfer(String id, String transferNo, String sourceWarehouseId, String targetWarehouseId,
                           String sparePartId, BigDecimal quantity, Status status, int version, String note,
                           LocalDateTime createdAt) {
        public BigDecimal inTransitQuantity() {
            return status == Status.IN_TRANSIT || status == Status.EXCEPTION ? quantity : BigDecimal.ZERO;
        }

        public BigDecimal getInTransitQuantity() {
            return inTransitQuantity();
        }

        Transfer withStatus(Status result, int resultVersion) {
            return new Transfer(id, transferNo, sourceWarehouseId, targetWarehouseId,
                    sparePartId, quantity, result, resultVersion, note, createdAt);
        }
    }

    public record Operation(String action, String actorUserId, String reason, LocalDateTime createdAt) {}
    public record Movement(String id, String warehouseId, String direction, BigDecimal quantity) {}
    public record Detail(Transfer transfer, List<Operation> operations, List<Movement> movements) {}
    public record Page(List<Transfer> records, long total, int page, int pageSize) {}
    private record Replay(long transferId, String hash, Status status, int version) {}

    private static final String READ_FROM = """
        FROM warehouse_transfers t
        JOIN warehouses sw ON sw.tenant_id=t.tenant_id AND sw.id=t.source_warehouse_id
        JOIN warehouses tw ON tw.tenant_id=t.tenant_id AND tw.id=t.target_warehouse_id
        WHERE t.tenant_id=?
        """;
    private static final String BOTH_SITES = """
        AND EXISTS (SELECT 1 FROM tenant_member_site_scopes g
            WHERE g.tenant_id=t.tenant_id AND g.membership_id=? AND g.site_id=sw.site_id)
        AND EXISTS (SELECT 1 FROM tenant_member_site_scopes g
            WHERE g.tenant_id=t.tenant_id AND g.membership_id=? AND g.site_id=tw.site_id)
        """;

    @Transactional
    public Transfer create(TransferController.Create request) {
        // 1. 锁定企业并以当前读复核管理员资格。
        var actor = lockAdministrator();
        long tenant = actor.getTenant().getId();
        // 2. 校验两端仓库、厂区及备件，并检查幂等结果。
        long source = request.sourceWarehouseId();
        long target = request.targetWarehouseId();
        if (source == target) throw error(HttpStatus.BAD_REQUEST, "源仓与目标仓不能相同");
        String note = normalize(request.note());
        String hash = hash("CREATE", source, target, request.sparePartId(),
                request.quantity().stripTrailingZeros().toPlainString(), note);
        var replay = replay(tenant, request.requestId(), hash);
        lockWarehouses(tenant, source, target, replay == null);
        if (replay != null) return replayResult(tenant, replay);
        if (jdbc.queryForList("SELECT id FROM spare_parts WHERE tenant_id=? AND id=? AND enabled=1 FOR UPDATE",
                Long.class, tenant, request.sparePartId()).isEmpty()) {
            throw error(HttpStatus.NOT_FOUND, "备件不存在或未启用");
        }
        // 3. 写入申请和幂等结果，申请不占用仓库余额。
        String number = "TR-" + UUID.randomUUID();
        jdbc.update("""
            INSERT INTO warehouse_transfers
            (tenant_id,transfer_no,source_warehouse_id,target_warehouse_id,spare_part_id,quantity,status,note,created_by)
            VALUES (?,?,?,?,?,?,'REQUESTED',?,?)
            """, tenant, number, source, target, request.sparePartId(), request.quantity(), note, actor.getUserId());
        var created = jdbc.queryForObject("SELECT * FROM warehouse_transfers WHERE tenant_id=? AND transfer_no=?",
                TransferService::map, tenant, number);
        // 4. 同一事务保存操作和审计。
        record(actor, created, "CREATE", request.requestId(), hash, note, AuditAction.TRANSFER_CREATED);
        return created;
    }

    @Transactional
    public Transfer act(long id, Action action, TransferController.Command request) {
        // 1. 获取可信管理员，锁住单据以及两端仓库并检查请求是否重放。
        var actor = lockAdministrator();
        long tenant = actor.getTenant().getId();
        var transfer = lockTransfer(tenant, id);
        long source = Long.parseLong(transfer.sourceWarehouseId());
        long target = Long.parseLong(transfer.targetWarehouseId());
        String reason = normalize(request.reason());
        String hash = hash(action.name(), id, request.expectedVersion(), reason);
        var replay = replay(tenant, request.requestId(), hash);
        lockWarehouses(tenant, source, target, replay == null && action != Action.CANCEL);
        if (replay != null) return replayResult(tenant, replay);
        // 2. 校验版本、状态和必填原因。
        if (transfer.version() != request.expectedVersion() || transfer.status() != action.from) {
            throw error(HttpStatus.CONFLICT, "调拨状态或版本已变更");
        }
        if (action != Action.DISPATCH && action != Action.RECEIVE && reason == null) {
            throw error(HttpStatus.BAD_REQUEST, "请填写操作原因");
        }
        // 3. 出库或收货同时修改余额和关联流水。
        if (action == Action.DISPATCH || action == Action.RECEIVE) {
            moveStock(tenant, actor.getUserId(), transfer, action == Action.RECEIVE ? target : source, action);
        }
        // 4. 更新状态并同事务保存操作结果和审计。
        var result = transfer.withStatus(action.to, transfer.version() + 1);
        jdbc.update("UPDATE warehouse_transfers SET status=?,version=? WHERE tenant_id=? AND id=?",
                result.status().name(), result.version(), tenant, id);
        record(actor, result, action.name(), request.requestId(), hash, reason, action.auditAction);
        return result;
    }

    @Transactional(readOnly = true)
    public Page list(Status status, Long warehouseId, LocalDateTime createdFrom, LocalDateTime createdBefore,
                     int page, int pageSize) {
        // 1. 取得可信查询范围；2. 加入筛选并查询总数和当前页。
        if (page < 1 || pageSize < 1 || pageSize > 100 || warehouseId != null && warehouseId < 1) {
            throw error(HttpStatus.BAD_REQUEST, "分页参数或仓库无效");
        }
        if (createdFrom != null && createdBefore != null && !createdFrom.isBefore(createdBefore)) {
            throw error(HttpStatus.BAD_REQUEST, "查询结束时间必须晚于开始时间");
        }
        var actor = access.requireTenantAccess();
        var args = readArgs(actor);
        String sql = readSql(actor);
        if (status != null) {
            sql += " AND t.status=?";
            args.add(status.name());
        }
        if (warehouseId != null) {
            sql += " AND (t.source_warehouse_id=? OR t.target_warehouse_id=?)";
            args.add(warehouseId);
            args.add(warehouseId);
        }
        if (createdFrom != null) {
            sql += " AND t.created_at>=?";
            args.add(createdFrom);
        }
        if (createdBefore != null) {
            sql += " AND t.created_at<?";
            args.add(createdBefore);
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) " + sql, Long.class, args.toArray());
        args.add(pageSize);
        args.add((page - 1L) * pageSize);
        var records = jdbc.query("SELECT t.* " + sql + " ORDER BY t.id DESC LIMIT ? OFFSET ?",
                TransferService::map, args.toArray());
        return new Page(records, total, page, pageSize);
    }

    @Transactional(readOnly = true)
    public Detail detail(long id) {
        // 1. 校验当前企业和两端厂区可见性；2. 查询操作历史与关联流水。
        var actor = access.requireTenantAccess();
        var args = readArgs(actor);
        args.add(id);
        var rows = jdbc.query("SELECT t.* " + readSql(actor) + " AND t.id=?", TransferService::map, args.toArray());
        if (rows.isEmpty()) throw error(HttpStatus.NOT_FOUND, "调拨单不存在");
        long tenant = actor.getTenant().getId();
        var operations = jdbc.query("""
            SELECT action,actor_user_id,reason,created_at FROM warehouse_transfer_operations
            WHERE tenant_id=? AND transfer_id=? ORDER BY id
            """, (rs, index) -> new Operation(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getObject(4, LocalDateTime.class)), tenant, id);
        var movements = jdbc.query("""
            SELECT id,warehouse_id,transaction_type,quantity FROM inventory_transactions
            WHERE tenant_id=? AND transfer_id=? ORDER BY id
            """, (rs, index) -> new Movement(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getBigDecimal(4)), tenant, id);
        return new Detail(rows.get(0), operations, movements);
    }

    TenantAccess lockAdministrator() {
        var actor = access.requireTenantAccess();
        long tenant = actor.getTenant().getId();
        if (!actor.getRoleCodes().contains("TENANT_ADMIN")
                || jdbc.queryForList("SELECT id FROM tenants WHERE id=? AND enabled=1 FOR UPDATE",
                    Long.class, tenant).isEmpty()
                || jdbc.queryForList("""
                    SELECT m.id FROM tenant_memberships m
                    JOIN users u ON u.id=m.user_id AND u.enabled=1
                    JOIN tenant_member_roles mr ON mr.tenant_id=m.tenant_id AND mr.membership_id=m.id
                    JOIN roles r ON r.id=mr.role_id AND r.role_code='TENANT_ADMIN'
                    WHERE m.tenant_id=? AND m.id=? AND m.user_id=? AND m.enabled=1 FOR UPDATE
                    """, Long.class, tenant, actor.getMembershipId(), actor.getUserId()).isEmpty()) {
            throw error(HttpStatus.FORBIDDEN, "需要当前企业的管理员权限");
        }
        return actor;
    }

    private void lockWarehouses(long tenant, long source, long target, boolean requireEnabled) {
        var warehouses = jdbc.query("""
            SELECT site_id,enabled FROM warehouses WHERE tenant_id=? AND id IN (?,?) ORDER BY id FOR UPDATE
            """, (rs, i) -> new WarehouseScope(rs.getLong(1), rs.getBoolean(2)), tenant, source, target);
        if (warehouses.size() != 2) throw error(HttpStatus.NOT_FOUND, "调拨仓库不存在");
        for (var warehouse : warehouses) {
            sites.lockSite(warehouse.siteId());
            if (requireEnabled && !warehouse.enabled()) throw error(HttpStatus.CONFLICT, "调拨仓库已停用");
        }
    }

    private record WarehouseScope(long siteId, boolean enabled) {}

    private Transfer lockTransfer(long tenant, long id) {
        var rows = jdbc.query("SELECT * FROM warehouse_transfers WHERE tenant_id=? AND id=? FOR UPDATE",
                TransferService::map, tenant, id);
        if (rows.isEmpty()) throw error(HttpStatus.NOT_FOUND, "调拨单不存在");
        return rows.get(0);
    }

    private void moveStock(long tenant, long user, Transfer transfer, long warehouse, Action action) {
        long part = Long.parseLong(transfer.sparePartId());
        var quantity = transfer.quantity();
        if (action == Action.DISPATCH) {
            int updated = jdbc.update("""
                UPDATE warehouse_stock SET quantity=quantity-?
                WHERE tenant_id=? AND warehouse_id=? AND spare_part_id=? AND quantity>=?
                """, quantity, tenant, warehouse, part, quantity);
            if (updated != 1) throw error(HttpStatus.CONFLICT, "源仓库存不足");
        } else {
            jdbc.update("""
                INSERT INTO warehouse_stock(tenant_id,warehouse_id,spare_part_id,quantity) VALUES (?,?,?,?)
                ON DUPLICATE KEY UPDATE quantity=quantity+?
                """, tenant, warehouse, part, quantity, quantity);
        }
        jdbc.update("""
            INSERT INTO inventory_transactions
            (tenant_id,warehouse_id,spare_part_id,transaction_no,transaction_type,quantity,
             reference_type,reference_text,transfer_id,created_by)
            VALUES (?,?,?,?,?,?,'TRANSFER',?,?,?)
            """, tenant, warehouse, part, UUID.randomUUID().toString(),
                action == Action.DISPATCH ? "OUTBOUND" : "INBOUND", quantity,
                transfer.transferNo(), Long.parseLong(transfer.id()), user);
    }

    private Replay replay(long tenant, String requestId, String hash) {
        var rows = jdbc.query("""
            SELECT transfer_id,payload_hash,result_status,result_version FROM warehouse_transfer_operations
            WHERE tenant_id=? AND request_id=? FOR UPDATE
            """, (rs, i) -> new Replay(rs.getLong(1), rs.getString(2), Status.valueOf(rs.getString(3)), rs.getInt(4)),
                tenant, requestId);
        if (rows.isEmpty()) return null;
        var result = rows.get(0);
        if (!result.hash().equals(hash)) throw error(HttpStatus.CONFLICT, "请求号已用于不同调拨操作");
        return result;
    }

    private Transfer replayResult(long tenant, Replay replay) {
        return lockTransfer(tenant, replay.transferId()).withStatus(replay.status(), replay.version());
    }

    private void record(TenantAccess actor, Transfer result, String action, String requestId,
                        String hash, String reason, AuditAction auditAction) {
        long tenant = actor.getTenant().getId();
        long id = Long.parseLong(result.id());
        jdbc.update("""
            INSERT INTO warehouse_transfer_operations
            (tenant_id,transfer_id,request_id,action,payload_hash,result_status,result_version,actor_user_id,reason)
            VALUES (?,?,?,?,?,?,?,?,?)
            """, tenant, id, requestId, action, hash, result.status().name(), result.version(), actor.getUserId(), reason);
        audit.appendSuccess(new AuditAppendCommand(tenant, actor.getUserId(), auditAction, AuditObjectType.TRANSFER,
                id, Map.of("status", result.status().name(), "version", result.version()), null, null));
    }

    private static List<Object> readArgs(TenantAccess actor) {
        var args = new ArrayList<Object>();
        args.add(actor.getTenant().getId());
        if (!actor.getRoleCodes().contains("TENANT_ADMIN")) {
            args.add(actor.getMembershipId());
            args.add(actor.getMembershipId());
        }
        return args;
    }

    private static String readSql(TenantAccess actor) {
        return READ_FROM + (actor.getRoleCodes().contains("TENANT_ADMIN") ? "" : BOTH_SITES);
    }

    private static Transfer map(ResultSet rs, int index) throws SQLException {
        return new Transfer(rs.getString("id"), rs.getString("transfer_no"), rs.getString("source_warehouse_id"),
                rs.getString("target_warehouse_id"), rs.getString("spare_part_id"), rs.getBigDecimal("quantity"),
                Status.valueOf(rs.getString("status")), rs.getInt("version"), rs.getString("note"),
                rs.getObject("created_at", LocalDateTime.class));
    }

    private static String hash(Object... fields) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var field : fields) {
                String value = Objects.toString(field, "");
                digest.update((value.length() + ":" + value).getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
