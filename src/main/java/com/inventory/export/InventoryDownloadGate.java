package com.inventory.export;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 单实例下载配额；按用户ID跨企业计数，立即拒绝超额请求，不建立等待队列。 */
@Component
public final class InventoryDownloadGate {
    private final int globalLimit;
    private final int userLimit;
    private int active;
    private final Map<Long,Integer> users=new HashMap<>();

    public InventoryDownloadGate(@Value("${inventory.export.download.max-concurrent:8}") int globalLimit,
            @Value("${inventory.export.download.max-per-user:2}") int userLimit) {
        // 1. 配置必须为正数且用户上限不超过实例总上限，错误配置在启动时拒绝。
        if(globalLimit<1 || userLimit<1 || userLimit>globalLimit)throw new IllegalArgumentException("下载并发配额配置无效");
        this.globalLimit=globalLimit;this.userLimit=userLimit;
    }

    public synchronized Lease acquire(Long userId) {
        // 1. 只接收可信用户ID，在同一个锁内检查用户与全局计数，防止并发检查超额。
        if(userId==null || userId<=0)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"下载身份无效");
        int count=users.getOrDefault(userId,0);
        if(active>=globalLimit || count>=userLimit)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"同时下载数量已达上限，请稍后重试");
        // 2. 同时占用两项名额；关闭Lease才归还，不因任务查询或打开失败遗留名额。
        active++;users.put(userId,count+1);return new Lease(userId);
    }

    private synchronized void release(Long userId) {
        // 1. 仅由已取得名额的Lease调用，用户计数归零即移除，避免永久保存用户键。
        int left=users.get(userId)-1;
        if(left==0)users.remove(userId);else users.put(userId,left);
        active--;
    }

    public final class Lease implements AutoCloseable {
        private final Long userId;
        private final AtomicBoolean closed=new AtomicBoolean();
        private Lease(Long userId){this.userId=userId;}
        @Override public void close(){
            // 1. 正常完成、异常和超时可能同时触发关闭，只有首次归还名额。
            if(closed.compareAndSet(false,true))release(userId);
        }
    }
}
