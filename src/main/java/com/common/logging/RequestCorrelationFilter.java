package com.common.logging;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 请求编号不作为身份凭证；不采集请求参数、正文、Cookie 或原始路径。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(RequestCorrelationFilter.class);
    private static final String ATTRIBUTE = RequestCorrelationFilter.class.getName()+".id";
    @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }
    @Override protected boolean shouldNotFilterErrorDispatch() { return false; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        // 1. 首次请求生成编号；异步/错误派发从服务端请求属性恢复，不使用客户端提供的值。
        String id = (String) request.getAttribute(ATTRIBUTE);
        if (id == null) { id = UUID.randomUUID().toString(); request.setAttribute(ATTRIBUTE,id); }
        String previous = MDC.get("requestId");
        MDC.put("requestId",id); response.setHeader("X-Request-ID",id);
        long start=System.nanoTime(); boolean failed=false;
        try {
            // 2. 继续执行认证及业务链；不读取 Session 或复制授权快照。
            chain.doFilter(request,response);
        } catch (IOException | ServletException | RuntimeException ex) {
            // 3. 只标记异常，不把异常消息、SQL 或堆栈写入请求日志。
            failed=true; throw ex;
        } finally {
            // 4. 每次派发记录状态/耗时；异步开始只表示提交，最终传输由后续派发完成。
            LOG.info("http method={} status={} elapsedMs={} async={}",
                    request.getMethod().matches("[A-Z]{1,16}")?request.getMethod():"OTHER",
                    failed?500:response.getStatus(),(System.nanoTime()-start)/1_000_000,request.isAsyncStarted());
            // 5. 恢复原上下文，避免线程池复用串号。
            if(previous==null) MDC.remove("requestId"); else MDC.put("requestId",previous);
        }
    }
}
