package com.operations.mapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipops.domain.dto.operations.AlertPageQueryDto;
import com.equipops.domain.vo.AlertEventVO;
import org.apache.ibatis.annotations.*;
/** 所有查询必须显式带可信企业ID；本步不提供写入入口。 */
@Mapper
public interface AlertEventMapper {
 IPage<AlertEventVO> selectEventPage(Page<AlertEventVO> page,@Param("tenantId") Long tenantId,
                                  @Param("query") AlertPageQueryDto query);
 AlertEventVO selectEventDetail(@Param("tenantId") Long tenantId,@Param("id") Long id);
}
