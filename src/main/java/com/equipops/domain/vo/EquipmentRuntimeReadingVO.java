package com.equipops.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class EquipmentRuntimeReadingVO {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long id;
    private BigDecimal hours;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long recordedBy;
    private Long recordedAt;
}
