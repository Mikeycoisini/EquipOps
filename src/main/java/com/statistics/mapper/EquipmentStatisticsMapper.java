package com.statistics.mapper;

import com.equipops.domain.vo.EquipmentRepairCountVO;
import com.equipops.domain.vo.EquipmentProcessingDurationVO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 设备工单数统计，SQL位于XML并显式限定企业。 */
public interface EquipmentStatisticsMapper {
    List<EquipmentRepairCountVO> selectRepairCounts(@Param("tenantId") Long tenantId,
            @Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime,
            @Param("limit") int limit);
    /** 完成时间落在[startTime,endTime)内的有效工单，按设备聚合。 */
    List<EquipmentProcessingDurationVO> selectProcessingDuration(@Param("tenantId") Long tenantId,
            @Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime,
            @Param("limit") int limit);

}
