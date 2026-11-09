package com.equipops.domain.vo;

import com.equipops.domain.enums.AuditAction;
import com.equipops.domain.enums.AuditActorType;
import com.equipops.domain.enums.AuditObjectType;
import com.equipops.domain.enums.AuditOutcome;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 审计事件列表返回数据。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditEventVO {
    /** 审计事件 ID */
    private Long id;

    /** 操作者用户 ID；系统任务为空 */
    private Long actorUserId;

    /** 操作人在本企业成员关系中关联的当前显示名；无法关联时为空，不是历史姓名快照 */
    private String actorName;

    /** 操作者类型 */
    private AuditActorType actorType;

    /** 动作编码 */
    private AuditAction action;

    /** 对象类型 */
    private AuditObjectType objectType;

    /** 对象 ID */
    private Long objectId;

    /** 审计结果 */
    private AuditOutcome outcome;

    /** 请求编号 */
    private String requestId;

    /** 业务发生时间 */
    private LocalDateTime occurredAt;

    /** 列表摘要，例如“角色：[ACCEPTOR] -> [TENANT_ADMIN]”；详情页仍以 changes 为准 */
    private String summary;

    /** 仅用于服务端计算 summary，序列化时不返回给前端。 */
    @JsonIgnore
    private String changesJson;
}
