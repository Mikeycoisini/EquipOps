package com.ai;

import com.common.response.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** AI接口统一错误结构；不反射SQL与内部异常。 */
@RestControllerAdvice(basePackages = "com.ai")
public class AiErrorAdvice {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<Void>> status(ResponseStatusException failure) {
        String reason = failure.getReason() == null ? "AI_REQUEST_FAILED" : failure.getReason();
        return ResponseEntity.status(failure.getStatusCode())
                .body(Result.fail(failure.getStatusCode().value(), reason));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> invalid(MethodArgumentNotValidException failure) {
        return ResponseEntity.badRequest().body(Result.fail(400, "AI_INVALID_REQUEST"));
    }
}
