package com.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.equipops.domain.dto.audit.AuditEventPageQueryDto;
import com.equipops.domain.entity.AuditEventEntity;
import com.equipops.domain.vo.AuditEventDetailVO;
import com.equipops.domain.vo.AuditEventVO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 审计事件数据访问接口。所有查询必须带可信企业条件。 */
@Mapper
public interface AuditEventMapper extends BaseMapper<AuditEventEntity> {
    /** 统计当前企业符合条件的审计事件。 */
    long countByQuery(@Param("tenantId") Long tenantId,
                      @Param("q") AuditEventPageQueryDto query);

    /** 分页查询当前企业审计事件，固定按发生时间、ID 倒序。 */
    List<AuditEventVO> selectPage(@Param("tenantId") Long tenantId,
                                  @Param("q") AuditEventPageQueryDto query,
                                  @Param("offset") long offset,
                                  @Param("limit") long limit);

    /** 按企业查询审计详情；不存在或属于其他企业时返回 null。 */
    AuditEventDetailVO selectDetailById(@Param("tenantId") Long tenantId,
                                        @Param("id") Long id);
}
