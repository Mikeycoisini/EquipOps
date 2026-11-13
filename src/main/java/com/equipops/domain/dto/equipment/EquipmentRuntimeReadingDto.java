package com.equipops.domain.dto.equipment;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record EquipmentRuntimeReadingDto(
        @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal hours) {
}
