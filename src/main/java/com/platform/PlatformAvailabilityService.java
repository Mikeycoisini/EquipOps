package com.platform;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class PlatformAvailabilityService {
    private final JdbcTemplate jdbc;
    private final PlatformTenantService platform;

    private record Cursor(String stream, long id, LocalDateTime sampledAt) {}
    public record Alert(String id, String ownerUserId, String evidence, LocalDateTime openedAt,
                        LocalDateTime recoveredAt, LocalDateTime notifiedAt, LocalDateTime readAt,
                        LocalDateTime acknowledgedAt, LocalDateTime resolvedAt) {}

    @Transactional(rollbackFor = Exception.class)
    public void importEvidence(Path path, int port, long owner, int cooldownSeconds) throws SQLException {
        // 1. 验证负责人；2. 锁定游标；3. 按证据顺序写入事件与通知；4. 提交游标。
        if (jdbc.queryForList("""
                SELECT p.user_id FROM platform_operator_roles p JOIN users u ON u.id=p.user_id
                WHERE p.user_id=? AND p.role_code='PLATFORM_OPERATOR' AND p.enabled=1 AND u.enabled=1 FOR UPDATE
                """, Long.class, owner).isEmpty()) {
            throw new IllegalArgumentException("Probe owner is not an enabled platform operator");
        }
        var cursor = jdbc.queryForObject("SELECT stream_id,last_event_id,last_sample_at FROM platform_probe_cursor WHERE id=1 FOR UPDATE",
                (rs, row) -> new Cursor(rs.getString(1), rs.getLong(2), time(rs, "last_sample_at")));
        var batch = new ProbeEvidenceReader().read(path, port, cursor.id());
        if (cursor.stream() == null) {
            jdbc.update("UPDATE platform_probe_cursor SET stream_id=? WHERE id=1", batch.stream());
        } else if (!cursor.stream().equals(batch.stream())) {
            throw new IllegalArgumentException("Probe stream changed");
        }
        long last = cursor.id();
        LocalDateTime previous = cursor.sampledAt();
        for (var evidence : batch.events()) {
            var sampled = LocalDateTime.ofInstant(evidence.sampledAt(), ZoneOffset.UTC);
            if (evidence.id() != last + 1 || (previous != null && sampled.isBefore(previous))
                    || evidence.sampledAt().isAfter(Instant.now().plusSeconds(60))) {
                throw new IllegalArgumentException("Probe evidence sequence or time is invalid");
            }
            if (evidence.opening() != (last % 2 == 0)) {
                throw new IllegalArgumentException("Probe transitions are out of order");
            }
            if (evidence.opening()) {
                open(evidence, sampled, owner, cooldownSeconds);
            } else if (jdbc.update("""
                    UPDATE platform_availability_alerts SET recovered_at=?
                    WHERE id=? AND recovered_at IS NULL
                    """, sampled, last) != 1) {
                throw new IllegalStateException("Probe recovery lacks its opening event");
            }
            last = evidence.id();
            previous = sampled;
        }
        if (last != cursor.id()) {
            jdbc.update("UPDATE platform_probe_cursor SET last_event_id=?,last_sample_at=? WHERE id=1", last, previous);
        }
    }

    private void open(ProbeEvidenceReader.Event evidence, LocalDateTime sampled, long owner, int cooldownSeconds) {
        // 使用已通知事件的证据时间进行冷却，延迟补读不改变判断。
        var last = jdbc.queryForObject("SELECT MAX(opened_at) FROM platform_availability_alerts WHERE notified_at IS NOT NULL",
                (rs, row) -> time(rs, 1));
        var notified = last == null || !sampled.isBefore(last.plusSeconds(cooldownSeconds))
                ? LocalDateTime.now(ZoneOffset.UTC) : null;
        jdbc.update("""
                INSERT INTO platform_availability_alerts (id,owner_user_id,evidence,opened_at,notified_at)
                VALUES (?,?,?,?,?)
                """, evidence.id(), owner, evidence.evidence(), sampled, notified);
    }

    @Transactional(readOnly = true)
    public List<Alert> list(boolean inbox, long before) {
        // 1. 实时校验平台权限；2. 站内收件箱只允许读取自己的提醒。
        long user = platform.requireOperator(false);
        String sql = "SELECT * FROM platform_availability_alerts WHERE id < ?";
        return inbox ? jdbc.query(sql + " AND owner_user_id=? AND notified_at IS NOT NULL ORDER BY id DESC LIMIT 50",
                PlatformAvailabilityService::alert, before, user)
                : jdbc.query(sql + " ORDER BY id DESC LIMIT 50", PlatformAvailabilityService::alert, before);
    }

    @Transactional
    public void act(long id, PlatformAvailabilityController.Action action) {
        // 1. 锁定平台权限与负责事件；2. 校验状态；3. 写入动作与状态。
        long actor = platform.requireOperator(true);
        var alerts = jdbc.query("SELECT * FROM platform_availability_alerts WHERE id=? AND owner_user_id=? FOR UPDATE",
                PlatformAvailabilityService::alert, id, actor);
        if (alerts.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "告警不存在");
        var alert = alerts.get(0);
        var now = LocalDateTime.now(ZoneOffset.UTC);
        switch (action) {
            case READ -> {
                if (alert.notifiedAt() == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "提醒不存在");
                jdbc.update("UPDATE platform_availability_alerts SET read_at=COALESCE(read_at,?) WHERE id=?", now, id);
            }
            case ACKNOWLEDGE -> {
                if (alert.acknowledgedAt() != null) return;
                jdbc.update("UPDATE platform_availability_alerts SET acknowledged_at=? WHERE id=?", now, id);
                recordAction(id, actor, action, now);
            }
            case RESOLVE -> {
                if (alert.resolvedAt() != null) return;
                if (alert.acknowledgedAt() == null || alert.recoveredAt() == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "确认接手且探针验证恢复后才能结案");
                }
                jdbc.update("UPDATE platform_availability_alerts SET resolved_at=? WHERE id=?", now, id);
                recordAction(id, actor, action, now);
            }
        }
    }

    private void recordAction(long id, long actor, PlatformAvailabilityController.Action action, LocalDateTime now) {
        jdbc.update("INSERT INTO platform_availability_actions (alert_id,actor_user_id,action,occurred_at) VALUES (?,?,?,?)",
                id, actor, action.name(), now);
    }

    private static Alert alert(ResultSet rs, int row) throws SQLException {
        return new Alert(rs.getString("id"), rs.getString("owner_user_id"), rs.getString("evidence"),
                time(rs, "opened_at"), time(rs, "recovered_at"), time(rs, "notified_at"), time(rs, "read_at"),
                time(rs, "acknowledged_at"), time(rs, "resolved_at"));
    }

    private static LocalDateTime time(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class);
    }

    private static LocalDateTime time(ResultSet rs, int column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class);
    }
}
