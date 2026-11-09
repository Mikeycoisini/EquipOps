package com.equipops.domain.dto.maintenance;

import com.equipops.domain.enums.MaintenancePlanType;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 维护计划分页条件；企业范围取登录上下文，不接收客户端tenantId。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MaintenancePlanPageQueryDto {
    /** 页码，默认第1页；小于1时由业务层按1处理。 */
    private long current = 1;

    /** 每页条数，默认10；业务层限制在1到100。 */
    private long size = 10;

    /** 计划名称关键字；省略或空白时不筛选。 */
    @Size(max = 160, message = "关键字不能超过160个字符")
    private String keyword;

    /** 可选设备ID，提供时必须属于当前企业。 */
    @Positive(message = "设备ID必须大于0")
    private Long equipmentId;

    /** MAINTENANCE维护、INSPECTION巡检；省略时查询两种类型。 */
    private MaintenancePlanType planType;

    /** true查询启用、false查询停用；省略时不限制启用状态。 */
    private Boolean enabled;
    /** true只查询已启用、已到期且本期未登记的计划；false或省略保留原查询。 */
    private Boolean dueOnly;

    /** 保留原有六参数构造方式，新筛选条件默认不启用。 */
    public MaintenancePlanPageQueryDto(long current, long size, String keyword,
            Long equipmentId, MaintenancePlanType planType, Boolean enabled) {
        this(current, size, keyword, equipmentId, planType, enabled, null);
    }

}
