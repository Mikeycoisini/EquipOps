package com.statistics.service;

import com.equipops.domain.dto.statistics.EquipmentRepairCountQueryDto;
import com.equipops.domain.dto.statistics.EquipmentProcessingDurationQueryDto;
import com.equipops.domain.vo.EquipmentRepairCountVO;
import com.equipops.domain.vo.EquipmentProcessingDurationVO;
import java.util.List;

/** 设备报修统计业务。 */
public interface EquipmentStatisticsService {
    /** 当前企业有效成员查询报修次数排行。 */
    List<EquipmentRepairCountVO> getRepairCounts(EquipmentRepairCountQueryDto query);
    /** 返回有有效样本设备的平均处理时长排行及样本数。 */
    List<EquipmentProcessingDurationVO> getProcessingDuration(EquipmentProcessingDurationQueryDto query);

}
