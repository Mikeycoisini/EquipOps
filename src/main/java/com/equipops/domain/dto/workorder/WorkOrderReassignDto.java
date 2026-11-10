package com.equipops.domain.dto.workorder;
import jakarta.validation.constraints.*;
import lombok.*;
/** 受控改派请求；客户端不得传企业或操作者。 */
@Data @NoArgsConstructor @AllArgsConstructor
public class WorkOrderReassignDto {
 @NotBlank private String requestId;
 @tools.jackson.databind.annotation.JsonDeserialize(using=com.equipops.domain.dto.tenant.SiteScopeUpdateDto.StrictVersionDeserializer.class)
 @NotNull @PositiveOrZero @Max(9_007_199_254_740_991L) private Long expectedVersion;
 @tools.jackson.databind.annotation.JsonDeserialize(using=com.equipops.domain.dto.tenant.SiteScopeUpdateDto.StrictSiteIdDeserializer.class)
 @NotNull @Positive private Long targetAssigneeUserId;
 @tools.jackson.databind.annotation.JsonDeserialize(using=com.equipops.domain.dto.tenant.SiteScopeUpdateDto.StrictSiteIdDeserializer.class)
 @Positive private Long targetTeamId;
 @NotBlank @Size(max=1000) private String reason;
}
