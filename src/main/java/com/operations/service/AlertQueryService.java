package com.operations.service;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.equipops.domain.dto.operations.AlertPageQueryDto;
import com.equipops.domain.vo.AlertEventVO;
/** 当前企业管理员告警只读查询，不包含检测、处置或通知投递。 */
public interface AlertQueryService {
 IPage<AlertEventVO> page(AlertPageQueryDto query);
 AlertEventVO detail(Long id);
}
