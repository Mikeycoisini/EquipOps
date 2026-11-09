package com.equipops.domain.dto.workorder;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 工单验收退回请求，接收验收不通过的具体原因。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkOrderRejectDto {

    /** 验收退回的具体原因，供原维修负责人继续排查和处理。 */
    @NotBlank(message = "退回原因不能为空")
    @Size(max = 5000, message = "退回原因不能超过5000个字符")
    private String reason;
}
