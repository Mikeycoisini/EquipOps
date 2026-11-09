package com.equipops.domain.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 工单备件关联。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity @Table(name = "work_order_parts", uniqueConstraints = @UniqueConstraint(name = "uk_work_order_parts_order_part", columnNames = {"work_order_id", "spare_part_id"}))
public class WorkOrderPartEntity {
    /** 工单备件关联 ID */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联工单 ID */
    @Column(name = "work_order_id", nullable = false)
    private Long workOrderId;

    /** 关联备件 ID */
    @Column(name = "spare_part_id", nullable = false)
    private Long sparePartId;

    /** 计划使用数量 */
    @Column(name = "planned_quantity", nullable = false, precision = 14, scale = 3)
    private BigDecimal plannedQuantity = BigDecimal.ZERO;

    /** 已领用数量 */
    @Column(name = "issued_quantity", nullable = false, precision = 14, scale = 3)
    private BigDecimal issuedQuantity = BigDecimal.ZERO;

    /** 已退回数量 */
    @Column(name = "returned_quantity", nullable = false, precision = 14, scale = 3)
    private BigDecimal returnedQuantity = BigDecimal.ZERO;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
