package com.equipops.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.equipops.domain.enums.EquipmentStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 设备。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity @Table(name = "equipment", uniqueConstraints =
        @UniqueConstraint(name = "uk_equipment_tenant_code", columnNames = {"tenant_id", "asset_code"}))
@TableName("equipment")
public class EquipmentEntity {
    /** 设备 ID */
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

    /** 设备资产编号 */
    @Column(name = "asset_code", nullable = false, length = 64)
    @TableField("asset_code")
    private String assetCode;

    /** 设备名称 */
    @Column(nullable = false, length = 160)
    private String name;

    /** 设备型号 */
    @Column(length = 120)
    private String model;

    /** 所在位置或库位 */
    @Column(length = 160)
    private String location;

    /** 分类 */
    @Column(length = 100)
    private String category;

    /** 设备运行状态，默认未运行。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    // 档案编辑不能覆盖并发状态变化；独立状态接口通过更新条件显式设置状态。
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private EquipmentStatus status = EquipmentStatus.STOPPED;
    /** 设备健康度（0～100） */
    // 与V1迁移的TINYINT UNSIGNED匹配，避免新建测试库时被推断为SMALLINT。
    @Column(columnDefinition = "TINYINT UNSIGNED")
    private Short health;

    /** 下次维护日期 */
    @Column(name = "next_maintenance_at")
    @TableField("next_maintenance_at")
    private LocalDate nextMaintenanceAt;

    /** 累计运行小时数 */
    @Column(name = "runtime_hours", precision = 14, scale = 2)
    @TableField("runtime_hours")
    private BigDecimal runtimeHours;

    /** 设备图片地址或资源标识 */
    @Column(name = "image_ref", length = 500)
    @TableField("image_ref")
    private String imageRef;

    /** 设备投产日期 */
    @Column(name = "installed_at")
    @TableField("installed_at")
    private LocalDate installedAt;

    /** 设备负责人用户 ID */
    @Column(name = "manager_user_id")
    @TableField("manager_user_id")
    private Long managerUserId;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    @TableField(value = "updated_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
