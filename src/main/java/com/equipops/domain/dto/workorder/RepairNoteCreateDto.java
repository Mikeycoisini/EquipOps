package com.equipops.domain.dto.workorder;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RepairNoteCreateDto {

    /** 本次维修处理记录。 */
    @NotBlank(message = "维修记录不能为空")
    @Size(max = 5000, message = "维修记录不能超过5000个字符")
    private String detail;
}