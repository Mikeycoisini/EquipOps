package com.equipops.domain.entity;

import com.equipops.domain.enums.WorkOrderEventType;
import com.equipops.domain.enums.WorkOrderStatus;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 工单操作记录。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity @Table(name = "work_order_logs")
@TableName("work_order_logs")
public class WorkOrderLogEntity {
    /** 工单操作记录 ID */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联工单 ID */
    @Column(name = "work_order_id", nullable = false)
    private Long workOrderId;

    /** 工单事件类型 */
    @Enumerated(EnumType.STRING) @Column(name = "event_type", nullable = false, length = 32)
    private WorkOrderEventType eventType;

    /** 变更前状态 */
    @Enumerated(EnumType.STRING) @Column(name = "from_status", length = 32)
    private WorkOrderStatus fromStatus;

    /** 变更后状态 */
    @Enumerated(EnumType.STRING) @Column(name = "to_status", length = 32)
    private WorkOrderStatus toStatus;

    /** 标题 */
    @Column(nullable = false, length = 160)
    private String title;

    /** 事件详情 */
    @Column(columnDefinition = "TEXT")
    private String detail;

    /** 操作人用户 ID */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
