package com.inventory.controller;

import com.common.response.BusinessExceptionHandler;
import com.common.response.Result;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;

/** 入库入口专用错误处理，复用既有业务状态格式，不把 SQL 或堆栈返回前端。 */
@RestControllerAdvice(assignableTypes = StockInController.class)
public class StockInExceptionHandler extends BusinessExceptionHandler {
    /** 缺少业务请求号时返回明确的中文提示。 */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Result<Void>> missingHeader(MissingRequestHeaderException exception) {
        return ResponseEntity.badRequest().body(Result.fail(400, "业务请求号不能为空"));
    }

    /** JSON 格式错误、数量类型错误或请求体缺失统一拒绝。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> unreadable(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(Result.fail(400, "入库参数格式有误"));
    }

    /** 锁等待或死锁由事务回滚后允许使用原请求号重试，不自动重复写入。 */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<Result<Void>> lockConflict(PessimisticLockingFailureException exception) {
        return ResponseEntity.status(409).body(Result.fail(409, "库存正在变更，请使用原请求号重试"));
    }

    /** 其他数据库异常只报告失败，不暴露具体表结构或连接信息。 */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Result<Void>> databaseFailure(DataAccessException exception) {
        return ResponseEntity.internalServerError().body(Result.fail(500, "入库失败，请稍后重试"));
    }
}
