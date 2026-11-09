package com.equipops.domain.enums;

/** 审计结果；SUCCESS 必须与业务写入同事务，FAILURE/DENIED 独立记录。 */
public enum AuditOutcome {
    SUCCESS, FAILURE, DENIED
}
