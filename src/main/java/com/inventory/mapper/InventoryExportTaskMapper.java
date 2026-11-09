package com.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.equipops.domain.dto.inventory.InventoryExportCreateDto;
import com.equipops.domain.entity.InventoryExportTaskEntity;
import com.equipops.domain.vo.InventoryExportTaskVO;
import com.equipops.domain.vo.InventoryTransactionVO;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 创建任务所需SQL；调用方负责在同一短事务内先锁成员再检查、插入。 */
@Mapper
public interface InventoryExportTaskMapper extends BaseMapper<InventoryExportTaskEntity> {
    /** 锁定本企业当前用户的有效成员关系；必须在事务内调用。 */
    Long selectEnabledMembershipForUpdate(@Param("tenantId") Long tenantId,
                                          @Param("userId") Long userId);

    /** 锁定读取最新活动任务，避免普通快照查询遗漏并发已提交任务。 */
    List<Long> selectActiveTaskIdsForUpdate(@Param("tenantId") Long tenantId,
                                           @Param("userId") Long userId);

    /** 保存QUEUED任务并回填task.id；初始租约使用数据库时间。 */
    int insertQueuedTask(
            @Param("task") InventoryExportTaskEntity task,
            @Param("request") InventoryExportCreateDto request,
            @Param("tenantId") Long tenantId,
            @Param("userId") Long userId
    );

    /** 三个条件共同限制本人任务，只返回公开VO。 */
    InventoryExportTaskVO selectOwnTask(@Param("tenantId") Long tenantId,
                                       @Param("userId") Long userId,
                                       @Param("taskId") Long taskId);

    /** 本企业本人任务分页；状态可选，返回公开VO，总数与记录使用相同范围。 */
    com.baomidou.mybatisplus.core.metadata.IPage<InventoryExportTaskVO> selectOwnTaskPage(
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<InventoryExportTaskVO> page,
            @Param("tenantId") Long tenantId, @Param("userId") Long userId,
            @Param("query") com.equipops.domain.dto.inventory.InventoryExportPageQueryDto query);

    /** 取消事务先锁成员再锁本人任务；使用当前读与worker的发布更新竞争。 */
    InventoryExportTaskVO selectOwnTaskForUpdate(@Param("tenantId") Long tenantId,
                                                @Param("userId") Long userId,
                                                @Param("taskId") Long taskId);

    /** 只推进本人QUEUED/RUNNING任务，SQL复核当前企业管理员和启用状态，不限定执行实例。 */
    int requestCancelOwnTask(@Param("tenantId") Long tenantId,
                             @Param("userId") Long userId,
                             @Param("taskId") Long taskId);

    boolean hasExportAccess(@Param("task") InventoryExportTaskEntity task);
    boolean isOwnedQueued(@Param("task") InventoryExportTaskEntity task);
    int startRunning(@Param("task") InventoryExportTaskEntity task);
    Long selectUpperBound(@Param("tenantId") Long tenantId);
    int saveUpperBound(@Param("task") InventoryExportTaskEntity task, @Param("upperBound") Long upperBound);
    boolean isRunningLive(@Param("task") InventoryExportTaskEntity task);
    List<InventoryTransactionVO> selectExportBatch(@Param("task") InventoryExportTaskEntity task,
                                                 @Param("lastId") Long lastId);
    int updateProgress(@Param("task") InventoryExportTaskEntity task, @Param("rows") long rows);
    int publishSuccess(@Param("task") InventoryExportTaskEntity task, @Param("storageKey") String storageKey,
                       @Param("fileName") String fileName, @Param("fileSize") long fileSize, @Param("rows") long rows);
    int rejectQueued(@Param("task") InventoryExportTaskEntity task);
    int finishUnsuccessful(@Param("task") InventoryExportTaskEntity task,
                           @Param("code") String code, @Param("message") String message);
    int renewOwnedLeases(@Param("task") InventoryExportTaskEntity task);
    int reapExpiredTasks();
    int expireFiles();
    boolean shouldKeepFiles(@Param("taskId") Long taskId, @Param("runToken") String runToken);

    // 分批读取指定企业、固定时间窗口内的系统性失败任务。
    List<InventoryExportTaskEntity> selectFailedTaskBatch(
            @Param("tenantId") Long tenantId,
            @Param("windowStart") LocalDateTime windowStart,
            @Param("windowEnd") LocalDateTime windowEnd,
            @Param("lastFinishedAt") LocalDateTime lastFinishedAt,
            @Param("lastTaskId") Long lastTaskId);

}
