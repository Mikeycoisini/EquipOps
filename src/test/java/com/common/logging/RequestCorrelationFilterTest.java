package com.common.logging;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;
class RequestCorrelationFilterTest {
 @Test void generatesServerIdAndRestoresContext() throws Exception {
  var request=new MockHttpServletRequest(); var response=new MockHttpServletResponse();
  request.addHeader("X-Request-ID","password=secret"); MDC.put("requestId","outer");
  try {
   new RequestCorrelationFilter().doFilter(request,response,(req,res)->{
    assertNotEquals("password=secret",MDC.get("requestId"));
    assertEquals(response.getHeader("X-Request-ID"),MDC.get("requestId"));
   });
   assertDoesNotThrow(()->java.util.UUID.fromString(response.getHeader("X-Request-ID")));
   assertEquals("outer",MDC.get("requestId"));
  } finally { MDC.clear(); }
 }
 @Test void exceptionStillHasIdAndClearsContext() {
  var response=new MockHttpServletResponse();
  assertThrows(jakarta.servlet.ServletException.class,()->new RequestCorrelationFilter().doFilter(
   new MockHttpServletRequest(),response,(a,b)->{throw new jakarta.servlet.ServletException("secret");}));
  assertNotNull(response.getHeader("X-Request-ID"));assertNull(MDC.get("requestId"));
 }
 @Test void asyncDispatchReusesId() throws Exception {
  var request=new MockHttpServletRequest();request.setAsyncSupported(true);var response=new MockHttpServletResponse();
  var filter=new RequestCorrelationFilter();
  filter.doFilter(request,response,(a,b)->{});String first=response.getHeader("X-Request-ID");
  request.setDispatcherType(jakarta.servlet.DispatcherType.ASYNC);
  filter.doFilter(request,response,(a,b)->assertEquals(first,MDC.get("requestId")));
  assertEquals(first,response.getHeader("X-Request-ID"));assertNull(MDC.get("requestId"));
 }
}
