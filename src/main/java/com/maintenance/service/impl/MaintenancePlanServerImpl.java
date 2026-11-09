package com.maintenance.service.impl;

import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.maintenance.MaintenanceExecutionPageQueryDto;
import com.equipops.domain.dto.maintenance.MaintenanceExecutionCreateDto;
import com.equipops.domain.vo.MaintenanceExecutionVO;
import com.equipops.domain.entity.MaintenanceExecutionEntity;
import java.time.LocalDateTime;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import com.auth.model.TenantAccess;
import com.equipops.domain.dto.maintenance.MaintenancePlanUpdateDto;
import java.util.Objects;
import com.equipops.domain.dto.maintenance.MaintenancePlanEnabledUpdateDto;
import com.equipops.domain.dto.maintenance.MaintenancePlanPageQueryDto;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.util.StringUtils;
import com.equipops.domain.dto.maintenance.MaintenancePlanCreateDto;
import com.equipops.domain.vo.MaintenancePlanVO;
import com.equipops.domain.entity.EquipmentEntity;
import com.equipops.domain.entity.MaintenancePlanEntity;
import com.equipops.domain.entity.SiteEntity;
import com.maintenance.mapper.MaintenancePlanMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.transaction.annotation.Transactional;
import com.maintenance.service.MaintenancePlanServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;

/** 维护计划业务实现 */
@Service
public class MaintenancePlanServerImpl implements MaintenancePlanServer {
    @Autowired
    private CurrentAccessService currentAccessService;
    /** 维护计划及本次创建所需资源查询。 */
    @Autowired
    private MaintenancePlanMapper maintenancePlanMapper;

    /** 创建计划的业务入口 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public MaintenancePlanVO createPlan(MaintenancePlanCreateDto request) {
        //1. 获取当前登录用户和当前企业
        //   → 确认具有当前企业管理员权限
        Long TenantId = currentAccessService.requireTenantAdminTenantId();
        Long UserId = currentAccessService.requireEnabledUserId();
        //2. 检查首次到期日
        //   → 按上海时区判断，不能早于今天
        LocalDate now = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        LocalDate nextDueDate = request.getNextDueDate();
        if (nextDueDate == null || nextDueDate.isBefore(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "首次到期日不能为空或早于今天"
            );
        }
        //3. 检查设备
        //   → 必须属于当前企业
        //   → 设备所属厂区必须启用
        //   → 不要求设备一定处于运行状态
        EquipmentEntity equipment = maintenancePlanMapper.selectTenantEquipment(TenantId, request.getEquipmentId());
        if (equipment == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在");
        }
        SiteEntity site = maintenancePlanMapper.selectTenantSite(TenantId, equipment.getSiteId());
        if (site == null || !Boolean.TRUE.equals(site.getEnabled())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "设备所属厂区不存在或未启用");
        }

        //4. 检查负责人
        //   → 必须是当前企业的启用成员
        //   → 对应的全局账号也必须启用
        if (maintenancePlanMapper.countEnabledAssignee(TenantId, request.getAssigneeUserId()) != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "负责人必须是当前企业的启用成员且账号已启用");
        }

        //5. 组装计划实体
        //   → 复制请求中的业务字段
        //   → 填入当前企业、设备所属厂区、当前创建人
        //   → 默认启用
        MaintenancePlanEntity plan = new MaintenancePlanEntity();
        BeanUtils.copyProperties(request, plan);
        // DTO不接收这些归属字段，由可信上下文和已校验的设备填充。
        plan.setTenantId(TenantId);
        plan.setSiteId(equipment.getSiteId());
        plan.setCreatedBy(UserId);
        plan.setEnabled(true);

        //6. 插入计划
        //   → 检查是否成功写入一条，取得生成的计划ID
        int rows = maintenancePlanMapper.insertPlan(plan);
        if (rows != 1 || plan.getId() == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "维护计划保存失败");
        }

        //7. 按“当前企业ID＋计划ID”查询完整VO
        //   → 带上设备编号、设备名称和负责人姓名
        //   → 返回结果
        // 回查读取数据库生成的时间以及联表名称，不能只返回刚组装的Java对象。
        MaintenancePlanVO result = maintenancePlanMapper.selectPlanDetail(TenantId, plan.getId());
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "维护计划保存后查询失败");
        }
        return result;
    }
    /** 只读查询维护计划；读取权限与设备列表一致，不要求管理员角色。 */
    @Override
    @Transactional(readOnly = true)
    public IPage<MaintenancePlanVO> getPlanPage(MaintenancePlanPageQueryDto query) {
        //1. 校验登录、当前企业和有效成员关系，取得可信企业ID。
        Long tenantId = currentAccessService.requireActiveTenantId();

        //2. 整理查询条件；空白关键字不筛选，页码和条数沿用现有分页约定。
        if (query.getKeyword() != null && query.getKeyword().length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "关键字不能超过160个字符");
        }
        query.setKeyword(StringUtils.hasText(query.getKeyword()) ? query.getKeyword().trim() : null);
        long current = Math.max(1, query.getCurrent());
        long size = Math.max(1, Math.min(100, query.getSize()));

        //3. 提供设备筛选时，只允许使用本企业设备；查询历史计划不限制设备运行状态。
        Long equipmentId = query.getEquipmentId();
        if (equipmentId != null) {
            if (equipmentId <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备ID必须大于0");
            }
            if (maintenancePlanMapper.selectTenantEquipment(tenantId, equipmentId) == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在");
            }
        }

        //4. XML始终固定企业条件，再追加可选筛选；分页插件负责查询总数和当前页。
        //5. 返回真实分页，含设备及负责人名称；没有记录时保留空分页和总数0。
        if (Boolean.TRUE.equals(query.getDueOnly())) {
            // 由服务端按上海时区确定今天，不接收客户端指定待办截止日期。
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
            return maintenancePlanMapper.selectDuePlanPage(new Page<>(current, size), tenantId, query, today);
        }
        return maintenancePlanMapper.selectPlanPage(new Page<>(current, size), tenantId, query);
    }

    /** 查询本企业计划详情；启用与停用计划均可查看。 */
    @Override
    @Transactional(readOnly = true)
    public MaintenancePlanVO getPlanDetail(Long id) {
        //1. 校验当前登录用户及企业成员关系，取得可信的当前企业ID。
        Long tenantId = currentAccessService.requireActiveTenantId();

        //2. 计划主键必须是正数，避免无效输入进入数据库查询。
        if (id == null || id <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "维护计划ID必须大于0");
        }

        //3. 复用现有XML，按企业和主键查询，并带出设备及负责人名称。
        MaintenancePlanVO plan = maintenancePlanMapper.selectPlanDetail(tenantId, id);
        //4. 不区分不存在和其他企业的数据，避免泄露跨企业信息。
        if (plan == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "维护计划不存在");
        }
        //5. 返回完整详情，不额外限制计划或关联成员当前是否启用。
        return plan;
    }

    /** 修改维护计划启用状态；锁定目标行后判断，保证重复设置不误报失败。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePlanEnabled(Long id, MaintenancePlanEnabledUpdateDto request) {
        //1. 管理员权限和企业ID都来自可信上下文。
        Long tenantId = currentAccessService.requireTenantAdminTenantId();
        //2. 验证主键和目标状态；Boolean保留缺省与false的区别。
        if (id == null || id <= 0 || request == null || request.getEnabled() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "维护计划ID和启用状态无效");
        }
        //3. 只锁定本企业这一条计划，防止并发修改导致判断与更新不一致。
        Boolean current = maintenancePlanMapper.selectPlanEnabledForUpdate(tenantId, id);
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "维护计划不存在");
        }
        //4. 相同状态直接成功，不依赖MySQL对无变化更新返回的影响行数。
        if (current.equals(request.getEnabled())) {
            return;
        }
        //5. XML仅更新启用状态；数据库自动维护更新时间，其他计划字段保持原值。
        if (maintenancePlanMapper.updatePlanEnabled(tenantId, id, request.getEnabled()) != 1) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "维护计划启用状态修改失败");
        }
    }

    /** 修改计划；锁定、校验、定向更新及回查在同一事务内完成。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public MaintenancePlanVO updatePlan(Long id, MaintenancePlanUpdateDto request) {
        //1. 企业范围和管理员权限由服务端确定。
        Long tenantId = currentAccessService.requireTenantAdminTenantId();
        if (id == null || id <= 0 || request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "维护计划ID或请求无效");
        }
        //2. 当前读并锁定原计划，与启停和其他修改请求串行处理。
        MaintenancePlanVO original = maintenancePlanMapper.selectPlanDetailForUpdate(tenantId, id);
        if (original == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "维护计划不存在");
        }
        //3. DTO负责完整请求的必填、长度、整数校验；此处检查与原记录有关的日期规则。
        LocalDate nextDueDate = request.getNextDueDate();
        if (nextDueDate == null || (!nextDueDate.equals(original.getNextDueDate())
                && nextDueDate.isBefore(LocalDate.now(ZoneId.of("Asia/Shanghai"))))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新的下次到期日不能早于今天");
        }
        //4. 当前读并锁定资源，避免使用等待前的快照；设备限本企业，厂区须启用；换设备时厂区随新设备确定，不要求设备运行中。
        EquipmentEntity equipment = maintenancePlanMapper.selectTenantEquipmentForUpdate(tenantId, request.getEquipmentId());
        if (equipment == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在");
        }
        SiteEntity site = maintenancePlanMapper.selectTenantSiteForUpdate(tenantId, equipment.getSiteId());
        if (site == null || !Boolean.TRUE.equals(site.getEnabled())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "设备所属厂区不存在或未启用");
        }
        //5. 负责人仍须是本企业启用成员，且全局账号启用。
        if (maintenancePlanMapper.selectEnabledAssigneeForUpdate(tenantId, request.getAssigneeUserId()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "负责人必须是当前企业的启用成员且账号已启用");
        }
        //6. 没有变化直接跳过写入，避免MySQL无变化更新返回0时误报失败。
        // 同类型DTO的equals由@Data生成，只比较这次允许编辑的7个字段。
        MaintenancePlanUpdateDto originalRequest = new MaintenancePlanUpdateDto();
        BeanUtils.copyProperties(original, originalRequest);
        // 厂区不在请求DTO中，仍需检查是否跟随设备变更。
        boolean changed = !request.equals(originalRequest)
                || !Objects.equals(original.getSiteId(), equipment.getSiteId());
        if (changed && maintenancePlanMapper.updatePlan(tenantId, id, request, equipment.getSiteId()) != 1) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "维护计划修改失败");
        }
        //7. 使用当前读回查，避免无变化路径读取到事务早期快照；保留启用状态和创建资料。
        MaintenancePlanVO result = maintenancePlanMapper.selectPlanDetailForUpdate(tenantId, id);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "维护计划修改后查询失败");
        }
        return result;
    }

    /** 记录完成结果和推进日期同事务提交；按计划行锁串行化，读已提交避免旧快照。 */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MaintenanceExecutionVO createExecution(Long planId, String requestId, MaintenanceExecutionCreateDto request) {
        try {
            //1. 一次取得已验证的企业和操作人；有效成员身份不等于拥有执行权限。
            TenantAccess access = currentAccessService.requireTenantAccess();
            Long tenantId = access.getTenant().getId();
            Long userId = access.getUserId();
            if (planId == null || planId <= 0 || requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,64}")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "计划ID或请求号无效，请提供1到64位字母数字下划线短横线请求号");
            }
            if (request == null || !StringUtils.hasText(request.getResult()) || request.getResult().length() > 2000) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "执行结果不能为空且最多2000个字符");
            }
            String result = request.getResult().trim();

            //2. 按当前企业和计划ID锁定计划，避免与修改、停用和其他执行请求交错处理。
            MaintenancePlanVO plan = maintenancePlanMapper.selectPlanDetailForUpdate(tenantId, planId);
            if (plan == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "维护计划不存在");
            }
            // 独立当前读并刷新本次查询缓存，不能用可能命中早期缓存的普通身份检查代替。
            if (maintenancePlanMapper.selectEnabledExecutorForUpdate(tenantId, userId) == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前企业、账号或成员关系已失效");
            }
            // 只在拿到计划锁后查询一次请求号；READ_COMMITTED保证等待后能看到已提交记录。
            MaintenanceExecutionEntity previous = maintenancePlanMapper.findExecutionByRequest(tenantId, requestId);
            if (previous != null) {
                return replayExecution(previous, planId, userId, result);
            }
            if (!Boolean.TRUE.equals(plan.getEnabled())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "维护计划已停用，不能登记执行结果");
            }

            //3. 只有计划负责人可以登记；管理员身份本身不允许代替负责人执行。
            if (!Objects.equals(userId, plan.getAssigneeUserId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有计划负责人可以登记执行结果");
            }
            //4. 结果必填校验已完成；统一在上海时区取一次完成时间，避免午夜跨日计算不一致。
            LocalDateTime executedAt = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
            LocalDate today = executedAt.toLocalDate();
            if (plan.getNextDueDate() == null || plan.getNextDueDate().isAfter(today)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "计划尚未到期，不能提前登记执行结果");
            }
            if (plan.getCycleDays() == null || plan.getCycleDays() < 1 || plan.getCycleDays() > 3650) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "维护计划周期无效");
            }
            //5. 同一计划的同一期仅能登记一次；数据库唯一约束提供第二层保护。
            if (maintenancePlanMapper.countExecutionForPeriod(tenantId, planId, plan.getNextDueDate()) != 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "本期计划已经登记过执行结果");
            }
            LocalDate nextDueDate = today.plusDays(plan.getCycleDays());

            //6. Java只传本次执行参数；XML用INSERT SELECT直接保存计划、设备和人员快照。
            MaintenanceExecutionEntity execution = new MaintenanceExecutionEntity();
            execution.setTenantId(tenantId);
            execution.setPlanId(planId);
            execution.setRequestId(requestId);
            execution.setScheduledDate(plan.getNextDueDate());
            execution.setExecutedAt(executedAt);
            execution.setExecutedBy(userId);
            execution.setResult(result);
            execution.setNextDueDate(nextDueDate);
            try {
                if (maintenancePlanMapper.insertExecution(execution) != 1 || execution.getId() == null) {
                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "执行记录保存失败");
                }
            } catch (DuplicateKeyException exception) {
                // 不同计划并发复用同一请求号时，检查获胜记录是否确为同一次操作。
                MaintenanceExecutionEntity concurrent = maintenancePlanMapper.findExecutionByRequest(tenantId, requestId);
                if (concurrent == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "执行请求或本期记录冲突，请核对后重试");
                }
                return replayExecution(concurrent, planId, userId, result);
            }

            //7. 实际完成日期加周期得到下次到期日，不逐期补造过去漏做的记录。
            if (maintenancePlanMapper.advancePlanDueDate(tenantId, planId, plan.getNextDueDate(), nextDueDate) != 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "计划到期日已变化，执行结果未提交");
            }
            //8. 回查不可变执行记录；后续任一步失败均回滚记录和日期修改。
            MaintenanceExecutionVO value = maintenancePlanMapper.selectExecutionDetail(tenantId, execution.getId());
            if (value == null) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "执行记录保存后查询失败");
            }
            return value;
        } catch (PessimisticLockingFailureException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "并发处理冲突，请使用相同请求号重试");
        }
    }

    /** 幂等重试只能复用同一操作人的同一计划及结果，不允许借请求号执行另一项操作。 */
    private MaintenanceExecutionVO replayExecution(MaintenanceExecutionEntity previous, Long planId, Long userId, String result) {
        if (!Objects.equals(previous.getPlanId(), planId) || !Objects.equals(previous.getExecutedBy(), userId)
                || !Objects.equals(previous.getResult(), result)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请求号已被其他执行内容使用");
        }
        MaintenanceExecutionVO value = new MaintenanceExecutionVO();
        BeanUtils.copyProperties(previous, value);
        return value;
    }

    /** 查询执行历史，不按当前计划或人员启用状态隐藏已完成记录。 */
    @Override
    @Transactional(readOnly = true)
    public IPage<MaintenanceExecutionVO> getExecutionPage(Long planId, MaintenanceExecutionPageQueryDto query) {
        //1. 校验当前企业及有效成员关系，不要求管理员或原执行人身份。
        Long tenantId = currentAccessService.requireActiveTenantId();
        //2. 无效计划ID不进入数据库。
        if (planId == null || planId <= 0 || query == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "计划ID或分页参数无效");
        }
        //3. 只允许查询本企业计划；停用计划仍保留历史查询入口。
        if (maintenancePlanMapper.selectPlanDetail(tenantId, planId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "维护计划不存在");
        }
        //4. 沿用现有分页默认值和限制。
        long current = Math.max(1, query.getCurrent());
        long size = Math.max(1, Math.min(100, query.getSize()));
        //5. XML同时限定企业和计划，读取执行表快照，不联查当前设备或人员资料。
        //6. 分页插件返回真实总数；没有记录时返回空分页。
        return maintenancePlanMapper.selectExecutionPage(new Page<>(current, size), tenantId, planId);
    }

    /** 查看执行记录详情，不因计划停用或负责人变更而隐藏历史。 */
    @Override
    @Transactional(readOnly = true)
    public MaintenanceExecutionVO getExecutionDetail(Long id) {
        //1. 获取可信企业范围并检查当前成员资格，不要求管理员或原执行人。
        Long tenantId = currentAccessService.requireActiveTenantId();
        //2. 执行记录主键必须是正数。
        if (id == null || id <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "执行记录ID必须大于0");
        }
        //3. 复用XML，按企业及执行记录ID读取历史快照。
        MaintenanceExecutionVO execution = maintenancePlanMapper.selectExecutionDetail(tenantId, id);
        //4. 不存在与跨企业统一返回404，避免泄露其他企业资料。
        if (execution == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "执行记录不存在");
        }
        //5. 直接返回登记时的资料，不重新拼接当前计划、设备或人员名称。
        return execution;
    }

}
