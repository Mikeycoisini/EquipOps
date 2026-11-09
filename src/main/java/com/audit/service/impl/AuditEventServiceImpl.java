package com.audit.service.impl;

import com.audit.mapper.AuditEventMapper;
import com.audit.service.AuditEventService;
import com.auth.service.CurrentAccessService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.equipops.domain.dto.audit.AuditAppendCommand;
import com.equipops.domain.dto.audit.AuditEventPageQueryDto;
import com.equipops.domain.entity.AuditEventEntity;
import com.equipops.domain.enums.AuditActorType;
import com.equipops.domain.enums.AuditOutcome;
import com.equipops.domain.vo.AuditEventDetailVO;
import com.equipops.domain.vo.AuditEventVO;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 企业审计查询与追加实现；写入和读取均使用动作级白名单。
 * 查询必须由企业管理员发起；写入身份只能来自 CurrentAccessService 或系统任务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditEventServiceImpl implements AuditEventService {

    private final AuditEventMapper auditEventMapper;
    private final CurrentAccessService currentAccessService;
    private final ObjectMapper objectMapper;

    @Override
    public IPage<AuditEventVO> page(AuditEventPageQueryDto query) {
        // 1. 从可信上下文取当前企业：currentAccessService.requireTenantAdminTenantId()。
        //    非管理员由该方法直接抛 403；绝不能从 query 或请求体读取 tenantId。
        //
        Long tenantId = currentAccessService.requireTenantAdminTenantId();

        // 2. 校验并修正分页参数：current<1 视为 1；size<1 视为 1，size>100 截断为 100；
        //    offset=(current-1)*size。current 极大时先限制上限，避免 long 溢出。
        //
        long current = Math.max(1, query.getCurrent());//限制页数
        long size = Math.max(1, Math.min(100, query.getSize()));//限制每页的最大条数据
        long offset;
        try {
            // 使用精确乘法，拒绝溢出页码而不是向SQL传递负数或错误偏移。
            offset = Math.multiplyExact(current - 1, size);
        } catch (ArithmeticException overflow) {
            // 参数超出可表达范围时在访问数据库前返回明确400。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "页码超出允许范围");
        }

        // 3. 校验 occurredFrom/occurredTo：两者都存在时 from 不得晚于 to，否则 400。
        //判断开始时间是否为空和结束是否为空，一者为空返回400，两者不为空，进行判断“开始时间是否晚于结束时间”
        if (query.getOccurredFrom() != null && query.getOccurredTo() != null
                && query.getOccurredFrom().isAfter(query.getOccurredTo())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "开始时间不能晚于结束时间");
        }

        // 4. 调用 auditEventMapper.countByQuery(tenantId, query)；
        //    total=0 时直接返回空分页对象，不再查询列表。
        //查总数：前端分页条要显示总页数，总数为 0 时，后面不用再查列表
        //
        long total = auditEventMapper.countByQuery(tenantId, query);

        // 5. 调用 selectPage(tenantId, query, offset, size)；
        //    SQL 已固定 ORDER BY occurred_at DESC, id DESC，Service 不再二次排序。
        //
        Page<AuditEventVO> page = new Page<>(current, size);
        page.setTotal(total);

        // 6. 逐条生成 summary：
        //    - 使用 objectMapper 把 changesJson 解析为 Map；
        //    - 只按白名单键拼摘要，解析失败或为空时 summary=null 并记录告警日志；
        //    - 不把 changesJson 原文返回前端（VO 字段已标记 @JsonIgnore）。
        //
        if (total == 0) {
            page.setRecords(List.of());
            return page;
        }

        // 7. 组装 Page<AuditEventVO>：设置 records、total、current、size 后返回；
        //    无数据返回空 records，不返回 null。
        List<AuditEventVO> records = auditEventMapper.selectPage(
                tenantId, query, offset, size);
        // 8. 逐条解析摘要，内部JSON不直接进入响应字段。
        for (AuditEventVO record : records) {
            // 每条记录独立处理，异常JSON不能令整页失败或回显原文。
            try {
                // 只将结构化变更传递给既有摘要生成器。
                Map<String, Object> changes = record.getChangesJson() == null ? null
                        : readChanges(record, objectMapper.readValue(record.getChangesJson(), new TypeReference<Map<String, Object>>() {}));
                // 按已有摘要协议提供列表摘要。
                record.setSummary(buildSummary(changes));
            } catch (JacksonException malformed) {
                // 仅输出事件ID，不记录原始JSON或解析异常正文。
                log.warn("审计列表摘要解析失败，id={}", record.getId());
                // 无法解析时明确没有摘要。
                record.setSummary(null);
            }
        }
        page.setRecords(records);
        return page;

    }

    @Override
    public AuditEventDetailVO detail(Long id) {
        // 1. 调用 requireTenantAdminTenantId() 取可信企业 ID；非管理员 403。
        //
        Long tenantId = currentAccessService.requireTenantAdminTenantId();
        // 2. 校验 id：null 或 <=0 返回 400。
        //
        if (id == null || id <= 0){
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审计事件ID无效");
        }
        // 3. 调用 selectDetailById(tenantId, id)；
        //    返回 null 时抛 404，不区分“不存在”和“属于其他企业”，避免泄露存在性。
        //
        AuditEventDetailVO vo = auditEventMapper.selectDetailById(tenantId, id);
        if (vo == null){
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "审计事件不存在");
        }
        // 4. 把 changesJson 解析为 Map 填充 changes：
        //    解析失败只记录日志并令 changes=null，不把原文或异常堆栈返回前端。
        //
        String changeJson = vo.getChangesJson();
        //isBlank() 是“是不是全是空白（空格、换行这些）
        if (changeJson != null && !changeJson.isBlank()){
            try {
                //.readValue(A, B)：把 A（JSON 文字）读成 B（Java 类型）
                Map<String, Object> changes = objectMapper.readValue(
                        changeJson,
                        //告诉工具“我要转成 Map<String, Object>”
                        new TypeReference<Map<String, Object>>() {});
                vo.setChanges(readChanges(vo, changes));
            } catch (JacksonException e){
                log.warn("审计事件changes_json 解析失败，id={}", id);
                vo.setChanges(Map.of());
            }
        }
        // 5. 按与列表相同的白名单规则生成 summary，返回详情 VO。
        vo.setSummary(buildSummary(vo.getChanges()));
        return vo;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendSuccess(AuditAppendCommand command) {
        // 成功事件加入调用方事务，异常必须由业务事务感知。
        append(command, AuditOutcome.SUCCESS);
    }

    private void append(AuditAppendCommand command, AuditOutcome outcome) {

        // 1. 校验编程参数：command、tenantId、actorUserId、action、objectType 不能为空；
        //    缺失属于调用方缺陷，抛 IllegalArgumentException，不伪装成 4xx 业务错误。
        //    objectId 可空，但传了必须 >0。
        //
        if (command == null) {
            throw new IllegalArgumentException("审计命令不能为空");
        }
        if (command.getTenantId() == null || command.getTenantId() <= 0
                || command.getActorUserId() == null || command.getActorUserId() <= 0
                || command.getAction() == null
                || command.getObjectType() == null) {
            throw new IllegalArgumentException(
                    "审计命令缺少 tenantId / actorUserId / action / objectType");
        }
        if (command.getObjectId() != null && command.getObjectId() <= 0) {
            throw new IllegalArgumentException("审计对象 ID 必须大于 0");
        }

        // 2. 确定 requestId：command.requestId 非空时使用（异步任务显式传入）；
        //    否则读取 MDC 的 "requestId"（RequestCorrelationFilter 写入）；
        //    两者都没有时保持 null，不生成假编号。
        //
        String requestId = command.getRequestId();
        if (requestId == null || requestId.isBlank()) {
            //MDC 可以理解成每个线程身上的便利贴。项目里的 RequestCorrelationFilter 在每次请求进来时，会往便利贴上写一个 requestId
            requestId = MDC.get("requestId");
        }

        // 3. 按动作白名单重建变更，只接受固定布尔、ID及有限角色集合。
        //    未知字段、任意字符串和嵌套正文全部丢弃，写入与历史读取使用同一规则。
        Map<String, Object> safeChanges = outcome == AuditOutcome.SUCCESS
                ? new LinkedHashMap<>(sanitizeChanges(command.getAction(), command.getChanges())) : new LinkedHashMap<>();
        // 失败/拒绝只记录固定安全错误码，不从异常正文获取内容。
        safeChanges.remove("errorCode");
        if (outcome != AuditOutcome.SUCCESS) {
            safeChanges.put("errorCode", safeErrorCode(command.getErrorCode()));
        }

        // 4. 用 objectMapper 把脱敏结果序列化为 JSON；失败抛 IllegalStateException。
        //
        String changesJson = null;
        if (safeChanges != null && !safeChanges.isEmpty()) {
            try {
                //writeValueAsString 读作“把值写成字符串”。
                changesJson = objectMapper.writeValueAsString(safeChanges);
            } catch (JacksonException e) {
                throw new IllegalStateException("审计变更摘要序列化失败", e);
            }
        }

        // 5. 组装 AuditEventEntity：
        //    actorType=USER、outcome=入口指定结果、occurredAt=上海当前时间，
        //    写入可信 tenantId/actorUserId/action/objectType/objectId/
        //    requestId/changesJson。
        //    不写入请求正文、Cookie、IP、异常堆栈。
        //
        AuditEventEntity entity = new AuditEventEntity();
        entity.setTenantId(command.getTenantId());
        entity.setActorUserId(command.getActorUserId());
        entity.setActorType(AuditActorType.USER);
        entity.setAction(command.getAction());
        entity.setObjectType(command.getObjectType());
        entity.setObjectId(command.getObjectId());
        entity.setOutcome(outcome);
        entity.setRequestId(requestId != null && requestId.matches("[A-Za-z0-9_-]{1,36}") ? requestId : null);
        entity.setChangesJson(changesJson);
        entity.setOccurredAt(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")));
        // 6. 调用 auditEventMapper.insert(entity)；
        //    影响行数 !=1 时抛 IllegalStateException。
        //
        int affectedRows = auditEventMapper.insert(entity);
        if (affectedRows != 1) {
            throw new IllegalStateException("审计事件写入失败，影响行数=" + affectedRows);
        }
        // 7. 不捕获、不吞异常：由调用方业务事务整体回滚（M1-R2）。
        //    本方法使用 Propagation.MANDATORY，调用方必须已经开启事务。

    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void appendFailure(AuditAppendCommand command) {
        // 1. 与 appendSuccess 相同的参数校验、requestId 获取、
        //    changes 脱敏和 JSON 序列化步骤。
        //
        // 2. outcome 固定为 FAILURE；errorCode 只记录安全错误码，
        //    不保存异常消息、SQL、堆栈或请求正文。
        //
        // 3. 组装 AuditEventEntity 并调用 auditEventMapper.insert(entity)。
        //
        // 4. 本方法使用 Propagation.REQUIRES_NEW 开启独立事务：
        //    业务方法回滚时失败事件仍保留；调用方必须先捕获异常再调用，
        //    不能在 finally 中无条件调用，否则成功操作也会被记成失败。
        //
        // 5. insert 失败由事务外编排捕获并记录安全警告，不覆盖原始业务异常。
        //    独立事务由 Spring 代理开启，不能在类内自调用本方法。
        // 独立事务异常向外传播，由事务外业务编排保留原始异常。
        append(command, AuditOutcome.FAILURE);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void appendDenied(AuditAppendCommand command) {
        // 1. 与 appendSuccess 相同的参数校验、requestId 获取、
        //    changes 脱敏和 JSON 序列化步骤。
        //
        // 2. outcome 固定为 DENIED；errorCode 使用权限拒绝的安全编码，
        //    不记录用户输入的敏感内容。
        //
        // 3. 其余与 appendFailure 相同：独立事务写入，
        //    insert 失败由事务外编排捕获，保留原始业务异常。
        // 拒绝事件与失败事件分开存储，不能标记成成功。
        append(command, AuditOutcome.DENIED);
    }


    /** 只按白名单键拼摘要；不认识的键一律不输出，解析失败时传 null 就返回 null。 */
    private String buildSummary(Map<String, Object> changes) {
        //为什么要判断？因为后面要 changes.get(...)，如果 Map 是 null，调用 .get() 会直接报错
        if (changes == null || changes.isEmpty()) {
            return null;
        }
        Object field = changes.get("field");
        Object before = changes.get("before");
        Object after = changes.get("after");
        // 首单元只开放已确认的启停布尔字段，其他摘要待逐动作定义白名单。
        if ("enabled".equals(field) && before instanceof Boolean && after instanceof Boolean) {
            // 不对任意字符串、嵌套Map或List调用toString，避免敏感内容进入列表。
            return field + "：[" + before + "] -> [" + after + "]";
        }
        if ("roleCodes".equals(field) && before instanceof List<?> && after instanceof List<?>) {
            return "角色：[" + before + "] -> [" + after + "]";
        }
        return null;
    }

    /** 脱敏：黑名单键整项丢弃；只保留简单类型；嵌套结构只记数量。 */
    private Map<String, Object> readChanges(AuditEventVO event, Map<String, Object> changes) {
        // 1. 失败/拒绝历史记录不能伪装成成功变更，读取也只保留安全错误码。
        if (event.getOutcome() == AuditOutcome.FAILURE || event.getOutcome() == AuditOutcome.DENIED) {
            return Map.of("errorCode", safeErrorCode(changes == null ? null : changes.get("errorCode")));
        }
        // 2. 未知结果类型默认不展示任何旧正文。
        if (event.getOutcome() != AuditOutcome.SUCCESS) return Map.of();
        // 3. 成功记录按动作白名单重建，同时剔除历史中不应存在的错误码。
        Map<String, Object> safe = sanitizeChanges(event.getAction(), changes);
        safe.remove("errorCode");
        return safe;
    }

    private Map<String, Object> sanitizeChanges(com.equipops.domain.enums.AuditAction action, Map<String, Object> changes) {
        // 1. 默认拒绝所有未知字段和业务正文，旧记录也不能绕过读取脱敏。
        Map<String, Object> safe = new LinkedHashMap<>();
        if (changes == null || action == null) return safe;
        // 2. 启停事件仅接受固定字段名及真实布尔前后值。
        if (action == com.equipops.domain.enums.AuditAction.MEMBER_STATUS_CHANGED
                && "enabled".equals(changes.get("field"))
                && changes.get("before") instanceof Boolean && changes.get("after") instanceof Boolean) {
            safe.put("field", "enabled");
            safe.put("before", changes.get("before"));
            safe.put("after", changes.get("after"));
        }
        // 3. 角色变更仅接受已支持的企业角色编码集合，禁止嵌套或任意字符串。
        if (action == com.equipops.domain.enums.AuditAction.MEMBER_ROLES_CHANGED
                && "roleCodes".equals(changes.get("field"))) {
            List<String> before = safeRoles(changes.get("before"));
            List<String> after = safeRoles(changes.get("after"));
            if (before != null && after != null) {
                safe.put("field", "roleCodes");
                safe.put("before", before);
                safe.put("after", after);
            }
        }
        // 4. 新成员事件只保存目标用户ID、明确角色及启用标记，不保存账号和姓名。
        if (action == com.equipops.domain.enums.AuditAction.MEMBER_ADDED) {
            Object userId = changes.get("userId");
            if ((userId instanceof Long || userId instanceof Integer) && ((Number) userId).longValue() > 0) {
                safe.put("userId", Long.toString(((Number) userId).longValue()));
            }
            // 历史读取及新增写入均以十进制字符串保持BIGINT精度。
            if (userId instanceof String text && text.matches("[1-9][0-9]{0,18}")) {
                try {
                    safe.put("userId", Long.toString(Long.parseLong(text)));
                } catch (NumberFormatException invalid) {
                    // 超出数据库有符号业务ID范围的旧值不展示。
                }
            }
            List<String> roles = safeRoles(changes.get("roleCodes"));
            if (roles != null) safe.put("roleCodes", roles);
            if (changes.get("enabled") instanceof Boolean) safe.put("enabled", changes.get("enabled"));
        }
        // 5. 历史失败记录读取时仅保留已定义的安全错误码。
        if (changes.containsKey("errorCode")) safe.put("errorCode", safeErrorCode(changes.get("errorCode")));
        return safe;
    }

    private List<String> safeRoles(Object value) {
        // 1. 角色集合有严格容量，不能通过大量元素扩张审计载荷。
        if (!(value instanceof Collection<?> roles) || roles.size() > 2) return null;
        // 2. 仅允许当前已定义的两个企业角色，未知编码留待显式扩展白名单。
        for (Object role : roles) {
            if (!(role instanceof String) || !List.of("TENANT_ADMIN", "ACCEPTOR").contains(role)) return null;
        }
        // 3. 去重排序以便比较及稳定展示。
        return roles.stream().map(String.class::cast).distinct().sorted().toList();
    }

    private String safeErrorCode(Object value) {
        // 只认可固定程序错误码，未知错误归一化，绝不保存异常消息。
        return value instanceof String code && List.of("FORBIDDEN", "INVALID_ARGUMENT", "NOT_FOUND", "CONFLICT", "INTERNAL_ERROR", "UNAUTHORIZED").contains(code)
                ? code : "INTERNAL_ERROR";
    }
}
