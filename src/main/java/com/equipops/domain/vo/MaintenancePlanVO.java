package com.equipops.domain.vo;

import com.equipops.domain.enums.MaintenancePlanType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 维护计划返回资料，设备及负责人名称在查询时联表加载。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MaintenancePlanVO {
    /** 维护计划主键，由数据库生成。 */
    private Long id;

    /** 所属企业，由可信登录上下文确定。 */
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
    private Boolean enabled;

    /** 创建人用户ID，由可信登录上下文取得。 */
    private Long createdBy;

    /** 创建时间，由数据库生成。 */
    private LocalDateTime createdAt;

    /** 最后更新时间，由数据库维护。 */
    private LocalDateTime updatedAt;

    /** 设备资产编号，查询时读取当前资料。 */
    private String equipmentAssetCode;

    /** 设备名称，查询时读取当前资料，不是历史快照。 */
    private String equipmentName;

    /** 负责人显示名，查询时通过同企业成员关系读取。 */
    private String assigneeName;
}
