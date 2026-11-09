package com.inventory.export;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 队列拒绝仍保留已落库任务ID，便于请求方后续查询。 */
public class InventoryExportRejectedException extends ResponseStatusException {
    private final Long taskId;
    public InventoryExportRejectedException(Long taskId) {
        super(HttpStatus.TOO_MANY_REQUESTS,"执行器容量不足，请稍后重试");
        this.taskId=taskId;
    }
    public Long getTaskId() { return taskId; }
}
