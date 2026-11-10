package com.team.dto;
import jakarta.validation.constraints.*;
import lombok.*;
import tools.jackson.databind.annotation.JsonDeserialize;
/** 更新团队名称和启停；团队编号不可修改。 */
@Data @NoArgsConstructor @AllArgsConstructor
public class TeamUpdateDto {
    @NotBlank @Size(min=36,max=36) private String requestId;
    @NotNull @PositiveOrZero @Max(TeamInput.MAX_VERSION) @JsonDeserialize(using=TeamInput.Version.class)
    private Long expectedVersion;
    @NotBlank @Size(max=160) private String name;
    @NotNull private Boolean enabled;
}
