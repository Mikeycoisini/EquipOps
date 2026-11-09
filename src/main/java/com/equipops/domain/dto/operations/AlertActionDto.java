package com.equipops.domain.dto.operations;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 确认、备注或结案的动作入参；动作由后续具体接口确定，不能自报身份或恢复状态。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlertActionDto {
    /** 调用者最后读到的版本号。 */
    @NotNull
    @Min(0)
    private Long expectedVersion;

    /** 必填处置说明，不能包含敏感信息。 */
    @NotBlank
    @Size(max = 1000)
    private String note;
}
