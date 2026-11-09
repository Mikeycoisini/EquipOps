package com.audit.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.equipops.domain.dto.audit.AuditAppendCommand;
import com.equipops.domain.dto.audit.AuditEventPageQueryDto;
import com.equipops.domain.vo.AuditEventDetailVO;
import com.equipops.domain.vo.AuditEventVO;

/** 审计事件读写契约。查询只允许企业管理员；写入只接受服务端可信身份。 */
public interface AuditEventService {
    /** 分页查询当前企业审计事件；企业范围从登录上下文取得。 */
    IPage<AuditEventVO> page(AuditEventPageQueryDto query);

    /** 查询当前企业审计详情；其他企业的事件按不存在处理。 */
    AuditEventDetailVO detail(Long id);

    /** 成功事件：必须加入调用方事务，调用方回滚则审计一起回滚。 */
    void appendSuccess(AuditAppendCommand command);

    /** 失败事件：独立事务记录，业务回滚不影响它。 */
    void appendFailure(AuditAppendCommand command);

    /** 拒绝事件：独立事务记录，用于无权限等被拒绝的敏感操作。 */
    void appendDenied(AuditAppendCommand command);
}
