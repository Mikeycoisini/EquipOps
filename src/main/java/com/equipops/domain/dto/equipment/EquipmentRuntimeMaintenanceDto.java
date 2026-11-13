package com.equipops.domain.dto.equipment;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record EquipmentRuntimeMaintenanceDto(
        @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal thresholdHours,
        @NotNull @PositiveOrZero Long expectedVersion) {
}
