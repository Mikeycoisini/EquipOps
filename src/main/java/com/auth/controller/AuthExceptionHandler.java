package com.auth.controller;

import com.common.response.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** 仅统一认证 Controller 的错误，不改写其他模块的异常行为。 */
@RestControllerAdvice(assignableTypes = AuthController.class)
public class AuthExceptionHandler {
    /** 对账号不存在、密码错误、账号停用统一响应，不泄露具体账号状态。 */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Result<Void>> authentication(AuthenticationException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Result.fail(401, "账号、密码有误或账号不可用"));
    }

    /** 保留认证业务主动指定的 400、401、403、409 等 HTTP 语义。 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<Void>> status(ResponseStatusException exception) {
        int status = exception.getStatusCode().value();
        return ResponseEntity.status(status).body(Result.fail(status, exception.getReason()));
    }

    /** @Valid 失败时取一个明确的字段错误，避免向前端输出整个异常堆栈。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> validation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage()).findFirst().orElse("请求参数有误");
        return ResponseEntity.badRequest().body(Result.fail(400, message));
    }
}
