package com.operations.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.equipops.domain.entity.AlertRuleEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface AlertRuleMapper extends BaseMapper<AlertRuleEntity> {
    // 从数据库读取当前时间，保留微秒精度。
    @Select("SELECT CURRENT_TIMESTAMP(6)")
    LocalDateTime selectDatabaseNow();
    // 按企业和规则ID锁定规则，锁在当前事务结束时释放。
    @Select("""
            SELECT *
            FROM alert_rules
            WHERE tenant_id = #{tenantId}
              AND id = #{ruleId}
            FOR UPDATE
            """)
    // 设置这条查询的执行超时时间为10秒。
    // 检查负责人是否仍是指定企业的有效管理员。

    @Options(timeout = 10)
    AlertRuleEntity selectRuleForUpdate(
            @Param("tenantId") Long tenantId,
            @Param("ruleId") Long ruleId);
}