package com.equipops.domain.vo;

import com.equipops.domain.enums.MaintenancePlanType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 已完成的维护/巡检执行记录；计划及人员名称均保存为历史快照。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MaintenanceExecutionVO {
    /** 执行记录主键。 */
    private Long id;

    /** 所属企业。 */
    private Long tenantId;

    /** 关联计划主键。 */
    private Long planId;

    /** 本期设备所属厂区快照。 */
    private Long siteId;

    /** 本期关联设备ID快照。 */
    private Long equipmentId;

    /** 幂等请求号，同一企业内唯一。 */
    private String requestId;

    /** 登记时计划名称快照。 */
    private String planName;

    /** 登记时计划类型快照。 */
    private MaintenancePlanType planType;

    /** 登记时执行内容快照。 */
    private String planContent;

    /** 登记时周期天数快照。 */
    private Integer cycleDays;

    /** 本期原定到期日期。 */
    private LocalDate scheduledDate;

    /** 服务端记录的上海时区实际完成时间。 */
    private LocalDateTime executedAt;

    /** 实际执行人用户ID。 */
    private Long executedBy;

    /** 本次执行结果说明。 */
    private String result;

    /** 本次完成后推算的下次到期日。 */
    private LocalDate nextDueDate;

    /** 登记时设备编号快照。 */
    private String equipmentAssetCode;

    /** 登记时设备名称快照。 */
    private String equipmentName;

    /** 登记时执行人姓名快照。 */
    private String executedByName;

}
