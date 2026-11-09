package com.inventory.controller;

import com.common.response.BusinessExceptionHandler;
import com.common.response.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 仅作用于仓库余额入口，复用既有业务错误格式，不改变其他 Controller 的处理范围。 */
@RestControllerAdvice(assignableTypes = WarehouseStockController.class)
public class WarehouseStockExceptionHandler extends BusinessExceptionHandler {
    /** ModelAttribute 字段转换失败与普通参数校验共用此异常，不能直接返回框架原始消息。 */
    @Override
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> validation(MethodArgumentNotValidException exception) {
        if (exception.getBindingResult().getFieldErrors().stream().anyMatch(error -> error.isBindingFailure())) {
            return ResponseEntity.badRequest().body(Result.fail(400, "查询参数类型有误"));
        }
        // 非空、正数、长度等字段约束继续使用 DTO 已写好的中文提示。
        return super.validation(exception);
    }

    /** 拒绝无法绑定的 ID、分页数和布尔值，不向调用者输出框架异常详情。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> typeMismatch(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest().body(Result.fail(400, "查询参数类型有误"));
    }
}
