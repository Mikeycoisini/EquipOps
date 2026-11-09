package com.config.Exception;
import com.common.response.*;
import com.operations.controller.AlertController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.DataAccessException;
/** 只处理本次告警入口，不改变其他模块的错误协议。 */
@RestControllerAdvice(assignableTypes= AlertController.class)
public class AlertExceptionHandler extends BusinessExceptionHandler {
 @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
 org.springframework.validation.BindException.class})
 public ResponseEntity<Result<Void>> binding(Exception exception) {
  return ResponseEntity.badRequest().body(Result.fail(400,"告警查询参数格式有误"));
 }
 @ExceptionHandler(DataAccessException.class)
 public ResponseEntity<Result<Void>> database(DataAccessException exception) {
  return ResponseEntity.status(503).body(Result.fail(503,"告警数据暂不可用，请确认数据库准备完成后重试"));
 }
}
