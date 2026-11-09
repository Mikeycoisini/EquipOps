package com.equipops.domain.dto.audit;

import com.equipops.domain.enums.AuditAction;
import com.equipops.domain.enums.AuditObjectType;
import com.equipops.domain.enums.AuditOutcome;
import jakarta.validation.constraints.Positive;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

/** 审计事件查询条件；企业范围从登录上下文取得，不接收客户端 tenantId。 */
@Data
@NoArgsConstructor
public class AuditEventPageQueryDto {
    /** 当前页码，小于1时按第1页处理。 */
    private long current = 1;

    /** 每页条数，业务层限制为1到100。 */
    private long size = 20;

    /** 动作编码，不传时不筛选。 */
    private AuditAction action;

    /** 操作者用户 ID，必须为正数。 */
    @Positive(message = "操作者 ID 必须大于0")
    private Long actorUserId;

    /** 对象类型，不传时不筛选。 */
    private AuditObjectType objectType;

    /** 对象 ID，必须为正数。 */
    @Positive(message = "对象 ID 必须大于0")
    private Long objectId;

    /** 审计结果，不传时不筛选。 */
    private AuditOutcome outcome;

    /** 发生时间下限，包含。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime occurredFrom;

    /** 发生时间上限，包含。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime occurredTo;
}
