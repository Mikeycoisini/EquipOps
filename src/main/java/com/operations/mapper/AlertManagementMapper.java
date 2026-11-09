package com.operations.mapper;
import com.equipops.domain.entity.*;
import com.equipops.domain.vo.*;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.*;
@Mapper
public interface AlertManagementMapper {
    // 查询企业的固定导出告警规则。
    AlertRuleEntity selectRule(@Param("tenantId") Long tenantId);
    // 管理员保存规则前先取得同一规则锁。
    AlertRuleEntity lockRule(@Param("tenantId") Long tenantId);
    // 创建规则并回填ID。
    int insertRule(@Param("rule") AlertRuleEntity rule);
    // 根据版本修改配置。
    int updateRule(@Param("rule") AlertRuleEntity rule,@Param("expectedVersion") Long expectedVersion);
    // 在规则锁之后读取活动或终态事件。
    AlertEventEntity lockEvent(@Param("tenantId") Long tenantId,@Param("id") Long id);
    // 按当前版本推进人工状态。
    int updateState(@Param("tenantId") Long tenantId,@Param("id") Long id,@Param("version") Long version,@Param("action") String action);
    // 保存时间线。
    int insertAction(@Param("action") AlertActionEntity action);
    // 查询该企业事件的时间线。
    List<AlertActionVO> selectActions(@Param("tenantId") Long tenantId,@Param("eventId") Long eventId);
    // 查询当前用户的站内提醒。
    IPage<AlertNotificationVO> selectNotifications(Page<AlertNotificationVO> page,@Param("tenantId") Long tenantId,@Param("userId") Long userId);
    // 查询可见通知主键。
    Long selectOwnNotification(@Param("tenantId") Long tenantId,@Param("userId") Long userId,@Param("id") Long id);
    // 幂等标记已读。
    int markRead(@Param("tenantId") Long tenantId,@Param("userId") Long userId,@Param("id") Long id);
    // 选取达到安静窗口的待复核事件。
    List<AlertEventEntity> recoveryCandidates();
    // 当前读检查窗口内是否仍存在同类失败。
    Long recentFailure(@Param("tenantId") Long tenantId,@Param("code") String code,@Param("start") LocalDateTime start);
    // 当前读寻找观测新故障之后的成功导出。
    Long laterSuccess(@Param("tenantId") Long tenantId,@Param("after") LocalDateTime after);
    // 条件标记恢复。
    int markRecovered(@Param("tenantId") Long tenantId,@Param("id") Long id,@Param("version") Long version);
}
