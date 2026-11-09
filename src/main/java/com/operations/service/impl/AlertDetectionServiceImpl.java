package com.operations.service.impl;

import com.auth.mapper.AuthRoleMapper;
import com.operations.mapper.AlertProgressMapper;
import com.equipops.domain.entity.AlertScanProgressEntity;
import java.util.concurrent.CancellationException;
import org.springframework.dao.DuplicateKeyException;
import java.util.TreeSet;
import java.util.Set;
import com.equipops.domain.enums.AlertRecoveryStatus;
import com.equipops.domain.enums.AlertEventStatus;
import com.equipops.domain.entity.AlertNotificationEntity;
import com.equipops.domain.entity.AlertEventSourceEntity;
import com.equipops.domain.entity.AlertEventEntity;
import com.operations.mapper.AlertDetectionMapper;
import com.auth.model.AuthSessionAttributes;
import com.equipops.domain.entity.TenantMembershipEntity;
import com.equipops.domain.entity.UserEntity;
import com.tenant.mapper.TenantMembershipMapper;
import com.workorder.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipops.domain.entity.AlertRuleEntity;
import com.equipops.domain.entity.InventoryExportTaskEntity;
import com.equipops.domain.entity.TenantEntity;
import com.equipops.domain.enums.AlertRuleCode;
import com.inventory.mapper.InventoryExportTaskMapper;
import com.operations.mapper.AlertRuleMapper;
import com.operations.service.AlertDetectionService;
import com.tenant.mapper.TenantMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

// 注册有界批次告警检测业务，由显式启用的调度器调用。
@Service
public class AlertDetectionServiceImpl implements AlertDetectionService {
    // 创建当前类的日志对象，用于记录检测过程中的安全提示。
    private static final Logger safeLog = LoggerFactory.getLogger(AlertDetectionServiceImpl.class);
    // 注入告警规则的数据访问接口。
    @Autowired
    private AlertRuleMapper alertRuleMapper;
    // 注入证据去重、事件归并及站内提醒的事务数据访问接口。
    @Autowired
    private AlertDetectionMapper alertDetectionMapper;
    // 保存每条规则已提交的任务翻页位置。
    @Autowired
    private AlertProgressMapper alertProgressMapper;
    // 企业成员角色查询接口。
    @Autowired
    private AuthRoleMapper roleMapper;
    // 企业查询接口。
    @Autowired
    private TenantMapper tenantMapper;
    // 标记由Spring注入负责人账号查询接口。
    @Autowired
    private UserMapper userMapper;
    // 标记由Spring注入企业成员关系查询接口。
    @Autowired
    private TenantMembershipMapper membershipMapper;
    @Autowired
    private InventoryExportTaskMapper inventoryExportTaskMapper;//调用方负责在同一短事务内先锁成员再检查、插入
    // 标记由Spring注入事务模板，用于管理每一批任务的事务。
    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * 一次导出任务故障检测的完整执行流程。
     * 当前状态：规则扫描及批次处理已有实现，自动调度与独立恢复检测已有各自入口。
     * 本方法不负责人工确认/结案，也不把“没有新失败”直接判为系统恢复。
     *
     */
    /**
     * 用户导出 Excel → 导出任务发生系统故障 → 后台检测失败记录 → 达到规则阈值 → 生成告警 → 通知负责人 → 管理员处理。
     */
    // 执行一次有界规则扫描，并按批次事务保存告警。
    @Override
    public void detectExportFailures() {
        // 一. 分页读取alert_rules中enabled=true且rule_code=EXPORT_TASK_SYSTEM_FAILURE的规则。
        /**  优先读取最久未扫描且负责人有效的规则，再按规则ID排序，每轮最多一页；没有可执行规则则结束。*/
        int count = 1;
        long pageSize = 100;
        // 本次方法调用最多查询100批失败任务。
        int maxBatches = 100;
        // 记录本次方法调用已经完成的任务查询批次数。
        int FinishCount = 0;//初始化
        while (true) {
            Page<AlertRuleEntity> page = new Page<>(count, pageSize);
            // 1.创建告警规则的查询条件.wrapper告诉数据库查什么条件、按什么排序
            LambdaQueryWrapper<AlertRuleEntity> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(AlertRuleEntity::getEnabled, true);
            wrapper.eq(AlertRuleEntity::getRuleCode, AlertRuleCode.EXPORT_TASK_SYSTEM_FAILURE);// 只查询“导出任务系统性失败”这一种规则。
            wrapper.orderByAsc(AlertRuleEntity::getId);// 按规则ID从小到大排列，使分页顺序稳定。



            com.baomidou.mybatisplus.core.metadata.IPage<AlertRuleEntity> rulePage = alertProgressMapper.selectRules(page);
            //2. 取出这一页实际查到的规则列表。
            List<AlertRuleEntity> rules = rulePage.getRecords();//：从分页对象里把“这一页真正查到的规则行”拎出来。查不到时返回空列表
            // 3.当前页没有规则时，结束翻页循环。
            if (rules.isEmpty()) {
                break;
            }
            //4. 逐条处理当前页查询到的规则。
            for (AlertRuleEntity rule : rules) {
                // 二. 逐条取得规则中可信的tenantId、ruleId、负责人和阈值/窗口/冷却配置。
                //    不从请求参数、Session或线程中的旧身份取企业；发现无效配置时记录安全错误并跳过该规则。
                Long tenantId = rule.getTenantId();
                // 1.取得当前规则的ID，后面用于关联告警事件和去重证据。
                Long ruleId = rule.getId();
                //2. 取得规则负责人的用户ID，后面检查其资格并生成站内提醒。
                Long ownerUserId = rule.getOwnerUserId();
                // 3.取得故障触发次数阈值。
                Integer thresholdCount = rule.getThresholdCount();
                // 4.取得故障统计窗口的秒数。
                Integer windowS = rule.getWindowSeconds();

                // 5.取得重复通知的冷却秒数。
                Integer cooldownSeconds = rule.getCooldownSeconds();
                if (tenantId == null || tenantId <= 0
                        || ruleId == null || ruleId <= 0
                        || ownerUserId == null || ownerUserId <= 0
                        || thresholdCount == null || thresholdCount <= 0
                        || windowS == null || windowS <= 0
                        || cooldownSeconds == null || cooldownSeconds < 0) {
                    safeLog.warn("跳过规则：配置无效, tenantId={}, ruleId={}, code=INVALID_RULE_CONFIG",
                            tenantId, ruleId);
                    continue;
                }
                // 三. 检查规则企业仍启用、负责人账号/成员关系仍有效且仍有本企业管理员权限。
                //    企业失效则跳过；负责人失效不能向其发送或暴露告警，按后续明确的规则停用/处置策略处理。
                // 根据规则中的企业ID查询企业最新状态。
                TenantEntity tenant = tenantMapper.selectById(tenantId);
                if(tenant ==null ||!Boolean.TRUE.equals(tenant.getEnabled())){
                    // 仅记录定位信息，不输出企业业务资料。
                    safeLog.warn("跳过规则：企业不可用, tenantId={}, ruleId={}", tenantId, ruleId);
                    continue;
                }
                // 四. 从数据库取得本轮观测结束时间，按规则windowSeconds计算窗口开始时间。
                //    固定本规则本轮窗口，避免翻页时窗口不断漂移；窗口边界约定和扫描批量在实现前明确。

                // 1.从数据库取得当前规则本轮检测的结束时间。
                LocalDateTime windowEndtime = alertRuleMapper.selectDatabaseNow();
                // 2.检查是否成功取得数据库时间。
                if (windowEndtime == null) {
                    throw new IllegalStateException("未取得数据库观测时间");
                }
                // 3.从结束时间向前减去规则规定的秒数，得到窗口开始时间。
                LocalDateTime windowStartTime = windowEndtime.minusSeconds(windowS);



                // 五. 准备当前规则失败任务的查询范围与翻页位置。
                //    使用第四步固定的时间窗口，查询条件限定本企业、FAILED和系统故障白名单。
                // 读取上一轮已成功提交的续扫记录。
                AlertScanProgressEntity scanProgress = alertProgressMapper.selectProgress(tenantId, ruleId);
                // 只有未完成且规则版本一致时才复用旧窗口和位置。
                boolean resuming = scanProgress != null && !Boolean.TRUE.equals(scanProgress.getCompleted())
                        && Objects.equals(scanProgress.getRuleVersion(), rule.getVersion());
                // 固定本轮实际扫描窗口开始时间。
                LocalDateTime scanWindowStart = resuming ? scanProgress.getWindowStart() : windowStartTime;
                // 固定本轮实际扫描窗口结束时间。
                LocalDateTime scanWindowEnd = resuming ? scanProgress.getWindowEnd() : windowEndtime;
                // 每条规则本次最多处理三批，然后让其他规则取得执行机会。
                int ruleBatches = 0;
                // 保存续扫记录的预期版本，提交后更新。
                Long progressVersion = scanProgress == null ? null : scanProgress.getVersion();
                // 未完成窗口从上次已提交位置继续。
                LocalDateTime lastFinishedAt = resuming ? scanProgress.getLastFinishedAt() : null;
                // 新窗口从任务起点开始，重复扫描由证据唯一键去重。
                Long lastTaskId = resuming ? scanProgress.getLastTaskId() : 0L;
                // 六. 开始逐批扫描，每批先检查停止条件，再读取任务并累计查询批次数。
                //    中断或达到本轮额度时结束方法；当前规则无任务时退出内层循环，继续下一条规则。
                while (true) {
                    // 当前规则达到本次额度时，保留数据库位置并转向其他规则。
                    if (ruleBatches >= 3) {
                        // 结束当前规则的批次循环。
                        break;
                    }
                    // 检查执行当前方法的线程是否已收到中断请求。
                    if (Thread.currentThread().isInterrupted()) {
                        // 记录停止原因和当前处理位置。
                        safeLog.info("停止告警检测，线程已中断，tenantId={}, ruleId={}", tenantId, ruleId);
                        // 结束整个检测方法，并保留线程的中断标记。
                        return;
                    }
                    // 检查本轮任务查询批次数是否已经达到上限。
                    if (FinishCount >= maxBatches) {
                        // 记录本轮已使用的查询批次数。
                        safeLog.info("停止告警检测，达到批次上限，queriedBatches={}", FinishCount);
                        // 结束整个检测方法，停止继续读取其他批次。
                        return;
                    }
                    //六。开始逐批扫描，每批先检查停止条件，再读取任务并累计查询批次数。
                    // 停止条件检查通过后，按固定窗口和当前位置读取本批任务。
                    List<InventoryExportTaskEntity> failedTasks = inventoryExportTaskMapper.selectFailedTaskBatch(
                            tenantId, scanWindowStart, scanWindowEnd, lastFinishedAt, lastTaskId);
                    // 本批查询正常返回后累计次数，空结果也计入本轮查询额度。
                    FinishCount++;
                    // 为事务回调固定当前续扫版本和查询位置。
                    Long expectedProgressVersion = progressVersion;
                    // 保存本批读取之前的位置供空批次提交使用。
                    LocalDateTime batchPreviousTime = lastFinishedAt;
                    // 保存本批读取之前的任务ID。
                    Long batchPreviousId = lastTaskId;

                    // 七. 为当前规则的一批结果开启短事务，先按tenantId+ruleId锁定规则行。
                    /**
                     * 为当前这一批失败任务开启短事务，锁住对应规则，并重新确认这批任务仍然可以按这条规则处理。
                     * -----------------------------------------------------------
                     * 事务内重查规则启用状态、配置、企业与负责人资格；已变更时退出或重新计算，不能使用旧规则盲写。
                     *  两实例处理相同规则必须按同一顺序取锁，不持有贯穿整轮扫描的长事务。
                     */
                    //7.1 开启当前批次的事务
                    // 接住本批事务结果，供事务结束后决定是否继续当前规则。
                    Boolean batchCompleted = transactionTemplate.execute(status -> {
                        //7.2按 tenantId + ruleId 查询并锁定规则
                        AlertRuleEntity lockedRule = alertRuleMapper.selectRuleForUpdate(tenantId, ruleId);// 检查负责人是否仍是指定企业的有效管理员。
                        // 规则已不存在时，结束当前批次的事务回调。
                        if (lockedRule == null) {
                            // 记录规则缺失的定位信息。
                            safeLog.warn("停止处理规则，规则不存在，tenantId={}, ruleId={}", tenantId, ruleId);
                            return false;
                        }
                        //7.3重新检查检查锁定后的规则是否启用、类型是否正确
                        if(!Boolean.TRUE.equals(lockedRule.getEnabled()) || lockedRule.getRuleCode() != AlertRuleCode.EXPORT_TASK_SYSTEM_FAILURE){
                            // 记录规则当前已经不可处理。
                            safeLog.info("停止处理规则，规则已停用或类型变化，tenantId={}, ruleId={}", tenantId, ruleId);
                            // 结束当前批次的事务回调。
                            return false;
                        }
                        // 7.4 比较最新配置与本轮扫描使用的配置，任一项变化都停止当前规则。
                        if (!Objects.equals(lockedRule.getOwnerUserId(), ownerUserId)
                                || !Objects.equals(lockedRule.getThresholdCount(), thresholdCount)
                                || !Objects.equals(lockedRule.getWindowSeconds(), windowS)
                                || !Objects.equals(lockedRule.getCooldownSeconds(), cooldownSeconds)
                                || !Objects.equals(lockedRule.getVersion(), rule.getVersion())) {
                            // 记录配置已经变化，后续重新读取规则时再建立新窗口。
                            safeLog.info("停止处理规则，配置已变化，tenantId={}, ruleId={}", tenantId, ruleId);
                            // 结束当前批次的事务回调。
                            return false;
                        }
                        // 在规则锁之后锁定续扫记录，检查其他实例是否已处理该位置。
                        AlertScanProgressEntity lockedProgress = alertProgressMapper.lockProgress(tenantId, ruleId);
                        // 版本变化时放弃当前快照，由下一轮重新取得位置。
                        if ((lockedProgress == null && expectedProgressVersion != null)
                                || (lockedProgress != null && !Objects.equals(lockedProgress.getVersion(), expectedProgressVersion))) {
                            // 停止当前规则的旧批次。
                            return false;
                        }
                        // 首次扫描初始化续扫记录，和后续业务在同一事务提交。
                        if (lockedProgress == null) {
                            // 创建记录实体。
                            lockedProgress = new AlertScanProgressEntity();
                            // 设置可信企业。
                            lockedProgress.setTenantId(tenantId);
                            // 设置规则归属。
                            lockedProgress.setRuleId(ruleId);
                            // 保存规则版本。
                            lockedProgress.setRuleVersion(rule.getVersion());
                            // 保存固定窗口开始。
                            lockedProgress.setWindowStart(scanWindowStart);
                            // 保存固定窗口结束。
                            lockedProgress.setWindowEnd(scanWindowEnd);
                            // 初始并发版本为零。
                            lockedProgress.setVersion(0L);
                            // 插入失败触发整批回滚。
                            if (alertProgressMapper.insertProgress(lockedProgress) != 1) {
                                // 阻止继续处理无有效续扫记录的批次。
                                throw new IllegalStateException("续扫记录未创建");
                            }
                        }
                        // 7.5.1 在本批事务内重新读取企业状态。
                        TenantEntity batchTenant = tenantMapper.selectById(tenantId);
                        // 企业不存在或未启用时，停止处理当前规则。
                        if (batchTenant == null || !Boolean.TRUE.equals(batchTenant.getEnabled())) {
                            // 记录企业资格失效的定位信息。
                            safeLog.warn("停止处理规则，企业不可用，tenantId={}, ruleId={}", tenantId, ruleId);
                            // 返回本批无法继续处理，交给事务外代码退出当前规则。
                            return false;
                        }
                        // 7.5.2 根据锁定规则的负责人ID重新读取账号状态。
                        UserEntity batchOwner = userMapper.selectById(lockedRule.getOwnerUserId());
                        // 负责人账号不存在或未启用时，停止处理当前规则。
                        if (batchOwner == null || !Boolean.TRUE.equals(batchOwner.getEnabled())) {
                            // 记录负责人账号失效的定位信息。
                            safeLog.warn("停止处理规则，负责人账号不可用，tenantId={}, ruleId={}", tenantId, ruleId);
                            // 返回本批无法继续处理。
                            return false;
                        }
                        // 7.5.3 创建负责人有效成员关系的查询条件。
                        LambdaQueryWrapper<TenantMembershipEntity> memberWrapper = new LambdaQueryWrapper<>();
                        // 限定为当前规则所属企业。
                        memberWrapper.eq(TenantMembershipEntity::getTenantId, tenantId);
                        // 限定为锁定规则中记录的负责人。
                        memberWrapper.eq(TenantMembershipEntity::getUserId, lockedRule.getOwnerUserId());
                        // 只允许启用的成员关系。
                        memberWrapper.eq(TenantMembershipEntity::getEnabled, true);
                        // 查询负责人在指定企业中的有效成员关系。
                        TenantMembershipEntity batchMember = membershipMapper.selectOne(memberWrapper);
                        // 有效成员关系不存在时，停止处理当前规则。
                        if (batchMember == null) {
                            // 记录成员资格失效的定位信息。
                            safeLog.warn("停止处理规则，负责人成员关系无效，tenantId={}, ruleId={}", tenantId, ruleId);
                            return false;
                        }
                        // 7.5.4 使用企业ID和成员关系ID，读取该成员最新的企业角色。
                        List<String> batchRoles = roleMapper.selectRoleCodes(tenantId, batchMember.getId());
                        // 负责人不再持有本企业管理员角色时，停止处理当前规则。
                        if (!batchRoles.contains(AuthSessionAttributes.TENANT_ADMIN_ROLE)) {
                            // 记录管理员资格失效的定位信息。
                            safeLog.warn("停止处理规则，负责人无管理员权限，tenantId={}, ruleId={}", tenantId, ruleId);
                            return false;
                        }
                        // 八. 事务内重新核对任务，将新失败保存为未归属证据，旧证据保留原归属。
                        // 8.1 用排序集合收集本批涉及的故障类型，使各次处理顺序一致。
                        Set<String> errorCodes = new TreeSet<>();
                        // 8.2 逐条读取当前批次候选任务。
                        for (InventoryExportTaskEntity candidateTask : failedTasks) {
                            // 批内收到中断时抛出异常，让当前事务全部回滚。
                            if (Thread.currentThread().isInterrupted()) {
                                // 向外传播中断停止原因并保留中断标记。
                                throw new CancellationException("告警批次检测已中断");
                            }
                            // 重新锁定符合企业、失败状态、窗口和错误白名单的任务。
                            InventoryExportTaskEntity currentTask = alertDetectionMapper.selectFailedTaskForUpdate(
                                    tenantId, candidateTask.getId(), scanWindowStart, scanWindowEnd);
                            // 候选任务已不符合检测条件时跳过该任务。
                            if (currentTask == null) {
                                // 继续检查当前批次下一条任务。
                                continue;
                            }
                            // 收集故障类型，已有证据也可推动未归属证据归并和冷却后提醒。
                            errorCodes.add(currentTask.getErrorCode());
                            // 按企业、规则和任务ID读取已存在的证据。
                            AlertEventSourceEntity existingSource = alertDetectionMapper.selectSourceForUpdate(
                                    tenantId, ruleId, currentTask.getId());
                            // 已有证据直接保留，避免重复计数或更改原事件归属。
                            if (existingSource != null) {
                                // 继续处理下一条任务。
                                continue;
                            }
                            // 尝试插入一条尚未归属事件的证据。
                            try {
                                // 必须实际插入一条记录，否则抛出异常回滚本批。
                                if (alertDetectionMapper.insertSource(tenantId, ruleId, currentTask) != 1) {
                                    // 不把异常写入结果作为成功继续处理。
                                    throw new IllegalStateException("失败任务证据未保存");
                                }
                            // 仅在唯一键冲突后核实同一任务证据确实已经存在。
                            } catch (DuplicateKeyException duplicate) {
                                // 使用当前读确认冲突对应的证据记录。
                                AlertEventSourceEntity concurrentSource = alertDetectionMapper.selectSourceForUpdate(
                                        tenantId, ruleId, currentTask.getId());
                                // 没有找到同一任务证据时，保留原异常并回滚。
                                if (concurrentSource == null) {
                                    // 其他唯一键错误不得静默忽略。
                                    throw duplicate;
                                }
                            }
                        }

                        // 九. 按企业、规则和故障类型读取活动事件及本窗口内尚未归属的证据。
                        // 9.1 读取数据库观测时间，供事件时间与通知冷却统一使用。
                        LocalDateTime observedAt = alertRuleMapper.selectDatabaseNow();
                        // 数据库时间缺失时回滚本批证据写入。
                        if (observedAt == null) {
                            // 抛出异常交给事务模板处理回滚。
                            throw new IllegalStateException("未取得告警观测时间");
                        }
                        // 9.2 逐类处理本批涉及的系统故障。
                        for (String errorCode : errorCodes) {
                            // 在规则锁内读取同一故障类型的活动告警。
                            AlertEventEntity event = alertDetectionMapper.selectActiveEvent(tenantId, ruleId, errorCode);
                            // 读取当前固定窗口中尚未归属事件的证据，排除已归属旧事件的记录。
                            List<AlertEventSourceEntity> pendingSources = alertDetectionMapper.selectPendingSources(
                                    tenantId, ruleId, errorCode, scanWindowStart, scanWindowEnd);
                            // 首次告警必须达到本窗口内去重证据数量阈值。
                            if (event == null && pendingSources.size() < thresholdCount) {
                                // 保留证据等待后续批次，继续下一类故障。
                                continue;
                            }

                            // 十. 同类活动事件不存在且达到阈值时，创建待处理事件。
                            // 10.1 创建事件需要至少一条未归属证据，上一条件已保证该前提。
                            if (event == null) {
                                // 使用普通实体和set方法准备事件。
                                event = new AlertEventEntity();
                                // 保存可信企业归属。
                                event.setTenantId(tenantId);
                                // 保存当前规则归属。
                                event.setRuleId(ruleId);
                                // 使用受控ID和白名单错误码生成稳定指纹。
                                event.setFingerprint(tenantId + ":" + ruleId + ":" + errorCode);
                                // 保存本事件的系统故障类型。
                                event.setErrorCode(errorCode);
                                // 新事件进入待处理状态。
                                event.setStatus(AlertEventStatus.OPEN);
                                // 新事件的恢复状态等待后续独立检测确认。
                                event.setRecoveryStatus(AlertRecoveryStatus.UNVERIFIED);
                                // 标记本事件占用活动告警唯一槽。
                                event.setOpenSlot(1);
                                // 初始累计值为零，证据认领成功后再增加。
                                event.setOccurrenceCount(0L);
                                // 保存按任务完成时间排序的首条证据任务ID。
                                event.setFirstTaskId(pendingSources.get(0).getTaskId());
                                // 首次创建时先以首条任务初始化最近来源。
                                event.setLatestTaskId(pendingSources.get(0).getTaskId());
                                // 保存锁定规则中的负责人。
                                event.setOwnerUserId(lockedRule.getOwnerUserId());
                                // 首次观测时间使用数据库时间。
                                event.setFirstSeenAt(observedAt);
                                // 初始化最近一次新故障观测时间。
                                event.setLastSeenAt(observedAt);
                                // 初始化并发更新版本。
                                event.setVersion(0L);
                                // 插入事件并检查生成主键。
                                try {
                                    // 必须实际插入一行并回填事件ID。
                                    if (alertDetectionMapper.insertEvent(event) != 1 || event.getId() == null) {
                                        // 事件创建异常时回滚本批。
                                        throw new IllegalStateException("告警事件未创建");
                                    }
                                // 唯一槽发生竞争时读取同企业同规则同类型的活动事件。
                                } catch (DuplicateKeyException duplicate) {
                                    // 重新取得已存在的活动事件。
                                    event = alertDetectionMapper.selectActiveEvent(tenantId, ruleId, errorCode);
                                    // 未找到预期活动事件时保留原异常。
                                    if (event == null) {
                                        // 其他唯一键冲突不得作为成功忽略。
                                        throw duplicate;
                                    }
                                }
                            }

                            // 十一. 只认领未归属证据，按实际认领数量增加累计值和版本。
                            // 11.1 没有新证据且负责人未变化时，保持事件计数及恢复状态。
                            if (!pendingSources.isEmpty() || !Objects.equals(event.getOwnerUserId(), lockedRule.getOwnerUserId())) {
                                // 保存更新前版本供SQL条件比较。
                                Long expectedVersion = event.getVersion();
                                // 逐条认领尚未归属的证据。
                                for (AlertEventSourceEntity source : pendingSources) {
                                    // 仅允许同企业、同规则且仍未归属的证据更新成功。
                                    if (alertDetectionMapper.attachSource(tenantId, ruleId, source.getId(), event.getId()) != 1) {
                                        // 认领数量不一致时回滚，避免计数与证据脱节。
                                        throw new IllegalStateException("告警证据归属发生变化");
                                    }
                                }
                                // 根据实际成功认领的证据数量累计，溢出时由异常触发回滚。
                                event.setOccurrenceCount(Math.addExact(event.getOccurrenceCount(), (long) pendingSources.size()));
                                // 同步锁定规则中的最新负责人。
                                event.setOwnerUserId(lockedRule.getOwnerUserId());
                                // 有新证据时更新最近来源和恢复标记。
                                if (!pendingSources.isEmpty()) {
                                    // 保存新认领证据中最后一条的来源任务ID。
                                    event.setLatestTaskId(pendingSources.get(pendingSources.size() - 1).getTaskId());
                                    // 保存本轮发现新故障的数据库时间。
                                    event.setLastSeenAt(observedAt);
                                    // 新故障使恢复状态重新等待检测。
                                    event.setRecoveryStatus(AlertRecoveryStatus.UNVERIFIED);
                                    // 同步清空原恢复时间以满足数据库约束。
                                    event.setRecoveredAt(null);
                                }
                                // 按活动状态和原版本更新事件，任何竞争都回滚本批。
                                if (alertDetectionMapper.updateEvent(event, expectedVersion) != 1) {
                                    // 抛出异常阻止半成功提交。
                                    throw new IllegalStateException("告警事件状态或版本已变化");
                                }
                                // 同步内存版本，供本轮通知幂等键使用。
                                event.setVersion(Math.addExact(expectedVersion, 1L));
                            }

                            // 十二. 按负责人资格、冷却时间和稳定投递键生成站内提醒。
                            // 12.1 已检测恢复的活动事件等待独立处置流程，当前不继续发送故障提醒。
                            if (event.getRecoveryStatus() == AlertRecoveryStatus.RECOVERED) {
                                // 继续处理下一类故障。
                                continue;
                            }
                            // 查询该接收者上一次已生成的站内提醒。
                            AlertNotificationEntity previous = alertDetectionMapper.selectLastNotification(
                                    tenantId, event.getId(), lockedRule.getOwnerUserId());
                            // 上次投递时间加冷却秒数仍晚于当前数据库时间时，本轮跳过提醒。
                            if (previous != null && previous.getDeliveredAt().plusSeconds(cooldownSeconds).isAfter(observedAt)) {
                                // 保留已更新事件与证据，等待冷却结束。
                                continue;
                            }
                            // 由事件、接收者和事件版本组成稳定键，同一版本重扫不生成第二条提醒。
                            String deliveryKey = event.getId() + ":" + lockedRule.getOwnerUserId() + ":" + event.getVersion();
                            // 查询本版本的提醒是否已持久化。
                            if (alertDetectionMapper.selectNotificationId(tenantId, deliveryKey) != null) {
                                // 已有同版本提醒时继续下一类故障。
                                continue;
                            }
                            // 使用当前读重新锁定仍具管理员资格的接收者关联记录。
                            if (alertDetectionMapper.selectRecipientForShare(tenantId, lockedRule.getOwnerUserId()) == null) {
                                // 资格失效时回滚本批已写入的事件与证据。
                                status.setRollbackOnly();
                                // 退出回调，外层停止当前规则且保留查询位置。
                                return false;
                            }
                            // 创建首版站内提醒实体。
                            AlertNotificationEntity notification = new AlertNotificationEntity();
                            // 保存提醒所属企业。
                            notification.setTenantId(tenantId);
                            // 关联当前活动告警。
                            notification.setEventId(event.getId());
                            // 接收者使用锁定规则中的负责人。
                            notification.setRecipientUserId(lockedRule.getOwnerUserId());
                            // 保存稳定投递键，重试时复用该键。
                            notification.setDeliveryKey(deliveryKey);
                            // 使用固定安全标题。
                            notification.setTitle("导出任务系统故障告警");
                            // 摘要仅包含受控事件ID、故障编码和去重累计数。
                            notification.setMessage("告警" + event.getId() + "，故障类型" + errorCode + "，累计" + event.getOccurrenceCount() + "条失败任务");
                            // 记录站内提醒持久化的数据库时间。
                            notification.setDeliveredAt(observedAt);
                            // 插入一条已投递且未读的站内提醒，失败时回滚本批。
                            if (alertDetectionMapper.insertNotification(notification) != 1 || notification.getId() == null) {
                                // 抛出异常让事务模板处理回滚。
                                throw new IllegalStateException("告警站内提醒未保存");
                            }
                        }
                        // 十三. 与证据、事件和通知同事务推进续扫窗口及位置。
                        // 保存更新前的续扫版本。
                        Long savedVersion = lockedProgress.getVersion();
                        // 保存本轮使用的规则版本。
                        lockedProgress.setRuleVersion(rule.getVersion());
                        // 保存本轮固定窗口。
                        lockedProgress.setWindowStart(scanWindowStart);
                        // 保存本轮结束时间。
                        lockedProgress.setWindowEnd(scanWindowEnd);
                        // 空批次表示当前窗口已读取完。
                        lockedProgress.setCompleted(failedTasks.isEmpty());
                        // 空批次保持原位置。
                        lockedProgress.setLastFinishedAt(batchPreviousTime);
                        // 空批次保持原任务ID。
                        lockedProgress.setLastTaskId(batchPreviousId);
                        // 非空批次记录最后一条已成功处理的任务位置。
                        if (!failedTasks.isEmpty()) {
                            // 取得本批最后任务。
                            InventoryExportTaskEntity endTask = failedTasks.get(failedTasks.size() - 1);
                            // 保存对应完成时间。
                            lockedProgress.setLastFinishedAt(endTask.getFinishedAt());
                            // 保存对应任务ID。
                            lockedProgress.setLastTaskId(endTask.getId());
                        }
                        // 条件更新失败时回滚整个批次，不能只保存位置。
                        if (alertProgressMapper.saveProgress(lockedProgress, savedVersion) != 1) {
                            // 抛出异常保持位置与业务结果一致。
                            throw new IllegalStateException("续扫位置提交冲突");
                        }
                        // 允许事务模板统一提交本批。
                        return true;
                    });
                    // 事务结果为false或null时，停止当前规则，避免继续推进任务查询位置。
                    if (!Boolean.TRUE.equals(batchCompleted)) {
                        // 跳出任务批次循环，继续外层for中的下一条规则。
                        break;
                    }




                    // 十三. 同一短事务提交证据归属、事件及计数、通知记录；任一步失败全部回滚。
                    //     不提前标记扫描已完成，不手工跳过失败任务，不让事件计数与来源证据出现半成功。


                    // 提交成功后累计本规则本次已处理批次数。
                    ruleBatches++;
                    // 同步下一批需要的续扫版本。
                    progressVersion = expectedProgressVersion == null ? 1L : expectedProgressVersion + 1L;
                    // 当前窗口的空批次已持久化完成标记。
                    if (failedTasks.isEmpty()) {
                        // 继续外层下一条规则。
                        break;
                    }
                    // 取得当前批次按完成时间和ID排序后的最后一条任务。
                    InventoryExportTaskEntity lastTask = failedTasks.get(failedTasks.size() - 1);

                    // 保存最后一条任务的完成时间，下一批从这个位置继续查询。
                    lastFinishedAt = lastTask.getFinishedAt();

                    // 保存相同完成时间下的最后任务ID，避免重复读取当前批次。
                    lastTaskId = lastTask.getId();
                    // 十四. 本批事务成功提交后，更新lastFinishedAt和lastTaskId，继续下一批。
                    //       查询位置在事务提交成功后推进；本批失败时不得推进位置。
                    //       下一批回到第六步重新检查停止条件；无更多任务时进入下一条规则。

                }


            }
            // 当前页处理完成后结束本轮，避免续扫时间更新造成翻页偏移。
            // 本轮仅处理最久未扫描的一页规则，下一次根据持久化时间重新公平选取。
            break;
        }



        // 15. 结束本次检测，释放数据库和日志上下文等本方法实际取得的资源。
        //     日志只记录规则/企业标识、数量、耗时及安全错误码，不输出SQL、凭据或任务筛选正文。
        //     故障恢复检测、人工处置和通知已读有各自后续方法，不能在本方法末尾一律标记RECOVERED/RESOLVED。

        // 本轮扫描正常结束，记录实际执行的任务查询批次数。
        safeLog.info("告警检测结束，queriedBatches={}", FinishCount);
    }
}
