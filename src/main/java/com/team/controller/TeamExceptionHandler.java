package com.team.controller;
import com.common.response.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
/** 团队协议保留业务状态，内部失败不能泄露SQL或请求正文。 */
@RestControllerAdvice(assignableTypes=TeamController.class)
public class TeamExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<Void>> status(ResponseStatusException error){int status=error.getStatusCode().value();return ResponseEntity.status(status).body(Result.fail(status,status>=500?"操作暂时无法完成，请稍后重试":error.getReason()));}
    @ExceptionHandler({org.springframework.validation.BindException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<Result<Void>> binding(Exception error){return ResponseEntity.badRequest().body(Result.fail(400,"团队参数格式有误"));}
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Result<Void>> internal(RuntimeException error){return ResponseEntity.internalServerError().body(Result.fail(500,"操作暂时无法完成，请稍后重试"));}
}
