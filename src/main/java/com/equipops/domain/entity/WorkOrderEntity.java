package com.equipops.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.equipops.domain.enums.WorkOrderPriority;
import com.equipops.domain.enums.WorkOrderStatus;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 维修工单。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity @Table(name = "work_orders")
@TableName("work_orders")
public class WorkOrderEntity {
    /** 维修工单 ID */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属企业 ID，由服务端当前企业上下文确定。 */
    @Column(name = "tenant_id")
    @TableField(value = "tenant_id", updateStrategy = FieldStrategy.NEVER)
    private Long tenantId;

    /** 所属厂区 ID，工单继承关联设备的厂区。 */
    @Column(name = "site_id")
    @TableField(value = "site_id", updateStrategy = FieldStrategy.NEVER)
    private Long siteId;

    /** 当前责任团队，可空表示个人接单。 */
    @Column(name="team_id")
    private Long teamId;
    /** 状态与责任变更共同推进的乐观版本。 */
    @Column(name="version", nullable=false)
    @TableField(value="version", insertStrategy=FieldStrategy.NEVER, updateStrategy=FieldStrategy.NEVER)
    private Long version = 0L;

    /** 区分人工报修与小时维护，历史记录由数据库默认值归类。 */
    @Column(name = "source", nullable = false, length = 24)
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String source;

    /** 工单编号 */
    @Column(name = "order_no", nullable = false, length = 40, unique = true)
    private String orderNo;

    /** 标题 */
    @Column(nullable = false, length = 200)
    private String title;

    /** 关联设备 ID */
    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    /** 描述 */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** 工单优先级 */
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private WorkOrderPriority priority = WorkOrderPriority.NORMAL;

    /** 工单状态 */
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
    private WorkOrderStatus status = WorkOrderStatus.PENDING;

    /** 工单负责人用户 ID */
    @Column(name = "assignee_user_id")
    private Long assigneeUserId;

    /** 截止时间 */
    @Column(name = "due_at")
    private LocalDateTime dueAt;

    /** 完成时间 */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** 创建人用户 ID */
    @Column(name = "created_by")
    private Long createdBy;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    @TableField(value = "updated_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
