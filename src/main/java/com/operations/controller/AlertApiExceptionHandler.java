package com.operations.controller;
import com.common.response.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.DataAccessException;
@RestControllerAdvice(assignableTypes={AlertController.class,AlertManagementController.class})
public class AlertApiExceptionHandler extends BusinessExceptionHandler {
    // 参数绑定失败返回安全提示。
    @ExceptionHandler({org.springframework.validation.BindException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<Result<Void>> binding(Exception error) { return ResponseEntity.badRequest().body(Result.fail(400,"告警参数格式有误")); }
    // 数据访问异常保留HTTP失败状态。
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Result<Void>> database(DataAccessException error) { return ResponseEntity.status(503).body(Result.fail(503,"告警数据暂不可用")); }
}
