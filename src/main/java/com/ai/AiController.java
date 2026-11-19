package com.ai;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.common.response.Result;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 浏览器AI接口：身份、企业、CSRF由现有Session链保护。 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {
    private final AiService ai;

    @PostMapping("/documents")
    public Result<AiViews.Document> upload(@RequestPart("file") MultipartFile file,
                                           @RequestParam String documentCode,
                                           @RequestParam String title) {
        return Result.success(ai.upload(file, documentCode, title));
    }

    @GetMapping("/documents")
    public Result<IPage<AiViews.Document>> documents(@RequestParam(defaultValue = "1") int current,
                                                     @RequestParam(defaultValue = "20") int size,
                                                     @RequestParam(required = false) String keyword) {
        return Result.success(ai.list(current, size, keyword));
    }

    @GetMapping("/documents/{id}")
    public Result<AiViews.Document> document(@PathVariable long id) {
        return Result.success(ai.detail(id));
    }

    @PostMapping("/documents/{id}/versions")
    public Result<AiViews.Document> version(@PathVariable long id, @RequestPart("file") MultipartFile file) {
        return Result.success(ai.addVersion(id, file));
    }

    @DeleteMapping("/documents/{id}")
    public Result<Void> delete(@PathVariable long id) {
        ai.deleteDocument(id);
        return Result.success();
    }

    @PostMapping("/questions")
    public ResponseEntity<Result<Map<String, String>>> question(@Valid @RequestBody AiRequests.Question request) {
        AiViews.Task task = ai.ask(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Result.success(Map.of("taskId", Long.toString(task.id()), "state", task.state())));
    }

    @GetMapping("/tasks/{id}")
    public Result<AiViews.Task> task(@PathVariable long id) {
        return Result.success(ai.task(id));
    }

    @PostMapping("/tasks/{id}/cancel")
    public Result<Void> cancel(@PathVariable long id) {
        ai.cancel(id);
        return Result.success();
    }
}
