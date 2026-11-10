package com.team.dto;
import jakarta.validation.constraints.*;
import lombok.*;
/** 新建团队；企业和创建人只从可信上下文取得。 */
@Data @NoArgsConstructor @AllArgsConstructor
public class TeamCreateDto {
    @NotBlank @Size(min=36,max=36) private String requestId;
    @NotBlank @Size(max=64) private String teamCode;
    @NotBlank @Size(max=160) private String name;
    private Boolean enabled;
}
