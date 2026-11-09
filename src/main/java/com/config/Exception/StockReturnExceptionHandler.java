package com.config.Exception;

import com.common.response.BusinessExceptionHandler;
import com.common.response.Result;
import com.inventory.controller.StockReturnController;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 仅处理退料入口的错误，保持统一响应，不向前端暴露 SQL 或连接信息。 */
@RestControllerAdvice(assignableTypes = StockReturnController.class)
public class StockReturnExceptionHandler extends BusinessExceptionHandler {
    /** 请求头缺失时提示补充幂等请求号。 */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Result<Void>> missingHeader(MissingRequestHeaderException exception) {
        return ResponseEntity.badRequest().body(Result.fail(400, "业务请求号不能为空"));
    }

    /** 请求体缺失、JSON 格式错误或字段类型错误统一返回400。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> unreadable(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(Result.fail(400, "退料参数格式有误"));
    }

    /** 锁等待或死锁后由事务回滚，客户端可以使用原请求号重试。 */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<Result<Void>> lockConflict(PessimisticLockingFailureException exception) {
        return ResponseEntity.status(409).body(Result.fail(409, "库存正在变更，请使用原请求号重试"));
    }

    /** 数据库异常不伪装成功；关联约束冲突沿用父类更具体的409处理。 */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Result<Void>> databaseFailure(DataAccessException exception) {
        return ResponseEntity.internalServerError().body(Result.fail(500, "退料失败，请稍后重试"));
    }
}
