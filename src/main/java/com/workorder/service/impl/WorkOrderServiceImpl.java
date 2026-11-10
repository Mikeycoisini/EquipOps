package com.workorder.service.impl;

import com.auth.model.TenantAccess;
import com.auth.service.CurrentAccessService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipment.mapper.EquipmentMapper;
import com.equipops.domain.dto.workorder.*;
import com.equipops.domain.entity.*;
import com.equipops.domain.enums.WorkOrderEventType;
import com.equipops.domain.enums.WorkOrderPriority;
import com.equipops.domain.enums.WorkOrderStatus;
import com.equipops.domain.vo.WorkOrderLogVO;
import com.equipops.domain.vo.WorkOrderPartVO;
import com.equipops.domain.vo.EquipmentVO;
import com.equipops.domain.vo.WorkOrderVO;
import com.tenant.mapper.TenantMembershipMapper;
import com.workorder.mapper.UserMapper;
import com.workorder.mapper.WorkOrderLogMapper;
import com.workorder.mapper.WorkOrderMapper;
import com.workorder.service.WorkOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static com.equipops.domain.enums.WorkOrderStatus.IN_PROGRESS;

@Service
@RequiredArgsConstructor
public class WorkOrderServiceImpl implements WorkOrderService {
    /** 新工单及日志插入前的可信厂区写入锁。 */
    @org.springframework.beans.factory.annotation.Autowired
    private com.auth.scope.SiteWriteAccessService siteWriteAccess;
    /** 原派单也必须验证当前个人厂区资格，防止旧入口绕过团队增量。 */
    @org.springframework.beans.factory.annotation.Autowired
    private WorkOrderAssignmentAccess assignmentAccess;


    private static final String PENDING = "PENDING";//待处理
    //
    //ofPattern 是日期时间格式化工具类的静态工厂方法：传入一个“模板字符串”，返回一个 DateTimeFormatter 对象。
    private static final DateTimeFormatter ORDER_NO_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    // 工单表操作：查询、新增和修改维修工单。
    private final WorkOrderMapper workOrderMapper;
    // 工单日志表操作：记录工单创建、派单、状态变更等事件，查询时间线。
    private final WorkOrderLogMapper workOrderLogMapper;
    // 用户表操作：查询用户信息，校验负责人是否存在、账号是否启用。
    private final UserMapper userMapper;
    // 设备表操作：查询设备信息，校验报修设备是否存在。
    private final EquipmentMapper equipmentMapper;
    /**
     * 当前企业、登录用户及管理员身份检查。
     */
    private final CurrentAccessService currentAccessService;
    /**
     * 检查派单负责人是否为当前企业启用成员。
     */
    private final TenantMembershipMapper membershipMapper;

    @Transactional
    @Override
    public WorkOrderVO reportRepair(WorkOrderCreateDto request) {
        Long tenantId = currentAccessService.requireActiveTenantId();

        //校验报修信息：标题、设备 ID、优先级等必填项是否合法。
        if (request == null) {
            throw new IllegalArgumentException("报修信息不能为空");
        }
        if (!StringUtils.hasText(request.getTitle())) {
            throw new IllegalArgumentException("报修标题不能为空");
        }
        String title = request.getTitle().trim();
        if (title.length() > 200) {
            throw new IllegalArgumentException("报修标题不能超过 200 个字符");
        }
        if (request.getEquipmentId() == null) {
            throw new IllegalArgumentException("设备 ID 不能为空");
        }
        //根据 equipmentId 查询设备；设备不存在时结束并返回业务错误。
        EquipmentEntity equipment = equipmentMapper.selectOne(new LambdaQueryWrapper<EquipmentEntity>()
                .eq(EquipmentEntity::getTenantId, tenantId)
                .eq(EquipmentEntity::getId, request.getEquipmentId()));
        if (equipment == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在");
        }
        // 新增工单前锁定当前授权，并用父设备当前读阻止删除/归属变化穿过INSERT。
        siteWriteAccess.lockSite(equipment.getSiteId());
        // 锁定父设备；授权成员和厂区的锁保持到工单及日志共同提交。
        equipment = equipmentMapper.selectOne(new LambdaQueryWrapper<EquipmentEntity>()
                .eq(EquipmentEntity::getTenantId, tenantId)
                .eq(EquipmentEntity::getId, request.getEquipmentId()).last("FOR UPDATE"));
        // 父对象在等待期间不可用时不能继续创建。
        if (equipment == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在");
        //设置创建人 createdBy。有登录用户上下文时从当前登录用户取得，不要直接信任请求体传入的用户 ID。
        //项目目前没有登录上下文和 UserMapper，所以 createdBy 只能暂时信任请求体。
        // 上面的说明是旧版本背景；现已具备登录上下文，忽略请求体创建人。
        Long createdBy = currentAccessService.requireEnabledUserId();
        if (createdBy == null) {
            throw new IllegalArgumentException("创建人不能为空");
        }
        //生成唯一 orderNo，设置初始状态为 PENDING。
        String orderNo = "WO"
                //取当前系统时间，然后用定义的模板转成字符串
                + LocalDateTime.now().format(ORDER_NO_TIME)
                //用线程专属的随机数生成器，生成0-999的随机数，如果生成的数字是两位数或者一位数，要在左侧补零到三位数，例如9 -> 009
                + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));

        //保存 WorkOrderEntity，取得数据库生成的工单 ID。
        //每个setter对应sql表的一列
        WorkOrderEntity order = new WorkOrderEntity();
        order.setOrderNo(orderNo);
        order.setTitle(title);
        order.setEquipmentId(request.getEquipmentId());
        // 工单必须继承已验证设备的企业和厂区，不允许客户端指定归属。
        order.setTenantId(equipment.getTenantId());
        order.setSiteId(equipment.getSiteId());
        order.setDescription(request.getDescription());
        /*
        如果你直接 order.setPriority(request.getPriority()) 而前端没传优先级，就会把默认值覆盖成null。
        MyBatis-Plus 又会跳过 null 字段，最后靠数据库默认值填上——虽然结果也是 NORMAL，但对象里的值和数据库里的值不一致，后面组装 VO 时会拿到 null。
        所以显式兜底更清楚。
         */
        order.setPriority(request.getPriority() == null
                ? WorkOrderPriority.NORMAL
                : request.getPriority());
        order.setStatus(WorkOrderStatus.PENDING);
        order.setDueAt(request.getDueAt());
        order.setCreatedBy(createdBy);
        // 报修阶段不派单，负责人留空，由后面的 dispatch 接口负责
        order.setAssigneeUserId(null);
        order.setCompletedAt(null);
        int inserted = workOrderMapper.insert(order);
        //成功插入一行会返回 1，如果返回0，说明没插进去，属于异常情况，主动抛异常让事务回滚。
        if (inserted != 1) {
            throw new IllegalStateException("创建工单失败");
        }
        //把新生成的 id 塞回 order.id
        Long workOrderId = order.getId();
        //新增一条工单日志：eventType = CREATED、toStatus = PENDING、操作人为创建人。
        //创建一条日志内存对象，所有字段初始化为null
        WorkOrderLogEntity log = new WorkOrderLogEntity();
        //把这条日志挂到刚创建的工单上。
        log.setWorkOrderId(workOrderId);
        log.setEventType(WorkOrderEventType.CREATED);
        log.setFromStatus(null);
        log.setToStatus(WorkOrderStatus.PENDING);
        log.setTitle("工单创建");
        log.setDetail("报修工单已创建, 等待派单");
        log.setActorUserId(createdBy);

        if (workOrderLogMapper.insert(log) != 1) {
            throw new IllegalArgumentException("记录工单日志失败");
        }

        //将工单及时间线组装成 WorkOrderVO 并返回。
        WorkOrderEntity saved = requireWorkOrder(workOrderId, tenantId);
        //查询该工单的所有日志，SQL 放在 WorkOrderLogMapper.xml，按时间和 ID 升序。
        List<WorkOrderLogEntity> logs = workOrderLogMapper.selectByWorkOrderId(tenantId, workOrderId);
        //Entity 转 VO
        WorkOrderVO vo = new WorkOrderVO();
        BeanUtils.copyProperties(saved, vo);

        List<WorkOrderLogVO> timeline = logs.stream()
                //对每一条日志做转换
                .map(logEntity -> {
                    WorkOrderLogVO logVO = new WorkOrderLogVO();
                    BeanUtils.copyProperties(logEntity, logVO);
                    return logVO;
                }).toList();
        //把列表放进工单 VO 的 timeline 字段
        vo.setTimeline(timeline);
        return vo;
        /**
         * 或者：校验标题、设备 ID。
         * 查询设备，确认存在。
         * 新建工单：生成 orderNo，状态设为 PENDING。
         * 保存工单。
         * 保存一条 CREATED 日志。
         * 返回 WorkOrderVO。
         */
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkOrderVO dispatch(Long id, WorkOrderDispatchDto request) {
        // 当前阶段由企业管理员派单；操作人从登录身份取得。
        Long tenantId = currentAccessService.requireTenantAdminTenantId();
        //校验工单 ID、assigneeUserId 和操作人 actorUserId。
        /**
         *     1. 检查参数
         *        工单ID为空或小于1 → 结束，提示参数错误
         *        请求为空 → 结束，提示参数错误
         *        负责人ID为空或小于1 → 结束，提示请选择负责人
         *        操作人ID为空或小于1 → 结束，提示缺少操作人
         */
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("参数错误");
        }
        if (request == null) {
            throw new IllegalArgumentException("派单请求不能为空");
        }
        // 获取被派单的负责人用户 ID。
        Long assigneeUserId = request.getAssigneeUserId();
        /** 获取执行派单操作的用户 ID。 */
        Long actorUserId = currentAccessService.requireEnabledUserId();

        if (assigneeUserId == null || assigneeUserId <= 0) {
            throw new IllegalArgumentException("提示请选择负责人");
        }
        if (actorUserId == null || actorUserId <= 0) {
            throw new IllegalArgumentException("请提供有效的操作人");
        }
        // 根据 ID 查询工单；不存在时结束并返回业务错误。
        WorkOrderEntity workOrderEntity = requireWorkOrder(id, tenantId);
        if (workOrderEntity == null) {
            throw new IllegalArgumentException("工单不存在");
        }
        // 如果派单状态不是待处理状态，那么抛出当前状态不允许派单。
        if (!PENDING.equals(String.valueOf(workOrderEntity.getStatus()))) {
            throw new IllegalArgumentException("当前状态不允许派单");
        }
        // 如果工单已经有负责人，则不重复派单。
        if (workOrderEntity.getAssigneeUserId() != null) {
            throw new IllegalArgumentException("该工单已经派发");
        }
        // 根据用户 ID 查询负责人；确认用户存在且启用。
        UserEntity assignee = userMapper.selectById(assigneeUserId);
        if (assignee == null) {
            throw new IllegalArgumentException("负责人不存在");
        }
        if (!Boolean.TRUE.equals(assignee.getEnabled())) {
            throw new IllegalArgumentException("负责人账号已停用");
        }
        // 用户账号启用还不够，负责人必须属于当前企业且成员关系启用。
        if (membershipMapper.selectCount(new LambdaQueryWrapper<TenantMembershipEntity>()
                .eq(TenantMembershipEntity::getTenantId, tenantId)
                .eq(TenantMembershipEntity::getUserId, assigneeUserId)
                .eq(TenantMembershipEntity::getEnabled, true)) == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "负责人必须是当前企业的有效成员");
        }
        // 资格验证与范围变更共用企业锁，旧派单也不能向无个人厂区授权的成员派单。
        assignmentAccess.lockAdmin();
        assignmentAccess.requireTarget(tenantId, assigneeUserId, workOrderEntity.getSiteId(), null);
        // 设置工单负责人并保存工单。
        workOrderEntity.setAssigneeUserId(assigneeUserId);
        // 带上企业、待处理及未派单条件，防止两个请求覆盖彼此的负责人。
        // 具体条件更新 SQL 放在 WorkOrderMapper.xml。
        int rows = workOrderMapper.assignWorkOrder(tenantId, id, assigneeUserId);
        if (rows != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "派单状态已变化，请刷新后重试");
        }
        // 新增一条 ASSIGNED 日志，记录派单人、负责人及派单说明。
        WorkOrderLogEntity log = new WorkOrderLogEntity();
        log.setWorkOrderId(id);
        log.setEventType(WorkOrderEventType.ASSIGNED);
        log.setFromStatus(workOrderEntity.getStatus());
        log.setToStatus(workOrderEntity.getStatus());
        log.setTitle("工单已派单");
        log.setDetail("派单给用户 " + assigneeUserId);
        log.setActorUserId(actorUserId);
        int logRows = workOrderLogMapper.insert(log);
        if (logRows != 1) {
            throw new IllegalStateException("派单日志保存失败");
        }

        // 重新查询工单，取得数据库中的最新数据。
        WorkOrderEntity latestWorkOrder = requireWorkOrder(id, tenantId);

        // 查询,这张,工单的所有日志，按创建时间和 ID 排列。
        List<WorkOrderLogEntity> logs = workOrderLogMapper.selectByWorkOrderId(tenantId, id);

        // 将日志逐条转换成页面需要的时间线数据。
        List<WorkOrderLogVO> timeline = new ArrayList<>();
        for (WorkOrderLogEntity logEntity : logs) {
            WorkOrderLogVO logVO = new WorkOrderLogVO();
            BeanUtils.copyProperties(logEntity, logVO);
            timeline.add(logVO);
        }

        // 将最新工单和时间线装入返回对象。
        WorkOrderVO result = new WorkOrderVO();
        BeanUtils.copyProperties(latestWorkOrder, result);
        result.setTimeline(timeline);
        return result;
    }


    /**
     * 分页查询当前企业的工单，日志通过独立接口按需加载。
     */
    @Override
    public IPage<WorkOrderVO> getWorkOrderPage(WorkOrderPageQueryDto query) {
        // 1. 获取当前企业 ID，确保只查询当前企业的数据。
        Long tenantId = currentAccessService.requireActiveTenantId();

        // 2. 校验筛选条件中的设备 ID 和负责人 ID。
        Long equipmentId = query.getEquipmentId();
        Long assigneeUserId = query.getAssigneeUserId();

        if (equipmentId != null && equipmentId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "设备 ID 必须大于0");
        }
        if (assigneeUserId != null && assigneeUserId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "负责人 ID 必须大于0");
        }

        // 3. 校验分页参数，每页最多查询 100 条。
        long current = query.getCurrent();
        long size = query.getSize();

        if (current < 1) {
            current = 1;
        }
        if (size < 1) {
            size = 1;
        } else if (size > 100) {
            size = 100;
        }

        Page<WorkOrderEntity> page = new Page<>(current, size);

        // 4. 整理关键字；具体筛选条件放在 WorkOrderMapper.xml。
        String keyword = StringUtils.hasText(query.getKeyword()) ? query.getKeyword().trim() : null;
        // 5. XML 中将编号或标题匹配放在同一组括号内，避免绕过企业条件。
        // 6. XML 按创建时间、ID 倒序，让最新工单排在前面。

        // 7. 执行分页，再将 Entity 转换成列表需要的 VO。
        IPage<WorkOrderEntity> entityPage;
        if (Boolean.TRUE.equals(query.getOverdueOnly())) {
            // 逾期判断只取一次服务端上海时间，最早到期的工单排在前面。
            LocalDateTime asOf = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
            entityPage = workOrderMapper.selectOverdueWorkOrderPage(page, tenantId, query, keyword, asOf);
        } else {
            // false或省略时继续使用原分页方法及排序。
            entityPage = workOrderMapper.selectWorkOrderPage(page, tenantId, query, keyword);
        }

        return entityPage.convert(this::toListVO);
    }

    /**
     * 将工单转换为列表返回对象，不查询日志。
     */

    private WorkOrderVO toListVO(WorkOrderEntity entity) {
        WorkOrderVO vo = new WorkOrderVO();
        BeanUtils.copyProperties(entity, vo);

        // 列表不加载时间线；空列表不代表该工单没有日志。
        vo.setTimeline(List.of());
        return vo;
    }

    /**
     * 先校验父工单的企业归属，再读取日志；未归属工单同样不可见。
     */

    @Override
    public List<WorkOrderLogVO> getWorkOrderLogs(Long id) {
        // 1. 获取并验证当前企业，不能从前端传入的工单 ID 推测企业。
        Long tenantId = currentAccessService.requireActiveTenantId();
        // 2. 先确认父工单属于当前企业；失败时抛出异常，后面的日志查询不会执行。
        requireWorkOrder(id, tenantId);
        // 3. 只查询这个已经验证过的工单的日志，按发生时间、ID升序排列。
        // XML直接返回VO，补充同企业成员的当前显示名，保留原原因和操作人ID。
        return workOrderLogMapper.selectViewByWorkOrderId(tenantId, id);
    }

    /** 查询当前企业工单详情；只读事务让关联资料在同一次查询流程中读取。 */
    @Override
    @Transactional(readOnly = true)
    public WorkOrderVO getWorkOrderDetail(Long id) {
        // 1. 获取可信企业 ID，再校验工单 ID 和归属；不能只根据前端 ID 直接查所有企业。
        Long tenantId = currentAccessService.requireActiveTenantId();
        WorkOrderEntity order = requireWorkOrder(id, tenantId);

        // 2. XML 同时限定工单和设备的企业、厂区，不能把其他企业的设备资料拼入详情。
        EquipmentVO equipment = workOrderMapper.selectWorkOrderEquipment(tenantId, id);
        if (equipment == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "工单关联设备不可用，请核对设备归属");
        }

        // 3. 复制工单自身字段，equipment、parts、timeline 来自关联查询，需要单独设置。
        WorkOrderVO result = new WorkOrderVO();
        BeanUtils.copyProperties(order, result);
        result.setEquipment(equipment);

        // 4. XML 同时检查父工单与备件的企业归属；没有用料时正常返回空列表。
        result.setParts(workOrderMapper.selectWorkOrderParts(tenantId, id));

        // 5. 时间线按时间及 ID 升序返回，不修改日志，也不改变工单状态。
        result.setTimeline(workOrderLogMapper.selectViewByWorkOrderId(tenantId, id));
        return result;
    }

    /** 查询当前企业工单用料；此接口只读，不代替领料、退料或原始库存流水。 */
    @Override
    @Transactional(readOnly = true)
    public List<WorkOrderPartVO> getWorkOrderParts(Long id) {
        // 1. 先确认工单在当前企业可见，避免把“无权访问”当成“用料为空”。
        Long tenantId = currentAccessService.requireActiveTenantId();
        requireWorkOrder(id, tenantId);
        // 2. 联表查询备件资料和工单用量，不按备件逐条查询，避免重复访问数据库。
        return workOrderMapper.selectWorkOrderParts(tenantId, id);
    }

    //TODO
    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkOrderVO startRepair(Long id) {
        //1. 获取当前企业 ID、当前登录用户 ID
        Long tenantId = currentAccessService.requireActiveTenantId();
        //当前登录用户 ID
        Long userId = currentAccessService.requireEnabledUserId();

        //2. 查询当前企业的目标工单
        WorkOrderEntity order = requireWorkOrder(id, tenantId);

        //3. 检查：状态是待处理、已有负责人、负责人就是当前用户
        if (order.getStatus() != WorkOrderStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有待处理工单才能开始维修");
        }
        if (order.getAssigneeUserId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "工单尚未派单");
        }
        // 注意：equals 要用确定非空的那个对象来调，避免空指针
        if (!order.getAssigneeUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有工单负责人可以开始维修");
        }
        //4. 按原状态和负责人条件更新为维修中
        //返回值 rows 是 SQL 实际影响的行数
        int rows = workOrderMapper.startRepair(tenantId, id, userId);
        //rows=1: 成功改了一行, rows=0: 没有任何行满足 WHERE 条件
        if (rows != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "工单状态已变化，请刷新后重试");
        }

        //5. 保存一条状态变更日志
        WorkOrderLogEntity log = new WorkOrderLogEntity();
        log.setWorkOrderId(id);
        log.setEventType(WorkOrderEventType.STATUS_CHANGED);
        log.setFromStatus(WorkOrderStatus.PENDING);
        log.setToStatus(WorkOrderStatus.IN_PROGRESS);
        log.setTitle("开始维修");
        log.setDetail("负责人开始处理工单");
        log.setActorUserId(userId);
        if (workOrderLogMapper.insert(log) != 1) {
            throw new IllegalStateException("开始维修日志保存失败");
        }

        //6. 返回最新工单
        WorkOrderEntity latest = requireWorkOrder(id, tenantId);
        //重新发一次 SELECT，拿到更新后的真实数据
        List<WorkOrderLogVO> timeline = workOrderLogMapper.selectByWorkOrderId(tenantId, id).stream()
                .map(entity -> {
                    WorkOrderLogVO vo = new WorkOrderLogVO();
                    BeanUtils.copyProperties(entity, vo);
                    return vo;
                }).toList();
        //组装工件
        WorkOrderVO result = new WorkOrderVO();
        BeanUtils.copyProperties(latest, result);
        //timeline 是数据库里不存在的组合字段：它来自另一张表 work_order_logs 的查询结果。BeanUtils 无从得知，所以必须手动塞进去。
        result.setTimeline(timeline);
        return result;

    }

    /**
     * 添加维修日志
     * @param id
     * @param request
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkOrderLogVO addRepairNoteLog(Long id, RepairNoteCreateDto request) {
        // 1. 获取当前企业 ID、当前登录用户 ID。
        Long tenantId = currentAccessService.requireActiveTenantId();
        Long userId = currentAccessService.requireEnabledUserId();
        // 2. 校验请求及维修内容，去掉内容首尾空白。
        if (request == null) {
            throw new IllegalArgumentException("请求不能为空!");
        }
        if (!StringUtils.hasText(request.getDetail())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "维修内容不能为空");
        }
        //去掉内容首尾空白
        String detail = request.getDetail().trim();
        // 3. 根据当前企业 ID + 工单 ID 查询并锁定工单。
        //    查询可写在 WorkOrderMapper.xml 中，使用 FOR UPDATE。
        //    锁定后检查状态，避免检查期间工单被并发提交验收。
        if (id == null || id <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工单 ID 无效");
        }
        WorkOrderEntity workOrderEntity = workOrderMapper.selectTenantAnduserId(tenantId,id);
        if (workOrderEntity == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工单不存在");
        }
        // 4. 检查状态必须是 IN_PROGRESS。
        if (workOrderEntity.getStatus() != IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "状态错误！");
        }
        // 5. 检查当前用户必须是负责人。order.getAssigneeUserId()：第三步查到的工单负责人用户 ID。
        if (!userId.equals(workOrderEntity.getAssigneeUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前用户不是负责人！");
        }
        // 6. 创建日志：
        /// workOrderId = 路径中的 id,eventType = COMMENT,fromStatus、toStatus = 工单当前状态,
        /// title = "维修处理记录",title = "维修处理记录",detail = 校验后的请求内容,actorUserId = 当前登录用户 ID
        // 6. 创建本次维修记录的日志对象。
        WorkOrderLogEntity workLog = new WorkOrderLogEntity();
        // 关联当前正在处理的工单。
        workLog.setWorkOrderId(workOrderEntity.getId());
        // 追加维修文字记录，使用已有的 COMMENT 类型。
        workLog.setEventType(WorkOrderEventType.COMMENT);
        // 本接口不改变工单状态，因此变更前后状态相同。
        workLog.setFromStatus(workOrderEntity.getStatus());
        workLog.setToStatus(workOrderEntity.getStatus());
        // 日志标题由后端统一设置。
        workLog.setTitle("维修处理记录");
        // detail 是第二步校验并去掉首尾空白后的维修内容。
        workLog.setDetail(detail);
        // userId 是当前登录用户 ID，不能由前端指定操作人。
        workLog.setActorUserId(userId);
        // 7. 插入日志，检查影响行数。
        int logRows = workOrderMapper.insertWorkLog(workLog);
        if(logRows !=1) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "维修记录保存失败");
        }
        // 8. 重新读取日志，取得数据库生成的时间，转成 WorkOrderLogVO 返回。
        //重新读取数据库
        WorkOrderLogEntity savedLog = workOrderLogMapper.selectById(workLog.getId());
        if (savedLog == null) {
            throw new IllegalStateException("日志读取失败！");
        }
        // 转成 WorkOrderLogVO 返回
        WorkOrderLogVO logVO = new WorkOrderLogVO();
        BeanUtils.copyProperties(savedLog, logVO);
        return logVO;
    }

    /**
     * “提交验收,确认谁、哪个工单、当前能否提交，再改工单状态、记日志，最后返回更新后的工单。
     * @param id
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkOrderVO submitAcceptance(Long id) {
        // 1. 获取当前企业 ID、当前登录用户 ID。不相信前端传来的操作者身份
        Long tenantId = currentAccessService.requireActiveTenantId();
        Long userId = currentAccessService.requireEnabledUserId();
        // 2. 根据当前企业 ID + 工单 ID 查询并锁定工单。防止检查期间被别的操作改动
        //    查询可写在 WorkOrderMapper.xml 中，使用 FOR UPDATE。
        //    锁定后检查状态，避免检查期间工单被并发提交验收。
        if (id == null || id<=0) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "工单 ID 无效。");
        }
        WorkOrderEntity workOrderEntity = workOrderMapper.selectTenantAnduserId(tenantId,id);
        if(workOrderEntity == null) {
            throw new ResponseStatusException(HttpStatus.valueOf(404), "维修工单为空！请重试。");
        }
        if (workOrderEntity.getStatus() != IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.valueOf(409), "当前状态不能提交验收。");
        }
        if (!userId.equals(workOrderEntity.getAssigneeUserId())) {
            throw new ResponseStatusException(HttpStatus.valueOf(403), "当前用户不是负责人。");
        }
        // 3. 更新工单状态。这里改的是 work_orders 表。
        int rows = workOrderMapper.submitAcceptance(tenantId, id, userId);
        if (rows != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "工单状态已变化，请刷新后重试.");
        }

        // 4. 新建日志对象，描述刚才发生的状态变化。留下“谁在何时提交验收”的记录
        WorkOrderLogEntity workLog = new WorkOrderLogEntity();
        workLog.setWorkOrderId(id);
        workLog.setEventType(WorkOrderEventType.STATUS_CHANGED);
        workLog.setFromStatus(WorkOrderStatus.IN_PROGRESS);
        workLog.setToStatus(WorkOrderStatus.AWAITING_ACCEPTANCE);
        workLog.setTitle("提交验收");
        workLog.setActorUserId(userId);

        // 5. 将新日志插入 work_order_logs 表。
        int logRows = workOrderMapper.insertWorkLog(workLog);
        if (logRows != 1) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "工单日志保存失败");
        }
        //6.重新读取更新后的工单，为了让接口返回数据库中的最新状态。
        WorkOrderEntity workOrder = requireWorkOrder(id, tenantId);//先查询当前公司，限定当前企业，避免按 ID 读到其他企业的工单。
        WorkOrderVO result = new WorkOrderVO();
        BeanUtils.copyProperties(workOrder,result);
        return result;
    }
    /** 验收通过：先校验身份、工单、状态和维修人与验收人分离规则。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkOrderVO approve(Long id) {
        // 1. 重新验证登录用户的当前企业成员关系；access 同时包含企业、用户和实时角色。
        TenantAccess access = currentAccessService.requireTenantAccess();
        Long tenantId = access.getTenant().getId();
        Long userId = access.getUserId();
        // 2. 校验路径 ID，并在当前企业范围内锁定工单，防止并发修改状态。
        if (id == null || id <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工单 ID 无效");
        }
        WorkOrderEntity order = workOrderMapper.selectTenantAnduserId(tenantId, id);
        if (order == null) {
            // 不区分工单不存在和属于其他企业，避免泄露跨企业数据。
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工单不存在");
        }
        // 3. 只能验收已由维修负责人提交、正在等待验收的工单。
        if (order.getStatus() != WorkOrderStatus.AWAITING_ACCEPTANCE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前工单状态不能验收通过");
        }
        // 4. 维修负责人不能验收自己执行的工单；即使也是报修人或主管也不例外。
        if (userId.equals(order.getAssigneeUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "不能验收自己维修的工单");
        }
        // 5. 判断当前用户是否为报修人，或具有已明确约定的验收权限。
        // （报修人）
        boolean isReporter = userId.equals(order.getCreatedBy());
        // 判断是否有主管/验收权限 (假设 access 里有权限校验方法)
        boolean HasACCEPTOR = access.getRoleCodes().contains("ACCEPTOR"); //获取登录用户的当前企业成员是否有ACCEPTOR编码值
        if (!isReporter && !HasACCEPTOR) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "您没有权限验收该工单");
        }
        // 6. 只更新当前企业、待验收且维修负责人不是当前用户的工单。
        // 完成时间由 SQL 使用数据库当前时间写入，不接收前端提交的时间。
        int rows = workOrderMapper.approveWorkOrder(tenantId, id, userId);
        if (rows != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "工单状态已变化，请刷新后重试");
        }

        // 7. 新增状态变更日志；actorUserId 记录本次验收人，不覆盖原维修负责人。
        WorkOrderLogEntity workLog = new WorkOrderLogEntity();
        workLog.setWorkOrderId(id);
        workLog.setEventType(WorkOrderEventType.STATUS_CHANGED);
        workLog.setFromStatus(WorkOrderStatus.AWAITING_ACCEPTANCE);
        workLog.setToStatus(WorkOrderStatus.COMPLETED);
        workLog.setTitle("验收通过");
        workLog.setActorUserId(userId);
        // 日志写入失败时抛出异常，由当前事务一起回滚第 6 步的状态与完成时间。
        if (workOrderMapper.insertWorkLog(workLog) != 1) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "验收日志保存失败");
        }

        // 8. SQL 更新不会自动修改前面查询的 Java 对象，重新查询最新状态和完成时间。
        WorkOrderEntity saved = requireWorkOrder(id, tenantId);
        WorkOrderVO result = new WorkOrderVO();
        BeanUtils.copyProperties(saved, result);
        return result;
    }

    /**
     * /** 验收不通过，填写原因后退回原负责人继续维修。
     * 确认当前用户有验收资格，把待验收工单退回维修中，并记录原因。
     * @param id
     * @param request
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkOrderVO reject(Long id, WorkOrderRejectDto request) {
        //1. 获取当前企业、当前用户和企业角色。
        // access 是服务端重新验证的成员身份，不接收客户端指定的企业和操作人。
        TenantAccess access = currentAccessService.requireTenantAccess();
        Long tenantId = access.getTenant().getId();
        Long userId = access.getUserId();

        //2. 校验工单 ID、退回原因。
        if (id == null || id <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工单 ID 无效");
        }
        // Controller 的 @Valid 校验 HTTP 请求；这里同时保护其他代码直接调用 Service 的情况。
        if (request == null || !StringUtils.hasText(request.getReason())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "退回原因不能为空");
        }
        if (request.getReason().length() > 5000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "退回原因不能超过5000个字符");
        }
        String reason = request.getReason().strip();

        //3. 按当前企业和工单 ID 查询并锁定工单；不存在则返回 404。
        // 复用已有 FOR UPDATE 查询，当前事务结束前保持锁，避免重复退回或并发验收。
        WorkOrderEntity order = workOrderMapper.selectTenantAnduserId(tenantId, id);
        if (order == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工单不存在");
        }

        //4. 检查状态必须为 AWAITING_ACCEPTANCE。
        if (order.getStatus() != WorkOrderStatus.AWAITING_ACCEPTANCE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有待验收工单才能验收退回");
        }

        //5. 检查当前用户不能是维修负责人，
        //   并且必须是报修人，或者具有 ACCEPTOR 角色。
        if (userId.equals(order.getAssigneeUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "不能验收自己维修的工单");
        }
        boolean isReporter = userId.equals(order.getCreatedBy());
        boolean hasAcceptor = access.getRoleCodes().contains("ACCEPTOR");
        // TENANT_ADMIN 本身不等于验收权限，与已有 approve 方法保持一致。
        if (!isReporter && !hasAcceptor) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "您没有权限验收退回该工单");
        }

        //6. 条件更新为 IN_PROGRESS，保留原负责人；
        //   更新影响行数必须为 1。
        int rows = workOrderMapper.rejectWorkOrder(tenantId, id, userId);
        if (rows != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "工单状态已变化，请刷新后重试");
        }

        //7. 插入 STATUS_CHANGED 日志：
        //   待验收 → 维修中，detail 保存退回原因，actorUserId 保存当前用户；
        //   插入失败则抛出异常，让事务回滚。
        WorkOrderLogEntity workLog = new WorkOrderLogEntity();
        workLog.setWorkOrderId(id);
        workLog.setEventType(WorkOrderEventType.STATUS_CHANGED);
        workLog.setFromStatus(WorkOrderStatus.AWAITING_ACCEPTANCE);
        workLog.setToStatus(WorkOrderStatus.IN_PROGRESS);
        workLog.setTitle("验收退回");
        workLog.setDetail(reason);
        workLog.setActorUserId(userId);
        if (workOrderMapper.insertWorkLog(workLog) != 1) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "验收退回日志保存失败");
        }

        //8. 重新查询工单，转换成 WorkOrderVO 返回。
        // SQL 不会自动刷新前面的 order 对象；重查后返回数据库中的最新状态。
        WorkOrderEntity saved = requireWorkOrder(id, tenantId);
        WorkOrderVO result = new WorkOrderVO();
        BeanUtils.copyProperties(saved, result);
        return result;
    }

    /**
     * 所有工单 ID 查询都必须带企业条件；不存在和越权统一返回404。
     */
    private WorkOrderEntity requireWorkOrder(Long id, Long tenantId) {
        // 先校验 ID，再限定企业和工单 ID；两个条件必须同时满足。
        if (id == null || id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工单 ID 无效");
        WorkOrderEntity order = workOrderMapper.selectOne(new LambdaQueryWrapper<WorkOrderEntity>()
                .eq(WorkOrderEntity::getTenantId, tenantId).eq(WorkOrderEntity::getId, id));
        // 未知 ID 和其他企业的 ID 均返回404，不向调用者透露跨企业记录是否存在。
        if (order == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工单不存在");
        return order;
    }
}
