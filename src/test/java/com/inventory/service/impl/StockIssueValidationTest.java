package com.inventory.service.impl;

import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.inventory.StockIssueCreateDto;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 直接调用业务层时，非法精度和长度也不能越过参数校验进入数据库。 */
class StockIssueValidationTest {
    private StockIssueServiceImpl service() {
        var service = new StockIssueServiceImpl();
        CurrentAccessService access = (CurrentAccessService) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{CurrentAccessService.class},
                (p,m,a) -> switch (m.getName()) {
                    case "requireActiveTenantId" -> 7L;
                    case "requireEnabledUserId" -> 1L;
                    default -> throw new AssertionError(m.getName());
                });
        ReflectionTestUtils.setField(service,"currentAccessService",access);
        // 不注入 Mapper，确保非法参数在访问任何数据库边界之前就被拒绝。
        return service;
    }
    private StockIssueCreateDto request() {
        return new StockIssueCreateDto(30L,10L,20L,new BigDecimal("2.500"),null);
    }
    @Test void 超出小数精度或整数上限必须返回400() {
        for (String quantity : new String[]{"0.0001","100000000000","2.5000","1E+11"}) {
            var request=request(); request.setQuantity(new BigDecimal(quantity));
            var error=assertThrows(ResponseStatusException.class, () -> service().issue("issue-1",request));
            assertEquals(400,error.getStatusCode().value());
        }
    }
    @Test void 超长备注必须在查询和写库前拒绝() {
        var request=request(); request.setNote("备".repeat(501));
        var error=assertThrows(ResponseStatusException.class, () -> service().issue("issue-1",request));
        assertEquals(400,error.getStatusCode().value());
    }
}
