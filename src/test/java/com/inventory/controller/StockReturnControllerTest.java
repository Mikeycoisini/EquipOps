package com.inventory.controller;

import com.config.Exception.StockReturnExceptionHandler;
import com.equipops.domain.vo.InventoryTransactionVO;
import com.inventory.service.StockReturnService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.dao.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 独立 MVC 退料契约测试；不加载登录安全链，不连接数据库。 */
class StockReturnControllerTest {
    private static final String BODY="{\"issueTransactionId\":100,\"quantity\":2.5}";
    private MockMvc mvc(StockReturnService service) {
        return MockMvcBuilders.standaloneSetup(new StockReturnController(service))
                .setControllerAdvice(new StockReturnExceptionHandler()).build();
    }
    @Test void 退料请求正确绑定并以统一成功格式返回流水() throws Exception {
        var mvc=mvc((key,request) -> {
            assertEquals("return-1",key); assertEquals(100L,request.getIssueTransactionId());
            assertEquals(0,new BigDecimal("2.5").compareTo(request.getQuantity()));
            var result=new InventoryTransactionVO(); result.setId(100L); result.setRequestId(key);
            return result;
        });
        mvc.perform(post("/api/inventory/returns").header("Idempotency-Key","return-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.requestId").value("return-1"));
    }
    @Test void 缺少请求号返回可读400且不调用业务() throws Exception {
        var mvc=mvc((k,r) -> { throw new AssertionError("非法请求不能进入业务"); });
        mvc.perform(post("/api/inventory/returns").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("业务请求号不能为空"));
    }
    @Test void 错误JSON和数量类型返回可读400() throws Exception {
        var mvc=mvc((k,r) -> { throw new AssertionError("非法请求不能进入业务"); });
        for (String body : new String[]{"{bad json",BODY.replace("2.5","\"invalid\"")}) {
            mvc.perform(post("/api/inventory/returns").header("Idempotency-Key","return-1")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("退料参数格式有误"));
        }
    }
    @Test void 非法数量缺原领料或超长备注不进入业务层() throws Exception {
        var mvc=mvc((k,r) -> { throw new AssertionError("非法请求不能进入业务"); });
        for (String body : new String[]{BODY.replace("2.5","0"),BODY.replace("2.5","0.0001"),
                BODY.replace("2.5","100000000000"),BODY.replace("\"issueTransactionId\":100,",""),
                BODY.replace("}",",\"note\":\""+"x".repeat(501)+"\"}")}) {
            mvc.perform(post("/api/inventory/returns").header("Idempotency-Key","return-1")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }
    }
    @Test void 业务权限和状态错误保留原状态与原因() throws Exception {
        for (HttpStatus status : new HttpStatus[]{HttpStatus.FORBIDDEN,HttpStatus.NOT_FOUND,HttpStatus.CONFLICT}) {
            var mvc=mvc((k,r) -> { throw new ResponseStatusException(status,"不允许本次退料"); });
            mvc.perform(post("/api/inventory/returns").header("Idempotency-Key","return-1")
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().is(status.value())).andExpect(jsonPath("$.code").value(status.value()))
                    .andExpect(jsonPath("$.message").value("不允许本次退料"));
        }
    }
    @Test void 锁冲突返回409并允许用原请求号重试() throws Exception {
        var mvc=mvc((k,r) -> { throw new CannotAcquireLockException("private database SQL"); });
        mvc.perform(post("/api/inventory/returns").header("Idempotency-Key","return-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("库存正在变更，请使用原请求号重试"));
    }
    @Test void 数据库错误不暴露SQL且不能包装成成功() throws Exception {
        var mvc=mvc((k,r) -> { throw new DataAccessResourceFailureException("private connection details"); });
        mvc.perform(post("/api/inventory/returns").header("Idempotency-Key","return-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("退料失败，请稍后重试"));
    }
    @Test void 数据库关联约束冲突沿用通用409错误() throws Exception {
        var mvc=mvc((k,r) -> { throw new DataIntegrityViolationException("private table details"); });
        mvc.perform(post("/api/inventory/returns").header("Idempotency-Key","return-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("数据编号或关联关系冲突，请检查后重试"));
    }
}
