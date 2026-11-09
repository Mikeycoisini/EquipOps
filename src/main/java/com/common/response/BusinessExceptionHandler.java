package com.common.response;

import com.equipment.controller.EquipmentController;
import com.statistics.controller.EquipmentStatisticsController;
import com.statistics.controller.WorkOrderStatisticsController;
import com.equipment.controller.EquipmentSparePartController;
import com.equipment.controller.MaintenanceHistoryController;
import com.workorder.controller.WorkerderController;
import com.workorder.controller.EquipmentWorkOrderController;
import com.inventory.controller.SparePartController;
import com.inventory.controller.SparePartCompatibilityController;
import com.inventory.controller.InventoryTransactionController;
import com.workspace.controller.WorkspaceController;
import com.maintenance.controller.MaintenancePlanController;
import com.maintenance.controller.MaintenanceExecutionController;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** 仅处理本批业务入口的明确错误，保留现有认证和成员模块行为。 */
@RestControllerAdvice(assignableTypes = {EquipmentController.class, MaintenanceHistoryController.class,
        EquipmentWorkOrderController.class, WorkerderController.class, SparePartController.class, InventoryTransactionController.class,
        WorkspaceController.class, MaintenancePlanController.class, MaintenanceExecutionController.class, EquipmentSparePartController.class, EquipmentStatisticsController.class, WorkOrderStatisticsController.class, SparePartCompatibilityController.class})
public class BusinessExceptionHandler {
    /** 保留业务指定的 HTTP 状态及可读原因，不向前端返回 SQL 堆栈。 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<Void>> status(ResponseStatusException exception) {
        int status = exception.getStatusCode().value();
        return ResponseEntity.status(status).body(Result.fail(status, exception.getReason()));
    }

    /** 请求体或查询 DTO 的参数校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> validation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getAllErrors().stream()
                .map(error -> error.getDefaultMessage()).findFirst().orElse("请求参数有误");
        return ResponseEntity.badRequest().body(Result.fail(400, message));
    }

    /** 兼容现有用户手写的参数异常，不改写其方法结构。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> argument(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Result.fail(400, exception.getMessage()));
    }

    /** 现有状态限制以冲突返回，方便前端展示具体原因。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Result<Void>> state(IllegalStateException exception) {
        return ResponseEntity.status(409).body(Result.fail(409, exception.getMessage()));
    }

    /** 唯一索引或关联约束失败；不把具体表结构及 SQL 暴露给客户端。 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Result<Void>> integrity(DataIntegrityViolationException exception) {
        return ResponseEntity.status(409).body(Result.fail(409, "数据编号或关联关系冲突，请检查后重试"));
    }
}
