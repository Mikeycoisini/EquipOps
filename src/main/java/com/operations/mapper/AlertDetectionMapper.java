package com.operations.mapper;
import com.equipops.domain.entity.*;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;
/** 检测事务专用SQL，调用方必须先锁定企业内的规则行。 */
@Mapper
public interface AlertDetectionMapper {
    // 锁定并复核一条候选失败任务。
    InventoryExportTaskEntity selectFailedTaskForUpdate(@Param("tenantId") Long tenantId,@Param("taskId") Long taskId,@Param("start") LocalDateTime start,@Param("end") LocalDateTime end);
    // 当前读查询已有证据，避免重复累计。
    AlertEventSourceEntity selectSourceForUpdate(@Param("tenantId") Long tenantId,@Param("ruleId") Long ruleId,@Param("taskId") Long taskId);
    // 插入尚未归属事件的失败证据。
    int insertSource(@Param("tenantId") Long tenantId,@Param("ruleId") Long ruleId,@Param("task") InventoryExportTaskEntity task);
    // 查询本窗口内、同一故障类型的未归属证据。
    List<AlertEventSourceEntity> selectPendingSources(@Param("tenantId") Long tenantId,@Param("ruleId") Long ruleId,@Param("code") String code,@Param("start") LocalDateTime start,@Param("end") LocalDateTime end);
    // 锁定当前同类活动事件。
    AlertEventEntity selectActiveEvent(@Param("tenantId") Long tenantId,@Param("ruleId") Long ruleId,@Param("code") String code);
    // 创建活动事件，回填event.id。
    int insertEvent(@Param("event") AlertEventEntity event);
    // 认领一条未归属证据，只有实际更新一行才允许计数。
    int attachSource(@Param("tenantId") Long tenantId,@Param("ruleId") Long ruleId,@Param("sourceId") Long sourceId,@Param("eventId") Long eventId);
    // 条件更新累计值和版本，新故障清除恢复标记。
    int updateEvent(@Param("event") AlertEventEntity event,@Param("expectedVersion") Long expectedVersion);
    // 查询当前接收人最近一次已投递通知。
    AlertNotificationEntity selectLastNotification(@Param("tenantId") Long tenantId,@Param("eventId") Long eventId,@Param("recipientId") Long recipientId);
    // 当前读锁定仍具资格的管理员关联记录，供通知落库前复核。
    Long selectRecipientForShare(@Param("tenantId") Long tenantId,@Param("recipientId") Long recipientId);
    // 按稳定投递键查询通知是否已经存在。
    Long selectNotificationId(@Param("tenantId") Long tenantId,@Param("deliveryKey") String deliveryKey);
    // 持久化已投递站内通知，回填notification.id。
    int insertNotification(@Param("notification") AlertNotificationEntity notification);
}
