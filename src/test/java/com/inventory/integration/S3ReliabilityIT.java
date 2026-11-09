package com.inventory.integration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 四项真实数据库/HTTP可靠性测试。只连接本机equipops_test及8081，不启动应用。
 * 每个方法新建独立种子，可重复运行；保留业务证据，不删除或重置已有数据。
 * 唯一临时结构是回滚测试的专用触发器，finally中删除。
 */
@EnabledIfEnvironmentVariable(named="EQUIPOPS_RELIABILITY_IT", matches="true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
public class S3ReliabilityIT {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String BASE="http://localhost:8081";
    private static final Path EVIDENCE=Path.of("E:/ChatGPT/EquipPos/artifacts/s3-reliability-remaining");
    private static final ZoneId SHANGHAI=ZoneId.of("Asia/Shanghai");

    @BeforeAll
    static void preflight() throws Exception {
        // 运行前还需由启动配置/日志确认8081使用同一个测试库，端口本身不能证明库身份。
        try(Connection c=db()) {
            assertEquals(0, number(c,"SELECT COUNT(*) FROM information_schema.triggers "
                    +"WHERE trigger_schema=DATABASE() AND LEFT(trigger_name,6)='s3_rb_'"),
                    "有残留故障触发器，请先核对来源，不能继续测试");
            assertEquals(10,number(c,"SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL"));
            assertEquals(2,number(c,"SELECT COUNT(*) FROM tenant_memberships m JOIN users u ON u.id=m.user_id "
                    +"WHERE m.tenant_id=91001 AND m.enabled=1 AND u.enabled=1 AND u.id IN (91003,91005)"),
                    "派单的两个目标必须都是当前企业启用成员");
        }
    }

    @AfterAll
    static void verifyNoFaultTriggerRemains() throws Exception {
        try(Connection c=db()) {
            assertEquals(0,number(c,"SELECT COUNT(*) FROM information_schema.triggers "
                    +"WHERE trigger_schema=DATABASE() AND LEFT(trigger_name,6)='s3_rb_'"),
                    "故障触发器必须全部移除");
        }
    }

    @Test @Order(1)
    void concurrentReturnsCannotExceedOriginalIssue() throws Exception {
        try(Connection c=db()) {
            // 1. 独立库存1，先通过真实接口领完，再竞争退还这同一笔领料。
            Fixture f=fixture(c,false);
            Session a=login(false), b=login(false);
            long original=success(call(a,"POST","/api/inventory/issues",issueBody(f),f.marker()+"-issue")).path("id").asLong();
            assertTrue(original>0);
            assertDecimal("0",stock(c,f));
            String keyA=f.marker()+"-return-A", keyB=f.marker()+"-return-B";
            Map<String,Object> body=Map.of("issueTransactionId",original,"quantity",1,"note",f.marker());
            // 2. 不同请求号：验证可退上限，而非同请求号回放。
            List<HttpResponse<String>> responses=parallel(
                    ()->call(a,"POST","/api/inventory/returns",body,keyA),
                    ()->call(b,"POST","/api/inventory/returns",body,keyB));
            assertEquals(List.of(200,409),responses.stream().map(HttpResponse::statusCode).sorted().toList());
            int winner=responses.get(0).statusCode()==200?0:1;
            long returned=success(responses.get(winner)).path("id").asLong();
            String failedKey=winner==0?keyB:keyA;
            assertEquals(0,number(c,"SELECT COUNT(*) FROM inventory_transactions WHERE tenant_id=91001 AND request_id=?",failedKey));
            // 3. 最终余额、已领/已退、原流水关联数量和返回ID全部一致。
            assertDecimal("1",stock(c,f));
            assertDecimal("1",partQuantity(c,f,"issued_quantity"));
            assertDecimal("1",partQuantity(c,f,"returned_quantity"));
            assertEquals(1,number(c,"SELECT COUNT(*) FROM inventory_transactions WHERE tenant_id=91001 AND issue_transaction_id=?",original));
            assertDecimal("1",decimal(c,"SELECT SUM(quantity) FROM inventory_transactions WHERE tenant_id=91001 AND issue_transaction_id=?",original));
            assertEquals(1,number(c,"SELECT COUNT(*) FROM inventory_transactions WHERE tenant_id=91001 AND id=? AND issue_transaction_id=? "
                    +"AND transaction_type='INBOUND' AND quantity=1 AND spare_part_id=? AND warehouse_id=91001 AND work_order_id=?",returned,original,f.part(),f.order()));
            evidence("returns",Map.of("fixture",f,"issueId",original,"returnId",returned,"statuses",statuses(responses),"stock",stock(c,f)));
        }
    }

    @Test @Order(2)
    void concurrentDispatchMustNotOverwriteWinner() throws Exception {
        try(Connection c=db()) {
            Fixture f=fixture(c,true);
            Session a=login(true),b=login(true);
            String path="/api/work-orders/"+f.order()+"/dispatch";
            // 两个管理员Session把同一未派单工单派给不同的合法成员。
            List<HttpResponse<String>> responses=parallel(
                    ()->call(a,"PUT",path,Map.of("assigneeUserId",91003),null),
                    ()->call(b,"PUT",path,Map.of("assigneeUserId",91005),null));
            assertEquals(1,responses.stream().filter(r->r.statusCode()==200).count());
            int winner=responses.get(0).statusCode()==200?0:1;
            HttpResponse<String> loser=responses.get(1-winner);
            // 原代码先查到已派单为400，竞争条件更新未命中为409，均属于现有契约。
            assertTrue(loser.statusCode()==400 || loser.statusCode()==409,"竞争方应明确拒绝");
            long chosen=winner==0?91003:91005;
            assertEquals(chosen,success(responses.get(winner)).path("assigneeUserId").asLong());
            assertEquals(chosen,number(c,"SELECT assignee_user_id FROM work_orders WHERE tenant_id=91001 AND id=?",f.order()));
            assertEquals(1,number(c,"SELECT COUNT(*) FROM work_order_logs WHERE work_order_id=? AND event_type='ASSIGNED'",f.order()));
            assertEquals(1,number(c,"SELECT COUNT(*) FROM work_order_logs WHERE work_order_id=? AND event_type='ASSIGNED' "
                    +"AND actor_user_id=91001 AND detail=?",f.order(),"派单给用户 "+chosen));
            // 被拒绝的请求再试一次，仍不能覆盖已经选定的负责人或新增日志。
            HttpResponse<String> retry=call(winner==0?b:a,"PUT",path,Map.of("assigneeUserId",winner==0?91005:91003),null);
            assertTrue(retry.statusCode()==400 || retry.statusCode()==409);
            assertEquals(chosen,number(c,"SELECT assignee_user_id FROM work_orders WHERE tenant_id=91001 AND id=?",f.order()));
            assertEquals(1,number(c,"SELECT COUNT(*) FROM work_order_logs WHERE work_order_id=? AND event_type='ASSIGNED'",f.order()));
            evidence("dispatch",Map.of("fixture",f,"statuses",statuses(responses),"assigneeUserId",chosen,"retryStatus",retry.statusCode()));
        }
    }

    @Test @Order(3)
    void differentKeysCannotExecuteSameMaintenancePeriodTwice() throws Exception {
        try(Connection c=db()) {
            Session admin=login(true),a=login(false),b=login(false);
            String marker=marker();
            LocalDate today=LocalDate.now(SHANGHAI);
            JsonNode plan=success(call(admin,"POST","/api/maintenance-plans",Map.of(
                    "name",marker,"equipmentId",92001,"planType","INSPECTION","content",marker,
                    "cycleDays",7,"nextDueDate",today.toString(),"assigneeUserId",91003),null));
            long planId=plan.path("id").asLong();
            assertTrue(planId>0);
            try {
                String path="/api/maintenance-plans/"+planId+"/executions";
                List<HttpResponse<String>> responses=parallel(
                        ()->call(a,"POST",path,Map.of("result",marker+" A"),marker+"-A"),
                        ()->call(b,"POST",path,Map.of("result",marker+" B"),marker+"-B"));
                assertEquals(List.of(200,409),responses.stream().map(HttpResponse::statusCode).sorted().toList());
                int winner=responses.get(0).statusCode()==200?0:1;
                long execution=success(responses.get(winner)).path("id").asLong();
                assertTrue(execution>0);
                assertEquals(1,number(c,"SELECT COUNT(*) FROM maintenance_executions WHERE tenant_id=91001 AND plan_id=?",planId));
                assertEquals(1,number(c,"SELECT COUNT(*) FROM maintenance_executions WHERE tenant_id=91001 AND plan_id=? "
                        +"AND id=? AND scheduled_date=? AND next_due_date=? AND executed_by=91003 AND request_id=? AND result=?",
                        planId,execution,today,today.plusDays(7),marker+(winner==0?"-A":"-B"),marker+(winner==0?" A":" B")));
                assertEquals(1,number(c,"SELECT COUNT(*) FROM maintenance_plans WHERE tenant_id=91001 AND id=? AND next_due_date=?",planId,today.plusDays(7)));
                assertEquals(0,number(c,"SELECT COUNT(*) FROM maintenance_executions WHERE tenant_id=91001 AND request_id=?",marker+(winner==0?"-B":"-A")));
                assertEquals(today,LocalDate.now(SHANGHAI),"运行跨过上海午夜，本次需单独核对，不直接判通过");
                evidence("maintenance",Map.of("marker",marker,"planId",planId,"executionId",execution,"scheduledDate",today.toString(),
                        "nextDueDate",today.plusDays(7).toString(),"statuses",statuses(responses)));
            } finally {
                // 只停用本轮计划，保留执行证据，避免下次到期混入页面。
                success(call(admin,"PATCH","/api/maintenance-plans/"+planId+"/enabled",Map.of("enabled",false),null));
            }
        }
    }

    @Test @Order(4)
    void failureAfterStockDeductionMustRollbackAllWrites() throws Exception {
        try(Connection c=db()) {
            Fixture f=fixture(c,false);
            Session actor=login(false);
            String key=f.marker()+"-rollback";
            String trigger="s3_rb_"+UUID.randomUUID().toString().replace("-", "");
            String proof="S3_RB_OK_"+f.part();
            long beforeLogs=number(c,"SELECT COUNT(*) FROM inventory_transactions WHERE tenant_id=91001 AND spare_part_id=?",f.part());
            assertEquals(0,number(c,"SELECT COUNT(*) FROM work_order_parts WHERE work_order_id=? AND spare_part_id=?",f.order(),f.part()));
            // 故障只命中本轮新备件/工单；先验证同事务中库存已扣、流水已插，再抛出唯一标记。
            // 不插非事务表、不提交中间状态；用会话变量证明注入位置。
            // 会话变量不属于业务事务，通过Performance Schema定向读取本轮标记。
            String ddl="CREATE TRIGGER "+trigger+" BEFORE INSERT ON work_order_parts FOR EACH ROW BEGIN "
                    +"DECLARE v_qty DECIMAL(14,3); DECLARE v_logs INT; "
                    +"IF NEW.spare_part_id="+f.part()+" AND NEW.work_order_id="+f.order()+" THEN "
                    +"SELECT quantity INTO v_qty FROM warehouse_stock WHERE tenant_id=91001 AND warehouse_id=91001 AND spare_part_id="+f.part()+"; "
                    +"SELECT COUNT(*) INTO v_logs FROM inventory_transactions WHERE tenant_id=91001 AND request_id='"+key+"'; "
                    +"IF v_qty=0 AND v_logs=1 THEN SET @s3_rb_proof='"+proof+"'; SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='"+proof+"'; "
                    +"ELSE SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='S3_RB_WRONG_STAGE'; END IF; END IF; END";
            boolean installed=false;
            try {
                try(Statement s=c.createStatement()) { s.execute(ddl); installed=true; }
                HttpResponse<String> failure=call(actor,"POST","/api/inventory/issues",issueBody(f),key);
                assertEquals(500,failure.statusCode(),"注入后的写用料应失败");
                assertDecimal("1",stock(c,f));
                assertEquals(beforeLogs,number(c,"SELECT COUNT(*) FROM inventory_transactions WHERE tenant_id=91001 AND spare_part_id=?",f.part()));
                assertEquals(0,number(c,"SELECT COUNT(*) FROM inventory_transactions WHERE tenant_id=91001 AND request_id=?",key));
                assertEquals(0,number(c,"SELECT COUNT(*) FROM work_order_parts WHERE work_order_id=? AND spare_part_id=?",f.order(),f.part()));
                assertEquals(1,number(c,"SELECT COUNT(*) FROM performance_schema.user_variables_by_thread "
                        +"WHERE VARIABLE_NAME='s3_rb_proof' AND CONVERT(VARIABLE_VALUE USING utf8mb4)=?",proof),
                        "必须找到已扣库存且已插流水后的注入证据，不能把任意500判通过");
            } finally {
                if(installed) try(Statement s=c.createStatement()) {s.execute("DROP TRIGGER "+trigger);}
            }
            assertEquals(0,number(c,"SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name=?",trigger));
            // 撤掉故障后用原号重试，应正常成功，证明失败事务未占住幂等请求号。
            long transaction=success(call(actor,"POST","/api/inventory/issues",issueBody(f),key)).path("id").asLong();
            assertTrue(transaction>0);
            assertDecimal("0",stock(c,f));
            assertDecimal("1",partQuantity(c,f,"issued_quantity"));
            assertEquals(beforeLogs+1,number(c,"SELECT COUNT(*) FROM inventory_transactions WHERE tenant_id=91001 AND spare_part_id=?",f.part()));
            assertEquals(1,number(c,"SELECT COUNT(*) FROM inventory_transactions WHERE tenant_id=91001 AND request_id=? AND id=?",key,transaction));
            evidence("rollback",Map.of("fixture",f,"proof",proof,"failureStatus",500,"stockAfterFailure",1,
                    "triggerRemoved",true,"retryTransactionId",transaction,"stockAfterRetry",0));
        }
    }

    /** 每次新建独立种子，在单事务内准备；不依赖手工填写备件和工单ID。 */
    private Fixture fixture(Connection c,boolean pending) throws Exception {
        String marker=marker();
        c.setAutoCommit(false);
        try {
            long site=number(c,"SELECT e.site_id FROM equipment e JOIN warehouses w ON w.tenant_id=e.tenant_id "
                    +"JOIN sites s ON s.id=w.site_id AND s.tenant_id=w.tenant_id "
                    +"WHERE e.tenant_id=91001 AND e.id=92001 AND w.id=91001 AND w.enabled=1 AND s.enabled=1");
            long part=insert(c,"INSERT INTO spare_parts (tenant_id,part_code,name,unit,enabled) VALUES (91001,?,'可靠性测试专用','件',1)",marker);
            insert(c,"INSERT INTO warehouse_stock (tenant_id,warehouse_id,spare_part_id,quantity,min_stock_quantity) VALUES (91001,91001,?,1,0)",part);
            insert(c,"INSERT INTO inventory_transactions (tenant_id,warehouse_id,request_id,transaction_no,spare_part_id,transaction_type,quantity,reference_type,created_by,note) "
                    +"VALUES (91001,91001,?,?,?,'INBOUND',1,'MANUAL',91003,?)",marker+"-seed","S3-IN-"+UUID.randomUUID().toString().replace("-",""),part,marker);
            long order=insert(c,"INSERT INTO work_orders (tenant_id,site_id,order_no,title,equipment_id,description,priority,status,assignee_user_id,created_by) "
                    +"VALUES (91001,?,?,?,92001,'可靠性测试种子，不用于生命周期验收','NORMAL',?,?,91003)",site,
                    "S3-WO-"+UUID.randomUUID().toString().replace("-",""),marker,pending?"PENDING":"IN_PROGRESS",pending?null:91003L);
            c.commit();
            Fixture f=new Fixture(part,order,marker);
            evidence("fixture-"+order,Map.of("fixture",f));
            return f;
        } catch(Exception | AssertionError error) {c.rollback();throw error;}
        finally {c.setAutoCommit(true);}
    }

    private static Connection db() throws Exception {
        Connection c=DriverManager.getConnection("jdbc:mysql://localhost:3306/equipops_test",env("IT_DB_USER"),env("IT_DB_PASSWORD"));
        if(!"equipops_test".equals(c.getCatalog())) {c.close();throw new IllegalStateException("错误数据库");}
        c.setAutoCommit(true);
        return c;
    }

    private Session login(boolean admin) throws Exception {
        HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ORIGINAL_SERVER))
                .connectTimeout(Duration.ofSeconds(5)).build();
        Session session=new Session(client,null,null);
        session=refresh(session);
        JsonNode result=success(call(session,"POST","/api/auth/login",Map.of(
                "account",env(admin?"IT_ADMIN_ACCOUNT":"IT_ACCOUNT"),"password",env(admin?"IT_ADMIN_PASSWORD":"IT_PASSWORD")),null));
        assertEquals(admin?91001:91003,result.path("userId").asLong(),"必须使用本测试库的指定种子账号");
        session=refresh(session);
        success(call(session,"POST","/api/auth/active-tenant",Map.of("tenantId",91001),null));
        return refresh(session);
    }

    private Session refresh(Session session) throws Exception {
        JsonNode token=success(session.client().send(HttpRequest.newBuilder(URI.create(BASE+"/api/auth/csrf"))
                .timeout(Duration.ofSeconds(30)).GET().build(),HttpResponse.BodyHandlers.ofString()));
        assertFalse(token.path("token").asText().isBlank());
        assertFalse(token.path("headerName").asText().isBlank());
        return new Session(session.client(),token.path("headerName").asText(),token.path("token").asText());
    }

    private HttpResponse<String> call(Session session,String method,String path,Object body,String key) throws Exception {
        HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(BASE+path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type","application/json").header(session.header(),session.token())
                .method(method,HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if(key!=null) request.header("Idempotency-Key",key);
        return session.client().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }

    private List<HttpResponse<String>> parallel(Callable<HttpResponse<String>> first,Callable<HttpResponse<String>> second) throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch ready=new CountDownLatch(2), start=new CountDownLatch(1);
        try {
            Future<HttpResponse<String>> a=pool.submit(()->{ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new TimeoutException();return first.call();});
            Future<HttpResponse<String>> b=pool.submit(()->{ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new TimeoutException();return second.call();});
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();
            return List.of(a.get(40,TimeUnit.SECONDS),b.get(40,TimeUnit.SECONDS));
        } finally {start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(35,TimeUnit.SECONDS));}
    }

    private JsonNode success(HttpResponse<String> response) throws Exception {
        assertEquals(200,response.statusCode(),"接口未成功");
        JsonNode payload=JSON.readTree(response.body());assertEquals(200,payload.path("code").asInt());
        return payload.path("data");
    }
    private Map<String,Object> issueBody(Fixture f) {return Map.of("workOrderId",f.order(),"warehouseId",91001,"sparePartId",f.part(),"quantity",1,"note",f.marker());}
    private BigDecimal stock(Connection c,Fixture f) throws Exception {return decimal(c,"SELECT quantity FROM warehouse_stock WHERE tenant_id=91001 AND warehouse_id=91001 AND spare_part_id=?",f.part());}
    private BigDecimal partQuantity(Connection c,Fixture f,String column) throws Exception {
        if(!Set.of("issued_quantity","returned_quantity").contains(column))throw new IllegalArgumentException();
        return decimal(c,"SELECT "+column+" FROM work_order_parts WHERE work_order_id=? AND spare_part_id=?",f.order(),f.part());
    }
    private static long number(Connection c,String sql,Object... args) throws Exception {return decimal(c,sql,args).longValueExact();}
    private static BigDecimal decimal(Connection c,String sql,Object... args) throws Exception {
        try(PreparedStatement p=c.prepareStatement(sql)) {
            p.setQueryTimeout(10);for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);
            try(ResultSet r=p.executeQuery()){assertTrue(r.next(),"缺少核对记录");BigDecimal value=r.getBigDecimal(1);assertNotNull(value);assertFalse(r.next());return value;}
        }
    }
    private long insert(Connection c,String sql,Object... args) throws Exception {
        try(PreparedStatement p=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)) {
            p.setQueryTimeout(10);for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);assertEquals(1,p.executeUpdate());
            try(ResultSet r=p.getGeneratedKeys()){assertTrue(r.next());return r.getLong(1);}
        }
    }
    private void assertDecimal(String expected,BigDecimal actual){assertEquals(0,new BigDecimal(expected).compareTo(actual));}
    private List<Integer> statuses(List<HttpResponse<String>> responses){return responses.stream().map(HttpResponse::statusCode).toList();}
    private void evidence(String name,Map<String,Object> data) throws Exception {
        Files.createDirectories(EVIDENCE);
        String filename=name+"-"+UUID.randomUUID()+".json";
        Files.writeString(EVIDENCE.resolve(filename),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(data));
        System.out.println("Evidence: "+filename);
    }
    private String marker(){return "S3-CONC-"+UUID.randomUUID().toString().replace("-","");}
    private static String env(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalStateException("缺少测试配置："+key);return value;}
    private record Fixture(long part,long order,String marker){}
    private record Session(HttpClient client,String header,String token){}
}
