package com.equipops.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.equipops.domain.enums.AlertRuleCode;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 企业级固定告警规则；本步仅MyBatis映射，不使用JPA、不建表。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("alert_rules")
public class AlertRuleEntity {
    /** 主键ID，由数据库生成。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属企业，后续由可信上下文或任务记录确定，不从浏览器接收。 */
    private Long tenantId;

    /** 固定规则编码，不接受任意表达式或脚本。 */
    private AlertRuleCode ruleCode;

    /** 是否启用检测。 */
    private Boolean enabled;

    /** 观测窗口内系统性失败数量阈值，实际默认值后续确认。 */
    private Integer thresholdCount;

    /** 触发观测窗口秒数。 */
    private Integer windowSeconds;

    /** 重复站内通知的冷却秒数，不是恢复判据。 */
    private Integer cooldownSeconds;

    /** 负责人的全局用户ID；后续须验证当前企业有效管理员身份。 */
    private Long ownerUserId;

    /** 条件更新版本，防止并发确认或处置互相覆盖。 */
    private Long version;

    /** 记录创建时间，后续数据库步骤确定生成策略。 */
    private LocalDateTime createdAt;

    /** 最近更新时间。 */
    private LocalDateTime updatedAt;
}
