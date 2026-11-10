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
 @tools.jackson.databind.annotation.JsonDeserialize(using=TrimmedReason.class)
 @NotBlank @Size(max=1000) private String reason;
 /** JSON构造绑定可能绕过setter，因此字段解码阶段也须执行相同正文规范化。 */
 public static class TrimmedReason extends tools.jackson.databind.ValueDeserializer<String> {
  @Override public String deserialize(tools.jackson.core.JsonParser parser,tools.jackson.databind.DeserializationContext context){
   if(parser.currentToken()!=tools.jackson.core.JsonToken.VALUE_STRING)
    return context.reportInputMismatch(String.class,"交接原因必须是字符串"); // 非文本不能通过隐式转换成为交接理由。
   return parser.getString().trim(); // 在构造绑定及Bean Validation之前去除首尾空白。
  }
 }
 /** 1.保留空值供必填校验；2.与事务服务一致去首尾空白；3.再由Bean Validation检查正文长度。 */
 public void setReason(String reason){
  this.reason=reason==null?null:reason.trim(); // HTTP和直接setter调用统一使用规范正文，不放宽1000字符上限。
 }
}
