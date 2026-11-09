package com.equipops.domain.vo;

import com.equipops.domain.enums.AlertActionType;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 告警处置时间线；仅向有权访问该告警的管理员返回。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlertActionVO {
    /** 记录ID。 */
    private Long id;

    /** 操作者ID。 */
    private Long actorUserId;

    /** 操作类型。 */
    private AlertActionType actionType;

    /** 安全处置说明。 */
    private String note;

    /** 记录创建时间，后续数据库步骤确定生成策略。 */
    private LocalDateTime createdAt;
}
