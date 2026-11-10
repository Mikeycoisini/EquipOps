package com.inventory.service.impl;

import com.auth.service.CurrentAccessService;
import com.auth.model.TenantAccess;
import com.inventory.export.InventoryExportDownloadFile;
import com.inventory.export.InventoryExportDownloadRecord;
import com.equipops.domain.enums.InventoryExportStatus;
import com.equipops.domain.vo.InventoryExportTaskVO;
import com.inventory.mapper.InventoryExportDownloadMapper;
import com.inventory.mapper.InventoryExportTaskMapper;
import com.inventory.service.InventoryExportDownloadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import com.inventory.export.InventoryDownloadGate;
import com.inventory.export.InventoryExportFileAccess;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 下载独立业务实现；文件读写流程不放入InventoryExportServiceImpl。 */

/**
 * 本次代码由codex生成
 */
@Service
public class InventoryExportDownloadServiceImpl implements InventoryExportDownloadService {
    @Autowired private CurrentAccessService currentAccessService;
    @Autowired private InventoryExportDownloadMapper downloadMapper;
    @Autowired private InventoryExportTaskMapper taskMapper;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private InventoryExportServiceImpl exportServiceImpl;
    @Autowired private InventoryDownloadGate downloadGate;
    @Autowired private InventoryExportFileAccess fileAccess;
    @Value("${inventory.export.root:${user.home}/.equipops/inventory-exports}")
    private String exportRoot;
    private static final Logger LOGGER=LoggerFactory.getLogger(InventoryExportDownloadServiceImpl.class);

    @Override
    public InventoryExportDownloadFile openDownload(Long taskId) {
        // 1. 重新验证当前账号、企业和成员资格，身份只从可信TenantAccess取得。
        TenantAccess access = currentAccessService.requireTenantAccess();

        // 2. 校验任务ID；按可信用户占用实例和用户下载名额，超过配额立即429，不在请求线程排队。
        if (taskId == null || taskId <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"任务ID必须大于0");
        InventoryDownloadGate.Lease lease = downloadGate.acquire(access.getUserId());
        InventoryExportDownloadRecord task = null;
        InventoryExportFileAccess.PinnedDirectory directory = null;
        boolean handedOff = false;
        try {
            // 仍以企业、本人和任务ID一起查询；查询失败也进入finally归还名额。
            task = downloadMapper.selectOwnDownload(access.getTenant().getId(), access.getUserId(), taskId);

            // 3. 不可见404，成功且过期410，其他未成功状态409；时间判断来自数据库。
            validateState(task);
            // 3.1 旧任务没有可信厂区快照时拒绝重用文件，必须重新导出。
            if (!downloadMapper.hasTaskSites(task)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,"导出任务缺少厂区授权快照，请重新导出");
            }

            // 3.2 打开文件前复核全部厂区快照，撤权主体不得触发磁盘访问或文件缺失写入。
            if (!downloadMapper.canDownload(task)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"导出厂区权限或文件状态已变化，请重新导出");
            }

            // 4. 验证固定存储键并取得目录句柄，目录在打开叶文件前已固定，关闭前不能被路径替换绕过。
            directory = pinFile(task);

            // 5. 相对已固定目录打开普通叶文件，禁止跟随链接，实际大小必须与任务记录一致。
            SeekableByteChannel channel = directory.openFile();
            long size = channel.size();
            if (task.getFileSize() == null || size <= 0 || size != task.getFileSize()) throw unavailable(task);

            // 6. 持有文件和目录句柄时再次检查实时权限、数据库成功状态、有效期、存储键和令牌。
            requireSameAccess(access);
            if (!downloadMapper.canDownload(task)) {
                requireSameAccess(access);
                validateState(downloadMapper.selectOwnDownload(access.getTenant().getId(), access.getUserId(), taskId));
                throw new ResponseStatusException(HttpStatus.CONFLICT,"导出文件状态已变化，请刷新任务");
            }

            // 7. 把输入流、目录句柄和并发名额的关闭责任一起交付Controller，非阻塞发送结束/超时均释放。
            var opened = new InventoryExportDownloadFile(ownedStream(channel,directory,lease,task), "库存流水-" + taskId + ".xlsx", size);
            handedOff = true;
            return opened;
        } catch (IOException failure) {
            LOGGER.warn("导出文件无法打开，taskId={}", taskId, failure);
            throw unavailable(task);
        } catch (LinkageError | UnsupportedOperationException unavailablePlatform) {
            // 安全打开能力不可用时拒绝服务，不能降级为存在竞争窗口的普通路径打开。
            LOGGER.error("安全下载存储能力不可用",unavailablePlatform);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"当前存储暂不支持安全下载");
        } finally {
            // 8. 任何交付前失败都关闭已固定目录/通道并归还名额；交付后由输入流的幂等关闭统一处理。
            if (!handedOff) {
                try { if (directory != null) directory.close(); }
                catch (IOException closeFailure) { LOGGER.warn("下载句柄关闭失败，taskId={}", taskId, closeFailure); }
                finally { lease.close(); }
            }
        }
    }

    /** 管理员取消当前企业内本人创建的任务；运行中的任务交给后台协作停止。 */
    @Override
    public InventoryExportTaskVO cancel(Long id) {
        // 1. 重新取得同一次可信身份中的企业、用户和实时角色；不是当前企业管理员时返回403。
        TenantAccess access = currentAccessService.requireTenantAccess();
        if (!access.getRoleCodes().contains(com.auth.model.AuthSessionAttributes.TENANT_ADMIN_ROLE)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"需要当前企业的管理员权限");
        }
        Long tenantId = access.getTenant().getId();
        Long userId = access.getUserId();

        // 2. 检查任务ID必须大于0；禁止加入外层事务，保证返回和本地队列处理发生在取消事务提交之后。
        if (id == null || id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"任务ID必须大于0");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"取消导出不能嵌套在其他业务事务中");
        }
        InventoryExportTaskVO result;
        try {
            result = transactionTemplate.execute(status -> {
                // 3. 开启短事务，先锁当前有效成员，再按企业、创建人、任务ID锁定本人任务；成员失效403，不可见统一404。
                if (taskMapper.selectEnabledMembershipForUpdate(tenantId,userId) == null) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN,"当前企业成员关系已失效");
                }
                InventoryExportTaskVO task = taskMapper.selectOwnTaskForUpdate(tenantId,userId,id);
                if (task == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"导出任务不存在");

                // 4. 读取加锁后的最新状态：取消中重复请求直接返回；已成功、失败、取消、拒绝、过期等终态返回409。
                if (task.getStatus() == InventoryExportStatus.CANCEL_REQUESTED) return task;
                if (task.getStatus() != InventoryExportStatus.QUEUED && task.getStatus() != InventoryExportStatus.RUNNING) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"导出任务已结束，不能取消");
                }

                // 5. SQL再次检查管理员角色及账号、企业、成员启用状态，以活动状态为条件竞争更新；QUEUED改CANCELLED并记结束时间和原因，RUNNING只改CANCEL_REQUESTED。
                int changed = taskMapper.requestCancelOwnTask(tenantId,userId,id);

                // 6. 检查影响行数并查询公开VO；更新不成功不得声称已取消，事务失败回滚，不提前操作本地Future。
                //    任务行已锁且状态已确认，更新0行说明写入时权限检查未通过，返回403；数据库异常只返回通用失败提示。
                if (changed != 1) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"取消任务的权限已失效");
                InventoryExportTaskVO latest = taskMapper.selectOwnTask(tenantId,userId,id);
                if (latest == null) throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"取消任务结果读取失败");
                return latest;
            });
        } catch (org.springframework.dao.DataAccessException failure) {
            LOGGER.error("取消导出任务数据库操作失败，taskId={}",id,failure);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"取消任务失败，请稍后查询任务状态");
        }
        if (result == null) throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"取消任务结果缺失");

        // 7. 事务提交后，仅对排队取消结果尽力释放本实例对应Future并移出队列；跨实例依靠数据库状态阻止启动。
        if (result.getStatus() == InventoryExportStatus.CANCELLED) {
            exportServiceImpl.releaseLocalCancelledExecution(id);
        }

        // 8. 返回提交后的公开任务；运行中由worker检查或维护器处理取消，关闭资源并清理未发布文件；SQL与完成发布竞争，先到终态的一方不能被覆盖。
        //    第1～8步均已实现；CANCEL_REQUESTED只表示取消请求被接受，不能声称文件清理已完成。
        return result;
    }

    private InputStream ownedStream(SeekableByteChannel channel,InventoryExportFileAccess.PinnedDirectory directory,InventoryDownloadGate.Lease lease, InventoryExportDownloadRecord task) {
        // 1. 文件仍是流式读取，把目录句柄与配额绑定输入流，Controller不必知道平台实现。
        return new FilterInputStream(Channels.newInputStream(channel)) {
            private final AtomicBoolean closed = new AtomicBoolean();
            /** 1. 每次读取前按固定任务身份重查全快照授权，不从容器回调读取HTTP身份。 */
            private void requireReadable() throws IOException {
                // 2. 已关闭的流立即停止，不再执行数据库查询。
                if (closed.get()) throw new IOException("下载流已关闭");
                // 3. 授权、有效期或数据库不可用均停止交付，失败仍关闭全部句柄并归还并发名额。
                try {
                    if (downloadMapper.canDownload(task)) return;
                } catch (RuntimeException failure) {
                    // 4. 数据库错误只作为内部异常原因，不把SQL或存储信息写入公开响应。
                    IOException stopped = new IOException("下载授权检查失败",failure);
                    try { close(); } catch (IOException closeFailure) { stopped.addSuppressed(closeFailure); }
                    throw stopped;
                }
                // 5. 撤权或文件状态变化后拒绝继续读取，已经发送的数据无法收回。
                IOException stopped = new IOException("下载权限或文件状态已变化，请重新导出");
                try { close(); } catch (IOException closeFailure) { stopped.addSuppressed(closeFailure); }
                throw stopped;
            }
            @Override public int read() throws IOException {
                // 1. 单字节读取与分块读取使用相同的实时授权规则。
                requireReadable();
                // 2. 直接读取底层输入流，避免FilterInputStream转发产生重复检查。
                return in.read();
            }
            @Override public int read(byte[] bytes,int offset,int length) throws IOException {
                // 1. 每个文件块开始前复核授权，容器背压期间不会保持过期授权快照。
                requireReadable();
                // 2. 每次最多交付调用方请求的固定块，撤权窗口为检查到输出间的一块及已缓存字节。
                return in.read(bytes,offset,length);
            }
            @Override public void close() throws IOException {
                // 2. 超时、完成和错误回调只允许关闭一次；即使通道关闭失败，也继续关闭目录并归还名额。
                if (!closed.compareAndSet(false,true)) return;
                IOException failure = null;
                try { super.close(); } catch (IOException close) { failure = close; }
                try { directory.close(); } catch (IOException close) { if (failure == null) failure = close; else failure.addSuppressed(close); }
                finally { lease.close(); }
                if (failure != null) throw failure;
            }
        };
    }

    private void validateState(InventoryExportDownloadRecord task) {
        // 1. 不可见统一404；EXPIRED优先410，其余未成功状态409；成功任务还必须由数据库确认未过期。
        // Mapper已限定企业和本人，查不到时不区分“不存在”和“不属于当前用户”。
        if (task == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"导出任务不存在");
        // 维护器已经标记EXPIRED时，直接按文件过期处理。
        if (task.getStatus() == InventoryExportStatus.EXPIRED) throw new ResponseStatusException(HttpStatus.GONE,"导出文件已过期，请重新导出");
        // 排队、运行、失败、取消等状态都没有可供本接口下载的成功文件。
        if (task.getStatus() != InventoryExportStatus.SUCCEEDED) throw new ResponseStatusException(HttpStatus.CONFLICT,"当前任务没有可下载的文件");
        // expired由SQL按数据库时间计算；只有明确为false才允许继续，不能把null当成未过期。
        if (!Boolean.FALSE.equals(task.getExpired())) throw new ResponseStatusException(HttpStatus.GONE,"导出文件已过期，请重新导出");
    }

    private void requireSameAccess(TenantAccess original) {
        // 1. 打开句柄后重新验证实时权限；请求中的企业或用户快照若不一致，立即拒绝交付。
        // 重新执行账号、企业和成员关系检查，不能一直沿用第一次检查的结果。
        TenantAccess current = currentAccessService.requireTenantAccess();
        // 当前身份还必须与本次下载开始时相同，防止按旧身份继续交付文件。
        if (!Objects.equals(original.getUserId(), current.getUserId())
                || !Objects.equals(original.getTenant().getId(), current.getTenant().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"当前身份已变化，请重新查询任务");
        }
    }

    private InventoryExportFileAccess.PinnedDirectory pinFile(InventoryExportDownloadRecord task) throws IOException {
        // 1. 存储键只能为本任务ID-执行UUID/inventory.xlsx，不接受其他文件或目录层级。
        String token = task.getRunToken();
        if (token == null || !token.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")
                || !Objects.equals(task.getStorageKey(), task.getId() + "-" + token + "/inventory.xlsx")) throw unavailable(task);

        // 2. 配置格式错误500；有效根目录交给平台组件固定，安全能力不支持时拒绝下载。
        final Path configured;
        try { configured = Path.of(exportRoot); }
        catch (RuntimeException invalid) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"导出存储配置不可用"); }
        if (!configured.isAbsolute()) throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"导出存储配置不可用");
        return fileAccess.pinDirectory(configured,task.getId() + "-" + token);
    }

    private ResponseStatusException unavailable(InventoryExportDownloadRecord task) {
        // 1. 仅为当前企业、本人的同一成功未过期文件条件记录FILE_MISSING，不覆盖成功/其他终态。
        try {
            // SQL同时匹配任务归属、令牌、存储键和有效期，避免修改已变化的任务。
            int updated = downloadMapper.markFileMissing(task);

            // 2. 记录失败返回500；未命中条件时重新分类404/410/409；不向调用方暴露路径或SQL。
            // 更新不到时，任务可能已经过期或不可见，重查后复用统一状态判断。
            if (updated != 1) validateState(downloadMapper.selectOwnDownload(task.getTenantId(), task.getCreatedBy(), task.getId()));
        } catch (ResponseStatusException state) {
            // 已经判断出的404或410保持原样抛出，不把它们误当成数据库记录失败。
            throw state;
        } catch (RuntimeException failure) {
            // 数据库更新或重查失败，不能声称FILE_MISSING已经成功保存。
            LOGGER.error("文件不可用原因保存失败，taskId={}", task.getId(), failure);
            return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"文件状态记录失败，请稍后重试");
        }
        // 返回异常对象，由调用位置的throw unavailable(task)真正抛出。
        return new ResponseStatusException(HttpStatus.CONFLICT,"导出文件不存在或不可用，请重新导出");
    }
}
