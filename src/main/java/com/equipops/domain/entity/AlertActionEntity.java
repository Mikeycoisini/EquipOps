package com.equipops.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.equipops.domain.enums.AlertActionType;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 告警人工处置时间线；不替代M1统一审计，当前不实现审计接口。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("alert_actions")
public class AlertActionEntity {
    /** 主键ID，由数据库生成。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属企业，后续由可信上下文或任务记录确定，不从浏览器接收。 */
    private Long tenantId;

    /** 当前企业告警事件ID。 */
    private Long eventId;

    /** 服务端取得的操作者ID。 */
    private Long actorUserId;

    /** 操作类型。 */
    private AlertActionType actionType;

    /** 处置说明，不得填写密码、令牌或完整SQL。 */
    private String note;

    /** 追踪请求编号，不等于业务幂等键。 */
    private String requestId;

    /** 记录创建时间，后续数据库步骤确定生成策略。 */
    private LocalDateTime createdAt;
}
