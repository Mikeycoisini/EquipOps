package com.inventory.service.impl;

import com.auth.model.TenantAccess;
import com.auth.service.CurrentAccessService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.equipops.domain.dto.inventory.InventoryExportCreateDto;
import com.equipops.domain.entity.InventoryExportTaskEntity;
import com.equipops.domain.entity.SparePartEntity;
import com.equipops.domain.entity.WarehouseEntity;
import com.equipops.domain.entity.WorkOrderEntity;
import com.equipops.domain.vo.InventoryExportTaskVO;
import com.inventory.mapper.InventoryExportTaskMapper;
import com.inventory.mapper.SparePartMapper;
import com.inventory.service.InventoryExportService;
import com.tenant.mapper.WarehouseMapper;
import com.workorder.mapper.WorkOrderMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.inventory.export.InventoryExportRejectedException;
import com.inventory.export.InventoryXlsxWriter;
import com.equipops.domain.enums.InventoryExportStatus;
import com.equipops.domain.vo.InventoryTransactionVO;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.ExecutorService;

/** 创建事务提交后异步导出；执行状态和租约由数据库条件更新保护。 */
@Service
public class InventoryExportServiceImpl implements InventoryExportService {
    @Autowired
    private  CurrentAccessService currentAccessService;
    @Autowired
    private  InventoryExportTaskMapper taskMapper;
    @Autowired
    // 用来执行需要放在同一个事务里的数据库操作。明确划出一小段需要事务保护的数据库操作。
    private TransactionTemplate transactionTemplate;
    @Autowired
    private WarehouseMapper warehouseMapper;
    @Autowired
    private SparePartMapper sparePartMapper;
    @Autowired
    private WorkOrderMapper workOrderMapper;
    @Autowired
    @Qualifier("exportThreadPool")
    private ExecutorService exportThreadPool;

    /**
     * 优先读取 inventory.export.root 配置
     * 没有配置时，使用：
     * 当前系统用户目录/.equipops/inventory-exports
     */
    @Value("${inventory.export.root:${user.home}/.equipops/inventory-exports}")
    private String exportRoot;
    @Value("${inventory.export.min-free-bytes:2147483648}")
    private long minFreeBytes = 2147483648L;
    private static final long MAX_FILE_BYTES = 512L * 1024 * 1024;
    private static final long MAX_ROWS = 100000;
    private static final Logger LOGGER = LoggerFactory.getLogger(InventoryExportServiceImpl.class);
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final Map<Long,InventoryExportTaskEntity> ownedTasks = new ConcurrentHashMap<>();
    private final Map<Long,FutureTask<Void>> executions = new ConcurrentHashMap<>();
    private ScheduledExecutorService maintenance;

    /** 本进程启动时生成一次；重启后使用新的实例标识。 */
    private final String instanceId = UUID.randomUUID().toString();

    /** 分页查询本人在当前企业创建的任务，管理员也遵守本人范围。 */
    @Override
    public com.baomidou.mybatisplus.core.metadata.IPage<InventoryExportTaskVO> getTaskPage(
            com.equipops.domain.dto.inventory.InventoryExportPageQueryDto query) {
        // 1. 重新验证当前账号、企业和成员资格，取得同一次可信身份中的tenantId与userId；失效时停止查询。
        // 2. 检查分页条件对象非空，空参数返回400；可选status沿用既有枚举，不接收客户端身份或排序字段。
        // 3. 页码至少为1，每页数量收敛到1～100；检查分页乘法是否会溢出long，异常页码返回400。
        // 4. 构造VO分页对象，SQL同时限定tenantId、createdBy和可选status，按创建时间、ID倒序读取公开字段及总数。
        // 5. 返回真实分页结果，无匹配任务时保留空records和total=0；只查询数据库，不改变任务状态或生成文件。
        TenantAccess access = currentAccessService.requireTenantAccess();
        if (query == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"任务查询条件不能为空");
        long currentPage = Math.max(1,query.getCurrent());
        long pageSize = Math.max(1,Math.min(100,query.getSize()));
        if (currentPage - 1 > Long.MAX_VALUE / pageSize) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"查询页码过大");
        }
        var page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<InventoryExportTaskVO>(currentPage,pageSize);
        return taskMapper.selectOwnTaskPage(page,access.getTenant().getId(),access.getUserId(),query);
    }

    /** 查询本人在当前企业的一条任务，不可见与不存在均返回404。 */
    @Override
    public InventoryExportTaskVO getTaskDetail(Long id) {
        // 1. 重新验证当前账号、企业和成员资格，从同一个TenantAccess中取得可信tenantId与userId。
        // 2. 检查任务ID非空且大于0，非法ID返回400，不进入数据库查询。
        // 3. 复用selectOwnTask，同时以tenantId、userId、taskId限制查询，只读取公开VO字段。
        // 4. 查不到记录时统一返回404，不区分其他企业、同企业其他创建人或记录不存在，避免泄露任务存在性。
        // 5. 返回数据库当前状态、已处理条数和公开文件信息，不暴露存储键/实例/令牌/租约，不读文件或改变状态。
        TenantAccess access = currentAccessService.requireTenantAccess();
        if (id == null || id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"任务ID必须大于0");
        InventoryExportTaskVO result = taskMapper.selectOwnTask(access.getTenant().getId(),access.getUserId(),id);
        if (result == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"导出任务不存在");
        return result;
    }

    @Override
    public InventoryExportTaskVO create(InventoryExportCreateDto request) {
        //  1. 取得可信当前用户、企业；校验起止时间和关联对象归属。
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "导出筛选不能为空");
        for (Long id : new Long[]{request.getWarehouseId(),request.getSparePartId(),request.getWorkOrderId()}) {
            if (id != null && id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"筛选ID必须大于0");
        }
        if (!accepting.get()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"执行实例正在停止");
        // 本入口必须自己完成创建事务，禁止被外层长事务包住后提前提交后台执行器。
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"导出提交不能嵌套在其他业务事务中");
        }
        TenantAccess access = currentAccessService.requireTenantAccess();
        Long tenantId = access.getTenant().getId();
        Long userId = access.getUserId();
        /**
         * 检查结束时间不能早于开始时间；
         * 提供了仓库、备件或工单 ID 时，检查它属于当前企业。
         */
        if (request.getStartTime() != null && request.getEndTime() != null
                && request.getStartTime().isAfter(request.getEndTime())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "结束时间不能早于开始时间");
        }
        if (request.getWarehouseId() != null) {
            long count = warehouseMapper.selectCount(new LambdaQueryWrapper<WarehouseEntity>()
                    .eq(WarehouseEntity::getTenantId, tenantId)
                    .eq(WarehouseEntity::getId, request.getWarehouseId()));
            if (count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "仓库不存在");
        }
        if (request.getSparePartId() != null) {
            long count = sparePartMapper.selectCount(new LambdaQueryWrapper<SparePartEntity>()
                    .eq(SparePartEntity::getTenantId, tenantId)
                    .eq(SparePartEntity::getId, request.getSparePartId()));
            if (count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "备件不存在");
        }
        if (request.getWorkOrderId() != null) {
            long count = workOrderMapper.selectCount(new LambdaQueryWrapper<WorkOrderEntity>()
                    .eq(WorkOrderEntity::getTenantId, tenantId)
                    .eq(WorkOrderEntity::getId, request.getWorkOrderId()));
            if (count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工单不存在");
        }
        // 本次执行令牌保留到事务之外；拒绝处理不能从前端取得令牌。
        InventoryExportTaskEntity submitted = InventoryExportTaskEntity.builder()
                .tenantId(tenantId).createdBy(userId).ownerInstanceId(instanceId)
                .runToken(UUID.randomUUID().toString()).build();
        // 2. 在同一个事务内，先锁定成员，再检查是否已有活动任务。
        InventoryExportTaskVO task = transactionTemplate.execute(status -> {
            //  锁定成员
            Long Id = taskMapper.selectEnabledMembershipForUpdate(tenantId, userId);
            // 未找到有效成员关系时返回403。
            if (Id == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前企业成员关系已失效");
            }
            // 检查活动任务：已经有未结束任务时返回409。
            List<Long> activeTaskIds = taskMapper.selectActiveTaskIdsForUpdate(tenantId, userId);
            if (!activeTaskIds.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "当前已有未结束的导出任务");
            }
            // 3. 创建并保存任务；筛选字段由XML从request读取。
            InventoryExportTaskEntity taskEntity = submitted;
            int rows = taskMapper.insertQueuedTask(taskEntity, request, tenantId, userId);
            //检查数据是否插入成功
            if (rows != 1 || taskEntity.getId() == null) {
                throw new IllegalStateException("导出任务保存失败");
            }
            // 4. 按企业、本人和任务ID查询公开VO，返回给外面的task变量。
            InventoryExportTaskVO taskVO = taskMapper.selectOwnTask(tenantId, userId, taskEntity.getId());
            if (taskVO == null) {
                throw new IllegalStateException("创建后未查询到导出任务");
            }
            return taskVO;
        });
        // 5. 确认创建任务的事务已成功提交，取得后续提交所需的任务信息。
        // 第1～4步已实现：execute返回时事务已提交，不能在事务内部投递任务。
        if (task == null || task.getId() == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"导出任务提交结果缺失");
        }

        // 6. 将已保存的任务ID提交给exportThreadPool，由后台调用executeExport(taskId)。
        //    在try中调用线程池execute，将task.getId()传给后台方法。
        //    这里只安排后台工作，不在当前请求线程里查询流水或生成Excel。
        //    线程池接受后即可继续第8步，不等待Excel生成完成。
        //    后台方法内部的异常由executeExport自己的失败流程处理；外层try不能接住它。
        //    提交动作抛出RejectedExecutionException时，进入第7步，不能返回202成功。

        ownedTasks.put(task.getId(),submitted);
        FutureTask<Void> execution = new FutureTask<>(() -> { executeExport(task.getId()); return null; });
        executions.put(task.getId(),execution);
        try {
            exportThreadPool.execute(execution);
        } catch (RejectedExecutionException rejected) {
            ownedTasks.remove(task.getId());
            executions.remove(task.getId());
        // 7. 线程池拒绝任务时，用新的短事务处理已保存的任务记录。
        //    按任务ID、当前企业、创建人、本实例和本次执行令牌，条件更新QUEUED→REJECTED。
        //    记录“执行器容量不足”的公开原因和结束时间，使任务不再占用活动任务名额。
        //    本次执行令牌在提交事务前生成并保留，或从可信内部任务记录读取，不能由前端传入。
        //    检查影响行数；若任务已取消、租约失效或进入其他终态，不强行覆盖，重新确认状态。
        //    拒绝结果落库后，返回HTTP429，并在响应中保留任务ID，便于用户查询。
        //    若数据库更新失败，则返回服务端错误并记录日志，不能声称已成功标记REJECTED；
        //    未能更新的活动任务，后续还需要由租约回收流程处理，不能永久卡在QUEUED。

            try {
                Integer changed = transactionTemplate.execute(status -> taskMapper.rejectQueued(submitted));
                if (changed == null || changed != 1) {
                    InventoryExportTaskVO latest = taskMapper.selectOwnTask(tenantId,userId,task.getId());
                    if (latest == null || latest.getStatus() == InventoryExportStatus.QUEUED
                            || latest.getStatus() == InventoryExportStatus.RUNNING
                            || latest.getStatus() == InventoryExportStatus.CANCEL_REQUESTED) {
                        throw new IllegalStateException("拒绝状态未成功保存");
                    }
                }
            } catch (RuntimeException failure) {
                LOGGER.error("导出任务拒绝结果保存失败，taskId={}",task.getId(),failure);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"任务拒绝结果保存失败，请稍后查询任务状态");
            }
            throw new InventoryExportRejectedException(task.getId());
        }

        // 8. 线程池提交成功后，按企业、创建人和任务ID查询最新的公开VO并返回。
        //    后台可能已经将状态推进到RUNNING或终态，不能把响应状态强行写回QUEUED。
        //    Controller将VO包装为HTTP202，只表示任务已接收，不表示文件已生成。
        //    后续由前端轮询任务状态，成功后再调用授权下载接口。

        InventoryExportTaskVO latest = taskMapper.selectOwnTask(tenantId,userId,task.getId());
        if (latest == null) throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"导出任务读取失败");
        return latest;
    }
    /**
     * 取消事务提交后，仅释放本实例中已排队任务的线程资源。
     * 由同包的下载服务在取消成功后调用；跨实例仍依靠数据库状态阻止任务启动。
     */
    void releaseLocalCancelledExecution(Long id) {
        // 仅对排队取消结果尽力取消本实例对应Future并移出队列；不按线程名中断，不在请求中删除文件。
        FutureTask<Void> execution = executions.get(id);
        if (execution != null) {
            execution.cancel(false);
            executions.remove(id,execution);
            ownedTasks.remove(id);
            try {
                if (exportThreadPool instanceof ThreadPoolExecutor pool) pool.remove(execution);
            } catch (RuntimeException failure) {
                LOGGER.warn("导出任务已取消，本地队列将在启动检查或维护时释放，taskId={}",id,failure);
            }
        }
    }

    /**
     * 第5步：后台真正生成Excel（对应讲解中的第五步）。
     * 完整顺序：查任务→检查QUEUED及租约→复核权限→改RUNNING→记录流水ID上界
     * →每批1000条写Excel→每批检查取消/超时/权限/租约→关闭并发布文件→改SUCCEEDED。
     * 下列1～9项依次展开这个完整流程，第10项说明失败和取消的清理。
     * 由create在事务提交后投递；下列步骤均已有实现，数据库与文件联调需另行验证。
     */
    private void executeExport(Long taskId) {
        InventoryExportTaskEntity task = null;
        Path directory = null;
        Path part = null;
        Path result = null;
        boolean success = false;
        // 仅实际取得RUNNING执行资格的线程才负责清理该任务文件。
        boolean ownsExecution = false;
        boolean createdDirectory = false;
        boolean publicationUncertain = false;
        try {
            // 1. 根据taskId查询内部任务记录。
            //    取得任务的企业、创建人、筛选条件、执行令牌；任务不存在则结束。

            task = taskMapper.selectById(taskId);
            if (task == null) return;

            // 2. 检查任务是否仍为QUEUED、是否属于当前执行实例、租约是否有效。
            //    已取消、已结束、租约过期或不是本实例任务，都不能继续生成文件。

            if (!instanceId.equals(task.getOwnerInstanceId()) || !taskMapper.isOwnedQueued(task)) return;

            // 3. 根据任务中保存的createdBy（用户ID）和tenantId，重新检查权限。
            //    查询账号、企业和成员关系是否仍有效；后台不能从请求Session取得身份。

            requirePermission(task);

            // 4. 条件更新任务状态：QUEUED → RUNNING。
            //    同时匹配执行实例、执行令牌及有效租约；更新不成功就停止本次执行。
            //    记录开始时间和本次执行的超时截止时间。

            if (taskMapper.startRunning(task) != 1) return;
            ownsExecution = true;

            // 5. 记录本次允许导出的流水ID上界。
            //    在任务所属企业内取得当前可见流水的最大ID，保存为upperBoundId。
            //    后续只查询不超过该上界的流水，避免通常后续新增记录不断进入导出。
            //    这不等于严格的数据库时间点快照。

            Long upperBound = taskMapper.selectUpperBound(task.getTenantId());
            if (upperBound == null || taskMapper.saveUpperBound(task,upperBound) != 1) {
                throw new ExportStopped("EXECUTION_LOST","任务执行资格已失效");
            }
            task.setUpperBoundId(upperBound);

            // 6. 按企业、任务筛选条件及ID游标，每次查询1000条，逐行写入临时Excel。
            //    只查 lastId < id <= upperBoundId，按ID升序；写完本批后推进lastId。
            //    没有下一批时结束；无匹配数据时也保留表头，超过10万条则明确失败。

            directory = taskDirectory(task);
            Files.createDirectory(directory);
            createdDirectory = true;
            part = directory.resolve("inventory.part");
            result = directory.resolve("inventory.xlsx");
            long processed = 0;
            long lastId = 0;
            try (InventoryXlsxWriter workbook = new InventoryXlsxWriter(part)) {
                while (true) {
                    checkExecution(task);
                    checkDisk(directory,part);
                    List<InventoryTransactionVO> batch = taskMapper.selectExportBatch(task,lastId);
                    checkExecution(task); // 查询阻塞恢复后必须重新检查，不能继续发布旧任务。
                    if (batch.isEmpty()) break;
                    if (processed + batch.size() > MAX_ROWS) throw new ExportStopped("ROW_LIMIT","导出超过10万条，请缩小筛选范围");
                    for (InventoryTransactionVO row : batch) {
                        if (row.getId() == null || row.getId() <= lastId || row.getId() > upperBound) {
                            throw new ExportStopped("QUERY_FAILED","导出流水顺序异常");
                        }
                        if (Thread.currentThread().isInterrupted()) throw new ExportStopped("INTERRUPTED","导出已中断");
                        workbook.append(row); lastId=row.getId(); processed++;
                        if (processed % 100 == 0) { workbook.flush(); checkDisk(directory,part); }
                    }

            // 7. 每批处理时检查是否取消、是否超时、权限和租约是否仍然有效。
            //    同时检查磁盘等资源限制；发现不能继续时停止查询和写入，进入清理流程。
            //    租约按数据库时间续期，已过期的租约不能复活。

                    checkExecution(task);
                    workbook.flush(); checkDisk(directory,part);
                    if (taskMapper.updateProgress(task,processed) != 1) throw new ExportStopped("EXECUTION_LOST","任务执行资格已失效");
                }
            }

            // 8. 全部写完后关闭工作簿和输出流，清理Excel底层临时文件，再发布结果文件。
            //    发布前再次检查权限、状态和租约；使用受控路径，将临时文件重命名为结果文件。
            //    此时还不能把文件作为成功结果提供下载，需要继续完成第9步。

            checkExecution(task); checkDisk(directory,part);
            // 同目录内原子重命名；文件系统不支持时明确失败，不提供半成品。
            Files.move(part,result,StandardCopyOption.ATOMIC_MOVE);
            checkExecution(task); checkDisk(directory,result);

            // 9. 条件更新任务状态：RUNNING → SUCCEEDED。
            //    再次匹配执行令牌和有效租约，保存文件存储键、文件名、大小、条数及时间。
            //    记录完成时间和24小时文件有效期；更新失败时不能提供成功下载。

            String key = storageRoot().relativize(result).toString().replace('\\','/');
            long fileSize = Files.size(result);
            publicationUncertain = true;
            int updated = taskMapper.publishSuccess(task,key,"库存流水-"+taskId+".xlsx",fileSize,processed);
            publicationUncertain = false;
            if (updated != 1) {
                throw new ExportStopped("EXECUTION_LOST","任务发布条件已变化");
            }
            success = true;

        // 10. 异常或取消时关闭资源，清理临时文件和未成功发布的结果文件。
        //     按当前状态、执行令牌及对应规则记录FAILED或CANCELLED，不能覆盖已有终态。
        //     保存可公开的原因和结束时间，不向前端暴露SQL、堆栈或服务器绝对路径。
        } catch (Exception failure) {
            String code = failure instanceof ExportStopped stopped ? stopped.code
                    : failure instanceof IOException ? "IO_FAILED" : "QUERY_FAILED";
            String message = failure instanceof ExportStopped ? failure.getMessage()
                    : failure instanceof IOException ? "导出文件生成失败，请稍后重试" : "导出数据读取失败，请稍后重试";
            if (task != null && instanceId.equals(task.getOwnerInstanceId())) {
                try { taskMapper.finishUnsuccessful(task,code,message); }
                catch (RuntimeException updateFailure) { LOGGER.error("导出失败状态保存失败，taskId={}",taskId,updateFailure); }
            }
            LOGGER.warn("导出执行停止，taskId={}, code={}",taskId,code,failure);
        } finally {
            executions.remove(taskId);
            ownedTasks.remove(taskId);
            // 发布写入发生网络异常时，数据库可能已提交；无法确认时保留文件交给维护器回收。
            boolean retain = success;
            if (publicationUncertain) {
                try {
                    InventoryExportTaskEntity latest = taskMapper.selectById(taskId);
                    retain = latest != null && latest.getStatus() == InventoryExportStatus.SUCCEEDED
                            && task.getRunToken().equals(latest.getRunToken());
                } catch (RuntimeException uncertain) { retain=true; LOGGER.error("导出发布结果待核实，taskId={}",taskId,uncertain); }
            }
            if (!retain && ownsExecution && createdDirectory) removeDirectory(directory);
        }
    }

    private void requirePermission(InventoryExportTaskEntity task) {
        // 1. 按内部任务的企业和用户重查三项启用状态；撤权立即停止后续批次及发布。
        if (!taskMapper.hasExportAccess(task)) throw new ExportStopped("ACCESS_REVOKED","账号、企业或成员关系已失效");
    }

    private void checkExecution(InventoryExportTaskEntity task) {
        // 1. 检查中断，再用数据库时钟检查RUNNING、令牌、租约和5分钟截止时间，最后复核权限。
        if (Thread.currentThread().isInterrupted()) throw new ExportStopped("INTERRUPTED","导出已中断");
        if (!taskMapper.isRunningLive(task)) throw new ExportStopped("EXECUTION_LOST","任务已取消、超时或执行租约失效");
        requirePermission(task);
    }

    private Path storageRoot() throws IOException {
        // 1. 配置必须是绝对路径；解析为真实受控根目录，同机实例须配置相同目录。
        Path root = Path.of(exportRoot).toAbsolutePath().normalize();
        if (!Path.of(exportRoot).isAbsolute()) throw new IOException("导出根目录必须为绝对路径");
        Files.createDirectories(root);
        return root.toRealPath();
    }

    private Path taskDirectory(InventoryExportTaskEntity task) throws IOException {
        // 1. 只接受数据库生成ID和服务端UUID令牌，禁止使用筛选/备注等用户文本拼路径。
        if (task.getId() == null || task.getId() <= 0 || task.getRunToken() == null
                || !task.getRunToken().matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")) throw new IOException("任务存储键无效");
        Path root = storageRoot();
        Path path = root.resolve(task.getId()+"-"+task.getRunToken()).normalize();
            if (!path.startsWith(root) || Files.isSymbolicLink(path)
                    || Files.exists(path,LinkOption.NOFOLLOW_LINKS)) throw new IOException("任务目录无效或已存在");
        return path;
    }

    private void checkDisk(Path directory,Path file) throws IOException {
        // 1. 每批及每100行检查512MiB任务文件上限和卷剩余空间；这不是操作系统硬配额。
        if (Files.getFileStore(directory).getUsableSpace() < minFreeBytes) throw new ExportStopped("DISK_LIMIT","导出目录可用空间不足");
        if (Files.exists(file) && Files.size(file) > MAX_FILE_BYTES) throw new ExportStopped("FILE_LIMIT","导出文件超过大小上限");
    }

    private void removeDirectory(Path directory) {
        // 1. 重新验证绝对目标在真实根目录的一层UUID任务目录内；不跟随链接，再由叶到根删除。
        try {
            Path root=storageRoot(); Path target=directory.toAbsolutePath().normalize();
            if (!root.equals(target.getParent()) || !target.getFileName().toString().matches("[1-9][0-9]*-[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")
                    || Files.isSymbolicLink(target)) throw new IOException("拒绝清理无效任务目录");
            if (!Files.exists(target,LinkOption.NOFOLLOW_LINKS)) return;
            if (!target.toRealPath().equals(target)) throw new IOException("拒绝跟随任务目录链接");
            try (var paths=Files.walk(target)) {
                for (Path path:paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!Files.isSymbolicLink(path) && !path.toRealPath().startsWith(target)) throw new IOException("清理路径越界");
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException failure) { LOGGER.warn("导出文件清理延后重试",failure); }
    }

    @PostConstruct
    public void initializeExportMaintenance() throws IOException {
        // 1. 启动先验证共享文件根目录可写；每5秒续租，每10秒回收超时/失联任务与到期文件。
        Path root=storageRoot();
        if (!Files.isWritable(root)) throw new IOException("导出根目录不可写");
        maintenance=Executors.newScheduledThreadPool(3,r -> {
            Thread thread=new Thread(r,"inventory-export-maintenance"); thread.setDaemon(true); return thread;
        });
        maintenance.scheduleWithFixedDelay(this::renewExportLeases,5,5,TimeUnit.SECONDS);
        maintenance.scheduleWithFixedDelay(this::maintainExportTasks,10,10,TimeUnit.SECONDS);
        maintenance.scheduleWithFixedDelay(this::cleanExportFiles,10,10,TimeUnit.SECONDS);
    }

    void renewExportLeases() {
        // 1. 由独立定时工作续租，磁盘清理/状态扫描不能阻塞每5秒一次的心跳。
        //    每条更新匹配ID、企业、用户、实例、令牌、活动状态和未到期租约；过期不复活。
        if (!accepting.get()) return;
        for (InventoryExportTaskEntity task : ownedTasks.values()) {
            try { taskMapper.renewOwnedLeases(task); }
            catch (RuntimeException failure) { LOGGER.error("导出任务续租失败，taskId={}",task.getId(),failure); }
        }
    }

    void maintainExportTasks() {
        // 1. 每10秒按数据库时间竞争超时/失联/取消终态，并标记成功文件过期。
        if (!accepting.get()) return;
        try {
            taskMapper.reapExpiredTasks(); taskMapper.expireFiles();
            // 2. 使用绑定任务的Future取消。即使数据库查询期间A已结束、线程接手B，也不会中断B。
            for (var entry:executions.entrySet()) {
                InventoryExportTaskEntity task=taskMapper.selectById(entry.getKey());
                boolean live=task != null && instanceId.equals(task.getOwnerInstanceId())
                        && (taskMapper.isOwnedQueued(task) || taskMapper.isRunningLive(task));
                if (!live) {
                    FutureTask<Void> execution=entry.getValue();
                    execution.cancel(true);
                    executions.remove(entry.getKey(),execution); ownedTasks.remove(entry.getKey());
                    // 排队取消尽力立即释放队列；已开始运行的取消由worker自身清理文件。
                    if (exportThreadPool instanceof ThreadPoolExecutor pool) pool.remove(execution);
                }
            }
        } catch (RuntimeException failure) { LOGGER.error("导出任务状态维护失败，将在下轮重试",failure); }
    }

    void cleanExportFiles() {
        // 1. 文件清理由独立定时工作执行；先确认数据库允许回收，数据库异常时保留文件。
        // 2. 流式扫描UUID任务目录，不把整个共享目录加载进内存，不跟随链接。
        if (!accepting.get()) return;
        try (var directories=Files.list(storageRoot())) {
            var iterator=directories.iterator();
            while (iterator.hasNext() && accepting.get()) {
                Path directory=iterator.next();
                String name=directory.getFileName().toString();
                if (!name.matches("[1-9][0-9]*-[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}") || Files.isSymbolicLink(directory)) continue;
                int split=name.indexOf('-');
                long id;
                try { id=Long.parseLong(name.substring(0,split)); } catch (NumberFormatException invalid) { continue; }
                if (!taskMapper.shouldKeepFiles(id,name.substring(split+1))) removeDirectory(directory);
            }
        } catch (Exception failure) { LOGGER.error("导出文件清理失败，将在下轮重试",failure); }
    }

    @PreDestroy
    public void stopExportMaintenance() {
        // 1. 停止接收和续租并中断专用执行器；未启动/未能终止的任务由其他实例或重启后回收过期租约。
        accepting.set(false);
        if (maintenance != null) maintenance.shutdownNow();
        exportThreadPool.shutdownNow();
    }

    private static final class ExportStopped extends RuntimeException {
        private final String code;
        private ExportStopped(String code,String message) { super(message); this.code=code; }
    }
}
