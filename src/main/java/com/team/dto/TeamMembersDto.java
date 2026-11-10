package com.team.dto;
import jakarta.validation.constraints.*;
import java.util.List;
import lombok.*;
import tools.jackson.databind.annotation.JsonDeserialize;
/** 完整替换成员集合；空数组清空，重复ID不得静默去重。 */
@Data @NoArgsConstructor @AllArgsConstructor
public class TeamMembersDto {
    @NotBlank @Size(min=36,max=36) private String requestId;
    @NotNull @PositiveOrZero @Max(TeamInput.MAX_VERSION) @JsonDeserialize(using=TeamInput.Version.class)
    private Long expectedVersion;
    @NotNull @Size(max=500) @JsonDeserialize(contentUsing=TeamInput.Id.class)
    private List<@NotNull @Positive Long> membershipIds;
}
