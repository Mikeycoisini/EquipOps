package com.equipops.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 备件。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity @Table(name = "spare_parts", uniqueConstraints =
        @UniqueConstraint(name = "uk_spare_parts_tenant_code", columnNames = {"tenant_id", "part_code"}))
@TableName("spare_parts")
public class SparePartEntity {
    /** 备件 ID */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属企业 ID，由服务端当前企业上下文确定。 */
    @Column(name = "tenant_id")
    @TableField(value = "tenant_id", updateStrategy = FieldStrategy.NEVER)
    private Long tenantId;

    /** 备件编号 */
    @Column(name = "part_code", nullable = false, length = 64)
    private String partCode;

    /** 备件名称 */
    @Column(nullable = false, length = 160)
    private String name;

    /** 规格型号 */
    @Column(length = 160)
    private String spec;

    /** 分类 */
    @Column(length = 100)
    private String category;

    /** 所在位置或库位 */
    @Column(length = 120)
    private String location;

    /** 当前库存数量 */
    @Column(name = "stock_quantity", nullable = false, precision = 14, scale = 3)
    private BigDecimal stockQuantity = BigDecimal.ZERO;

    /** 安全库存数量 */
    @Column(name = "min_stock_quantity", nullable = false, precision = 14, scale = 3)
    private BigDecimal minStockQuantity = BigDecimal.ZERO;

    /** 计量单位 */
    @Column(nullable = false, length = 24)
    private String unit = "件";

    /** 是否启用 */
    @Column(nullable = false)
    private Boolean enabled = true;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    @TableField(value = "updated_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
