package com.team.dto;
import jakarta.validation.constraints.*;
import java.util.List;
import lombok.*;
import tools.jackson.databind.annotation.JsonDeserialize;
/** 完整替换工作厂区集合，不扩大任何个人厂区权限。 */
@Data @NoArgsConstructor @AllArgsConstructor
public class TeamSitesDto {
    @NotBlank @Size(min=36,max=36) private String requestId;
    @NotNull @PositiveOrZero @Max(TeamInput.MAX_VERSION) @JsonDeserialize(using=TeamInput.Version.class)
    private Long expectedVersion;
    @NotNull @Size(max=500) @JsonDeserialize(contentUsing=TeamInput.Id.class)
    private List<@NotNull @Positive Long> siteIds;
}
