package com.platform;

import com.common.response.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/platform")
@RequiredArgsConstructor
public class PlatformTenantController {
    private final PlatformTenantService service;

    public record Open(@NotBlank @Size(max = 64) String tenantCode,
                       @NotBlank @Size(max = 160) String name,
                       @NotBlank @Size(max = 100) String administratorAccount,
                       @NotBlank @Size(max = 500) String reason) {}

    public record Change(@NotNull @PositiveOrZero Long expectedVersion,
                         @NotBlank @Size(max = 500) String reason) {}

    @GetMapping("/me")
    public Result<PlatformTenantService.Operator> operator() {
        return Result.success(service.operator());
    }

    @GetMapping("/tenants")
    public Result<List<PlatformTenantService.Tenant>> list(
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int pageSize) {
        return Result.success(service.list(page, pageSize));
    }

    @GetMapping("/tenants/{id}")
    public Result<PlatformTenantService.Tenant> detail(@PathVariable long id) {
        return Result.success(service.detail(id));
    }

    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    public Result<PlatformTenantService.Tenant> open(@Valid @RequestBody Open request) {
        return Result.success(service.open(request));
    }

    @PostMapping("/tenants/{id}/suspend")
    public Result<PlatformTenantService.Tenant> suspend(@PathVariable long id, @Valid @RequestBody Change request) {
        return Result.success(service.change(id, request, PlatformTenantService.State.SUSPENDED));
    }

    @PostMapping("/tenants/{id}/resume")
    public Result<PlatformTenantService.Tenant> resume(@PathVariable long id, @Valid @RequestBody Change request) {
        return Result.success(service.change(id, request, PlatformTenantService.State.ACTIVE));
    }

    @GetMapping("/tenants/{id}/actions")
    public Result<List<PlatformTenantService.Action>> actions(@PathVariable long id,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int pageSize) {
        return Result.success(service.actions(id, page, pageSize));
    }
}
