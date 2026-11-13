package com.equipment.service.impl;

import com.auth.scope.SiteWriteAccessService;
import com.auth.service.CurrentAccessService;
import com.equipment.mapper.EquipmentRuntimeReadingMapper;
import com.equipment.service.EquipmentService;
import com.equipops.domain.vo.EquipmentRuntimeReadingVO;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class EquipmentRuntimeReadingService {
    private final EquipmentService equipment;
    private final EquipmentRuntimeReadingMapper readings;
    private final SiteWriteAccessService siteWriteAccess;
    private final CurrentAccessService access;

    /** 1.核验设备的企业与厂区可见性；2.按设备读取最近记录。 */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public List<EquipmentRuntimeReadingVO> recent(Long equipmentId) {
        // 设备详情沿用现有的企业和厂区数据权限。
        var asset = equipment.getEquipmentDetail(equipmentId);
        // 历史仅返回该设备最近20条，读数表同样应用厂区权限。
        return readings.recent(asset.getTenantId(), asset.getSiteId(), equipmentId, 20);
    }

    /** 1.校验读数；2.锁定授权和设备；3.比较最新读数；4.登记或返回既有记录。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EquipmentRuntimeReadingVO record(Long equipmentId, BigDecimal hours) {
        // 校验累计小时的范围和精度，覆盖通过服务调用的入口。
        if (hours == null || hours.signum() < 0 || hours.scale() > 2
                || hours.compareTo(new BigDecimal("1000000000000")) >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "运行小时须为非负数，最多12位整数和2位小数");
        }
        // 使用已授权设备的归属和当前账号，忽略任何客户端身份信息。
        var asset = equipment.getEquipmentDetail(equipmentId);
        Long userId = access.requireEnabledUserId();
        // 先锁定授权，再锁设备，与厂区管理采用相同的前置锁顺序。
        siteWriteAccess.lockSite(asset.getSiteId());
        if (readings.lockEquipment(asset.getTenantId(), asset.getSiteId(), equipmentId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在");
        }
        // 在设备行锁内读取已提交的最新读数，避免并发写入造成读数倒退。
        var recent = readings.recent(asset.getTenantId(), asset.getSiteId(), equipmentId, 1);
        if (!recent.isEmpty()) {
            var previous = recent.get(0);
            int comparison = hours.compareTo(previous.getHours());
            if (comparison < 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "累计运行小时不能小于最新读数");
            }
            if (comparison == 0) {
                return previous;
            }
        }
        // 保存可信录入人和服务端时间，统一返回两位小数。
        var reading = new EquipmentRuntimeReadingVO();
        reading.setHours(hours.setScale(2));
        reading.setRecordedBy(userId);
        reading.setRecordedAt(System.currentTimeMillis());
        if (readings.insert(asset.getTenantId(), asset.getSiteId(), equipmentId, reading) != 1 || reading.getId() == null) {
            throw new IllegalStateException("运行小时读数保存失败");
        }
        return reading;
    }
}
