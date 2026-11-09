package com.equipops.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.equipops.domain.enums.AuditAction;
import com.equipops.domain.enums.AuditActorType;
import com.equipops.domain.enums.AuditObjectType;
import com.equipops.domain.enums.AuditOutcome;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 统一审计事件。只追加，不提供修改和删除。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity @Table(name = "audit_events")
@TableName("audit_events")
public class AuditEventEntity {
    /** 审计事件 ID */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 企业 ID；平台事件可为空 */
    @Column(name = "tenant_id")
    private Long tenantId;

    /** 操作者用户 ID；系统任务可为空 */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    /** 操作者类型 */
    @Enumerated(EnumType.STRING) @Column(name = "actor_type", nullable = false, length = 16)
    private AuditActorType actorType;

    /** 动作编码 */
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 64)
    private AuditAction action;

    /** 对象类型 */
    @Enumerated(EnumType.STRING) @Column(name = "object_type", nullable = false, length = 64)
    private AuditObjectType objectType;

    /** 对象 ID，例如成员关系 ID */
    @Column(name = "object_id")
    private Long objectId;

    /** 审计结果 */
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private AuditOutcome outcome;

    /** 请求编号；同步请求由服务端过滤器生成，异步任务显式传入 */
    @Column(name = "request_id", length = 36)
    private String requestId;

    /** 脱敏后的变更摘要 JSON，不保存请求正文、密码或令牌 */
    @Column(name = "changes_json", columnDefinition = "JSON")
    private String changesJson;

    /** 业务发生时间 */
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    /** 落库时间 */
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
