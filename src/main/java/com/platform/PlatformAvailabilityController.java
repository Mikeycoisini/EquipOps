package com.platform;

import com.common.response.Result;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/platform/availability-alerts")
@RequiredArgsConstructor
public class PlatformAvailabilityController {
    private final PlatformAvailabilityService service;

    public enum Action { READ, ACKNOWLEDGE, RESOLVE }

    @GetMapping
    public Result<List<PlatformAvailabilityService.Alert>> list(
            @RequestParam(defaultValue = "false") boolean inbox,
            @RequestParam(defaultValue = "9223372036854775807") long before) {
        return Result.success(service.list(inbox, before));
    }

    @PostMapping("/{id}/actions/{action}")
    public Result<Void> act(@PathVariable long id, @PathVariable Action action) {
        service.act(id, action);
        return Result.success();
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<Void>> error(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode())
                .body(Result.fail(error.getStatusCode().value(), error.getReason()));
    }
}
