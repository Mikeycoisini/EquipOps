package com.inventory.transfer;

import com.common.response.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/transfers")
@RequiredArgsConstructor
public class TransferController {
    private final TransferService service;
    private static final String REQUEST_ID = "[A-Za-z0-9._:-]{1,64}";

    public record Create(@NotNull @Pattern(regexp = REQUEST_ID) String requestId,
                         @NotNull @Positive Long sourceWarehouseId,
                         @NotNull @Positive Long targetWarehouseId,
                         @NotNull @Positive Long sparePartId,
                         @NotNull @Positive @Digits(integer = 11, fraction = 3) BigDecimal quantity,
                         @Size(max = 500) String note) {}

    public record Command(@NotNull @Pattern(regexp = REQUEST_ID) String requestId,
                          @NotNull @Min(0) Integer expectedVersion,
                          @Size(max = 500) String reason) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<TransferService.Transfer> create(@Valid @RequestBody Create request) {
        return Result.success(service.create(request));
    }

    @GetMapping
    public Result<TransferService.Page> list(@RequestParam(required = false) TransferService.Status status,
                                             @RequestParam(required = false) Long warehouseId,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                             LocalDateTime createdFrom,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                             LocalDateTime createdBefore,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(service.list(status, warehouseId, createdFrom, createdBefore, page, pageSize));
    }

    @GetMapping("/{id}")
    public Result<TransferService.Detail> detail(@PathVariable long id) {
        return Result.success(service.detail(id));
    }

    @PostMapping("/{id}/{action:dispatch|receive|cancel|exceptions|resume}")
    public Result<TransferService.Transfer> act(@PathVariable long id, @PathVariable String action,
                                                @Valid @RequestBody Command request) {
        var operation = switch (action) {
            case "dispatch" -> TransferService.Action.DISPATCH;
            case "receive" -> TransferService.Action.RECEIVE;
            case "cancel" -> TransferService.Action.CANCEL;
            case "exceptions" -> TransferService.Action.EXCEPTION;
            case "resume" -> TransferService.Action.RESUME;
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        };
        return Result.success(service.act(id, operation, request));
    }
}
