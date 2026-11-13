package com.equipops.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class EquipmentRuntimeMaintenanceVO {
    private BigDecimal thresholdHours;
    private long version;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long workOrderId;
}
