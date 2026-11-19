package com.ai;

import com.ai.tool.ReadonlyToolRegistry;
import com.ai.tool.data.ToolInvocationStore;
import com.auth.model.TenantAccess;
import com.auth.service.CurrentAccessService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 资料与任务协调层；数据库短事务与外部HTTP严格分离。 */
@Service
public class AiService {
    private static final Logger LOG = LoggerFactory.getLogger(AiService.class);
    private final AiSettings settings;
    private final CurrentAccessService access;
    private final AiAuthority authority;
    private final AiDocumentStore documents;
    private final AiTaskStore tasks;
    private final AiPythonClient python;
    private final AiTokenCodec codec;
    private final ReadonlyToolRegistry toolRegistry;
    private final ToolInvocationStore toolInvocations;
    private final ObjectMapper json;
    private final ThreadPoolExecutor workers;
    private final ConcurrentHashMap<Long, FutureTask<Void>> active = new ConcurrentHashMap<>();

    public AiService(AiSettings settings, CurrentAccessService access, AiAuthority authority,
                     AiDocumentStore documents, AiTaskStore tasks, AiPythonClient python,
                     AiTokenCodec codec, ReadonlyToolRegistry toolRegistry,
                     ToolInvocationStore toolInvocations, ObjectMapper json) {
        this.settings = settings;
        this.access = access;
        this.authority = authority;
        this.documents = documents;
        this.tasks = tasks;
        this.python = python;
        this.codec = codec;
        this.toolRegistry = toolRegistry;
        this.toolInvocations = toolInvocations;
        this.json = json;
        AtomicInteger sequence = new AtomicInteger();
        this.workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), runnable -> {
            Thread thread = new Thread(runnable, "ai-query-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    private void enabled() {
        if (!settings.isEnabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI_DISABLED");
    }

    private TenantAccess actor(boolean admin) {
        // AI关闭时不触发额外查询。
        enabled();
        // 身份与企业只来自现有认证链。
        var current = access.requireTenantAccess();
        if (admin) access.requireTenantAdminTenantId();
        return current;
    }

    private AiAuthority.Subject subject(boolean admin) {
        // 复用当前企业访问校验。
        var current = actor(admin);
        // 再查一次数据库，防止缓存旧结论。
        var subject = authority.validate(current.getTenant().getId(), current.getUserId());
        if (admin && !subject.admin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "AI_ADMIN_REQUIRED");
        return subject;
    }

    public IPage<AiViews.Document> list(int current, int size, String keyword) {
        var currentAccess = actor(false);
        if (current < 1 || current > 1000000 || size < 1 || size > 100 || keyword == null || keyword.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI_INVALID_REQUEST");
        }
        var subject = authority.validate(currentAccess.getTenant().getId(), currentAccess.getUserId());
        return documents.list(currentAccess.getTenant().getId(), current, size, keyword, subject.admin());
    }

    public AiViews.Document detail(long id) {
        var current = actor(false);
        var subject = authority.validate(current.getTenant().getId(), current.getUserId());
        return documents.detail(current.getTenant().getId(), id, subject.admin())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AI_DOCUMENT_NOT_FOUND"));
    }

    /** 执行流程 1管理员校验 2文件校验与落盘 3建资料 4写版本与片段 5返回详情。 */
    public AiViews.Document upload(MultipartFile file, String documentCode, String title) {
        var subject = subject(true);
        String code = documentCode == null ? "" : documentCode.trim();
        String safeTitle = title == null ? "" : title.trim();
        if (!code.matches("[A-Za-z0-9_-]{1,64}") || safeTitle.isEmpty() || safeTitle.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI_INVALID_REQUEST");
        }
        StoredFile stored = store(file, subject.tenantId());
        long documentId;
        try {
            documentId = documents.create(subject.tenantId(), subject.userId(), code, safeTitle);
        } catch (RuntimeException failure) {
            deleteQuietly(stored.path());
            throw failure;
        }
        try {
            documents.addVersion(subject.tenantId(), documentId, stored.storageKey(), stored.format(), stored.text(), stored.bytes());
        } catch (RuntimeException failure) {
            // 版本写入失败时撤销资料主记录，避免留下空资料。
            try {
                documents.delete(subject.tenantId(), documentId);
            } catch (RuntimeException ignored) {
                // 保留原始失败。
            }
            deleteQuietly(stored.path());
            throw failure;
        }
        return documents.detail(subject.tenantId(), documentId, true).orElseThrow();
    }

    public AiViews.Document addVersion(long documentId, MultipartFile file) {
        var subject = subject(true);
        documents.detail(subject.tenantId(), documentId, true)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AI_DOCUMENT_NOT_FOUND"));
        StoredFile stored = store(file, subject.tenantId());
        try {
            documents.addVersion(subject.tenantId(), documentId, stored.storageKey(), stored.format(), stored.text(), stored.bytes());
        } catch (RuntimeException failure) {
            deleteQuietly(stored.path());
            throw failure;
        }
        return documents.detail(subject.tenantId(), documentId, true).orElseThrow();
    }

    public void deleteDocument(long documentId) {
        var subject = subject(true);
        documents.delete(subject.tenantId(), documentId);
    }

    /** 执行流程 1身份与参数 2选取可用版本 3幂等创建任务 4异步执行。 */
    public AiViews.Task ask(AiRequests.Question request) {
        var subject = subject(false);
        String question = request.question() == null ? "" : request.question().trim();
        if (question.isEmpty() || question.length() > settings.getMaxQuestionLength()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI_INVALID_REQUEST");
        }
        if (request.requestId() == null || !request.requestId().matches("[A-Za-z0-9_-]{1,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI_INVALID_REQUEST");
        }
        List<Long> selected = request.documentIds().stream().filter(id -> id != null && id > 0).distinct().toList();
        if (selected.isEmpty() || selected.size() > 20) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI_INVALID_REQUEST");
        }
        // 跨企业或已删除资料一律404，不能静默忽略。
        for (Long documentId : selected) {
            if (documents.detail(subject.tenantId(), documentId, false).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI_DOCUMENT_NOT_FOUND");
            }
        }
        var versions = documents.activeVersions(subject.tenantId(), selected);
        // 资料存在但没有可用版本时给出明确业务错误。
        if (versions.size() != selected.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI_NO_ELIGIBLE_DOCUMENT");
        }
        List<Long> versionIds = versions.stream().map(AiDocumentStore.Version::id).toList();
        String hash = AiRules.hash(question + "|" + versionIds);
        AiTaskStore.Created created = tasks.create(subject.tenantId(), subject.userId(), request.requestId(), hash,
                question, versionIds, Instant.now().plusMillis(settings.getTaskTimeoutMs()));
        if (!created.duplicate()) submit(created.task());
        return view(created.task());
    }

    public AiViews.Task task(long id) {
        var subject = subject(false);
        tasks.expire();
        AiTaskStore.Task task = tasks.get(subject.tenantId(), subject.userId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AI_TASK_NOT_FOUND"));
        return view(task);
    }

    public void cancel(long id) {
        var subject = subject(false);
        tasks.cancel(subject.tenantId(), subject.userId(), id);
        FutureTask<Void> running = active.remove(id);
        if (running != null) running.cancel(true);
    }

    /** 后台执行：领取任务、签发凭证、调Python、核对引用与事实、落库。 */
    private void execute(AiTaskStore.Task queued) {
        var started = tasks.start(queued.tenantId(), queued.id(), UUID.randomUUID().toString());
        if (started.isEmpty()) return;
        AiTaskStore.Task task = started.get();
        try {
            String toolToken = codec.issueTools(task.tenantId(), task.userId(), Long.toString(task.id()), toolRegistry.names());
            List<AiDocumentStore.ChunkRef> chunks = documents.chunks(task.tenantId(), versionIds(task));
            if (chunks.isEmpty()) {
                tasks.finish(task.tenantId(), task.id(), "INSUFFICIENT_EVIDENCE", "", json.writeValueAsString(List.of()),
                        json.writeValueAsString(List.of()), List.of(), null);
                return;
            }
            Duration remaining = Duration.between(Instant.now(), task.expiresAt());
            AiRules.PythonAnswer generated = python.query(task.id(), task.tenantId(), task.question(), chunks,
                    toolToken, toolRegistry.names(), remaining);
            var verified = toolRegistry.verifyFacts(generated.businessFacts(),
                    tool -> toolInvocations.succeeded(task.tenantId(), task.id(), tool));
            List<String> warnings = new ArrayList<>(generated.warnings() == null ? List.of() : generated.warnings());
            if (verified.dropped()) warnings.add("INVALID_BUSINESS_FACTS");
            AiRules.PythonAnswer checked = new AiRules.PythonAnswer(generated.state(), generated.answer(),
                    generated.citations(), verified.facts(), warnings);
            AiRules.Answer answer = AiRules.resolve(checked, documents.citations(task.tenantId(), versionIds(task)));
            tasks.finish(task.tenantId(), task.id(), answer.state(), answer.answer(),
                    json.writeValueAsString(answer.warnings()), json.writeValueAsString(answer.businessFacts()),
                    answer.citations(), null);
        } catch (ResponseStatusException failure) {
            finishFailed(task, failure.getReason() == null ? "AI_FAILED" : failure.getReason());
        } catch (RuntimeException failure) {
            LOG.warn("AI任务执行失败 task={} tenant={}", task.id(), task.tenantId(), failure);
            finishFailed(task, "AI_INTERNAL_ERROR");
        }
    }

    private void finishFailed(AiTaskStore.Task task, String code) {
        try {
            tasks.finish(task.tenantId(), task.id(), "FAILED", "", json.writeValueAsString(List.of(code)),
                    json.writeValueAsString(List.of()), List.of(), code);
        } catch (RuntimeException failure) {
            LOG.error("AI任务失败落库异常 task={} tenant={} code={}", task.id(), task.tenantId(), code, failure);
        }
    }

    private void submit(AiTaskStore.Task task) {
        FutureTask<Void> future = new FutureTask<>(() -> {
            try {
                execute(task);
            } finally {
                // 完成或失败后移除，避免任务表无限增长。
                active.remove(task.id());
            }
            return null;
        });
        active.put(task.id(), future);
        try {
            workers.execute(future);
        } catch (RejectedExecutionException rejected) {
            active.remove(task.id());
            finishFailed(task, "AI_BUSY");
        }
    }

    private AiViews.Task view(AiTaskStore.Task task) {
        List<AiViews.Citation> citations = List.of();
        List<Object> facts = List.of();
        List<String> warnings = new ArrayList<>(task.warnings());
        if ("SUCCEEDED".equals(task.state())) {
            Map<String, AiRules.Citation> available = new LinkedHashMap<>();
            for (AiRules.Citation citation : documents.citations(task.tenantId(), versionIds(task))) {
                available.put(citation.chunkId(), citation);
            }
            List<Long> ids = tasks.citationIds(task.tenantId(), task.id());
            List<AiViews.Citation> resolved = new ArrayList<>();
            for (Long id : ids) {
                AiRules.Citation citation = available.get(Long.toString(id));
                if (citation != null) {
                    resolved.add(new AiViews.Citation(citation.chunkId(), citation.documentId(), citation.versionId(),
                            citation.section(), citation.quote()));
                }
            }
            if (!ids.isEmpty() && resolved.isEmpty()) warnings.add("SOURCE_DELETED");
            citations = resolved;
            facts = parseFacts(task.businessFactsJson());
        }
        return new AiViews.Task(task.id(), task.state(), task.question(),
                "SUCCEEDED".equals(task.state()) ? task.answer() : "", citations, facts,
                warnings.stream().distinct().limit(20).toList(), task.errorCode(), task.createdAt(), task.updatedAt());
    }

    private List<Long> versionIds(AiTaskStore.Task task) {
        // 任务中的版本快照以字符串持久化，读取时恢复为数据库主键。
        List<Long> ids = new ArrayList<>();
        for (String value : task.selectedVersions()) {
            try {
                ids.add(Long.valueOf(value));
            } catch (NumberFormatException ignored) {
                // 非法历史值不进入查询。
            }
        }
        return ids;
    }

    private List<Object> parseFacts(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            @SuppressWarnings("unchecked")
            List<Object> facts = (List<Object>) json.readValue(value, List.class);
            return facts.stream().limit(20).toList();
        } catch (RuntimeException failure) {
            return List.of();
        }
    }

    @Scheduled(fixedDelay = 5000)
    public void watchdog() {
        if (!settings.isEnabled()) return;
        try {
            int expired = tasks.expire();
            if (expired > 0) LOG.info("AI任务超时置EXPIRED count={}", expired);
        } catch (RuntimeException failure) {
            LOG.warn("AI超时看门狗执行失败", failure);
        }
    }

    @PreDestroy
    public void close() {
        workers.shutdownNow();
    }

    private StoredFile store(MultipartFile file, long tenant) {
        if (file == null || file.isEmpty() || file.getSize() <= 0 || file.getSize() > settings.getMaxBytes()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI_INVALID_FILE");
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (original.contains("..") || original.contains("/") || original.contains("\\")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI_INVALID_FILE");
        }
        int dot = original.lastIndexOf('.');
        String extension = dot < 0 ? "" : original.substring(dot).toLowerCase();
        if (!List.of(".md", ".txt").contains(extension)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI_INVALID_FILE");
        }
        try {
            byte[] bytes = file.getBytes();
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            String key = tenant + "/" + UUID.randomUUID() + extension;
            Path root = Path.of(settings.getDocRoot()).toAbsolutePath().normalize();
            Path target = root.resolve(key).normalize();
            if (!target.startsWith(root)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI_INVALID_FILE");
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            return new StoredFile(key, text, extension.substring(1), bytes.length, target);
        } catch (CharacterCodingException encoding) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI_INVALID_ENCODING");
        } catch (IOException io) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI_STORAGE_UNAVAILABLE");
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败不影响原始错误。
        }
    }

    private record StoredFile(String storageKey, String text, String format, long bytes, Path path) {
    }
}
