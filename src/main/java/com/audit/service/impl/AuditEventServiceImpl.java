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
import com.fasterxml.jackson.core.JsonProcessingException;
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
 * 审计事件业务骨架。当前只列出执行步骤，业务尚未实现。
 * 查询必须由企业管理员发起；写入身份只能来自 CurrentAccessService 或系统任务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditEventServiceImpl implements AuditEventService {

    private final AuditEventMapper auditEventMapper;
    private final CurrentAccessService currentAccessService;
    private final ObjectMapper objectMapper;

    /** 单个字符串值最多保留多少字符。 */
    private static final int MAX_STRING_LENGTH = 200;

    /** changes 序列化后允许的最大字符数。 */
    private static final int MAX_JSON_LENGTH = 4000;

    /** 键名里出现这些词，就把整项丢掉。 */
    private static final List<String> SENSITIVE_KEY_PARTS = List.of(
            "password", "passwd", "token", "secret",
            "credential", "authorization", "sessionid");

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
        long offset = (current - 1) * size;//跳过多少条数据

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
        if (id == null || id < 0){
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
                vo.setChanges(changes);
            } catch (JacksonException e){
                log.warn("审计事件changes_json 解析失败，id={}", id, e);
            }
        }
        // 5. 按与列表相同的白名单规则生成 summary，返回详情 VO。
        vo.setSummary(buildSummary(vo.getChanges()));
        return vo;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendSuccess(AuditAppendCommand command) {

        // 1. 校验编程参数：command、tenantId、actorUserId、action、objectType 不能为空；
        //    缺失属于调用方缺陷，抛 IllegalArgumentException，不伪装成 4xx 业务错误。
        //    objectId 可空，但传了必须 >0。
        //
        if (command == null) {
            throw new IllegalArgumentException("审计命令不能为空");
        }
        if (command.getTenantId() == null
                || command.getActorUserId() == null
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

        // 3. 二次脱敏 changes：
        //    - 键名黑名单：password、passwd、token、secret、credential、
        //      authorization、sessionId；
        //    - 只保留 String/Number/Boolean/null，字符串按长度上限截断；
        //    - 嵌套 Map/List 不原样保存，改为长度或元素个数摘要；
        //    - 序列化后超过总长度上限（例如 4000 字符）时只保留键名列表。
        //sanitizeChanges(...)：调用私有方法，做脱敏
        Map<String, Object> safeChanges = sanitizeChanges(command.getChanges());

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
        //    actorType=USER、outcome=SUCCESS、occurredAt=当前时间，
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
        entity.setOutcome(AuditOutcome.SUCCESS);
        entity.setRequestId(requestId);
        entity.setChangesJson(changesJson);
        entity.setOccurredAt(LocalDateTime.now());
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
        // 5. insert 失败只记录 log.warn，不把审计异常覆盖到调用方原始异常。
        //    独立事务由 Spring 代理开启，不能在类内自调用本方法。
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "审计失败事件尚未实现");
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
        //    insert 失败只记日志，不覆盖调用方原始异常。
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "审计拒绝事件尚未实现");
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
        if (field != null && (before != null || after != null)) {
            return field + "：[" + before + "] -> [" + after + "]";
        }
        return null;
    }

    /** 脱敏：黑名单键整项丢弃；只保留简单类型；嵌套结构只记数量。 */
    private Map<String, Object> sanitizeChanges(Map<String, Object> changes) {
        if (changes == null || changes.isEmpty()) {
            return null;
        }

        Map<String, Object> safe = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : changes.entrySet()) {
            String key = entry.getKey();
            if (key == null || isSensitiveKey(key)) {
                continue; // 敏感键：连键名和值都不留
            }

            Object value = entry.getValue();
            if (value == null
                    || value instanceof String
                    || value instanceof Number
                    || value instanceof Boolean) {
                safe.put(key, truncate(value));
            } else if (value instanceof Map<?, ?> nested) {
                safe.put(key, "嵌套对象(" + nested.size() + "项)");
            } else if (value instanceof Collection<?> collection) {
                safe.put(key, "列表(" + collection.size() + "项)");
            } else {
                safe.put(key, value.getClass().getSimpleName());
            }
        }

        // 总长度兜底：超过 4000 字符时只保留键名。
        try {
            if (objectMapper.writeValueAsString(safe).length() > MAX_JSON_LENGTH) {
                Map<String, Object> keysOnly = new LinkedHashMap<>();
                for (String key : safe.keySet()) {
                    keysOnly.put(key, "[内容过长已省略]");
                }
                return keysOnly;
            }
        } catch (JacksonException e) {
            // 这里只是预检；真正序列化失败会在 appendSuccess 第 4 步抛 IllegalStateException。
            log.warn("审计变更摘要预检序列化失败", e);
        }
        return safe;
    }

    /** 键名包含黑名单词就认为是敏感字段，例如 accessToken、userPassword。 */
    private boolean isSensitiveKey(String key) {
        String lower = key.toLowerCase();
        for (String part : SENSITIVE_KEY_PARTS) {
            if (lower.contains(part)) {
                return true;
            }
        }
        return false;
    }

    /** 字符串太长就截断，其他类型原样返回。 */
    private Object truncate(Object value) {
        if (value instanceof String text && text.length() > MAX_STRING_LENGTH) {
            return text.substring(0, MAX_STRING_LENGTH) + "...";
        }
        return value;
    }
}
