package com.equipops.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.equipops.domain.enums.MaintenancePlanType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 日期周期维护/巡检计划，仅使用MyBatis映射，不触发未建表的JPA校验。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("maintenance_plans")
public class MaintenancePlanEntity {
    /** 维护计划主键，由数据库生成。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属企业，由可信登录上下文确定。 */
    @TableField(value = "tenant_id", updateStrategy = FieldStrategy.NEVER)
    private Long tenantId;

    /** 所属厂区，继承已校验设备的厂区。 */
    private Long siteId;

    /** 关联设备ID，必须属于当前企业。 */
    private Long equipmentId;

    /** 计划名称，最多160个字符。 */
    private String name;

    /** 计划类型：维护或巡检。 */
    private MaintenancePlanType planType;

    /** 维护或巡检的执行内容，最多2000个字符。 */
    private String content;

    /** 按日期循环的间隔天数，范围1到3650。 */
    private Integer cycleDays;

    /** 下次到期日期；创建时表示首次到期日。 */
    private LocalDate nextDueDate;

    /** 负责人用户ID，应为当前企业启用成员。 */
    private Long assigneeUserId;

    /** 是否启用；创建时由业务层确定为true。 */
    private Boolean enabled = true;

    /** 创建人用户ID，由可信登录上下文取得。 */
    @TableField(value = "created_by", updateStrategy = FieldStrategy.NEVER)
    private Long createdBy;

    /** 创建时间，由数据库生成。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    /** 最后更新时间，由数据库维护。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
