package com.operations.mapper;
import com.equipops.domain.entity.*;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.*;
@Mapper
public interface AlertProgressMapper {
    // 优先选择最久未成功扫描的规则，每次调用只取第一页。
    IPage<AlertRuleEntity> selectRules(Page<AlertRuleEntity> page);
    // 读取用于准备当前批次的续扫快照。
    AlertScanProgressEntity selectProgress(@Param("tenantId") Long tenantId,@Param("ruleId") Long ruleId);
    // 在规则锁内复核续扫版本。
    AlertScanProgressEntity lockProgress(@Param("tenantId") Long tenantId,@Param("ruleId") Long ruleId);
    // 第一次扫描时建立续扫记录。
    int insertProgress(@Param("progress") AlertScanProgressEntity progress);
    // 与证据、事件和通知同事务推进续扫记录。
    int saveProgress(@Param("progress") AlertScanProgressEntity progress,@Param("expectedVersion") Long expectedVersion);
}
