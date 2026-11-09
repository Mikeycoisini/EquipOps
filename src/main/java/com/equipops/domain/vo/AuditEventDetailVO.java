package com.equipops.domain.vo;

import java.util.Map;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** 审计事件详情返回数据；在列表字段基础上补充脱敏后的变更摘要。 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AuditEventDetailVO extends AuditEventVO {
    /** 脱敏后的变更摘要；解析失败时为 null，不返回原始文本 */
    private Map<String, Object> changes;
}
