package com.equipops.domain.dto.audit;

import com.equipops.domain.enums.AuditAction;
import com.equipops.domain.enums.AuditObjectType;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 审计追加命令，只由服务端组装，不直接绑定请求体。
 * 调用方必须先通过 CurrentAccessService 取得可信 tenantId 和 actorUserId。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditAppendCommand {
    /** 可信企业 ID；平台事件为空。 */
    private Long tenantId;

    /** 可信操作者用户 ID；系统任务为空。 */
    private Long actorUserId;

    /** 动作编码。 */
    private AuditAction action;

    /** 对象类型。 */
    private AuditObjectType objectType;

    /** 对象 ID，例如成员关系 ID。 */
    private Long objectId;

    /** 脱敏后的变更摘要，只放白名单字段。 */
    private Map<String, Object> changes;

    /** 失败事件的安全错误码；成功事件为空。 */
    private String errorCode;

    /** 异步任务显式传入；同步请求留空，由服务读取当前请求编号。 */
    private String requestId;
}
