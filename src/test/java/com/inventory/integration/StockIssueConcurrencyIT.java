package com.inventory.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实HTTP领料并发测试；不启动Spring、不自动迁移数据库、不使用Mapper替身。
 * 必须先确认8081连接equipops_test，再显式开启；仅消费已准备的专用测试库存。
 */
@EnabledIfEnvironmentVariable(named = "EQUIPOPS_CONCURRENCY_IT", matches = "true")
public class StockIssueConcurrencyIT {
    private static final String BASE = "http://localhost:8081";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @EnabledIf("isLastItemCase")
    void onlyOneRequestCanIssueTheLastItem() throws Exception {
        // 1. 确认测试环境并准备独立测试数据。
        // 本类只检查数据；准备工作由独立步骤完成，不把已有库存强制改成1。
        Fixture fixture = new Fixture(id("IT_TENANT_ID"), id("IT_WAREHOUSE_ID"),
                id("IT_SPARE_PART_ID"), id("IT_WORK_ORDER_ID"));
        try (Connection db = openTestDatabase()) {
            verifyFixture(db, fixture);
            BigDecimal beforeIssued = issued(db, fixture);
            long beforeLogs = ledgerCount(db, fixture);

            // 2. 登录负责人账号，建立两个独立会话。
            // 独立Cookie容器避免共享同一个Session；两边仍是同一负责人。
            Session first = login(fixture.tenantId());
            Session second = login(fixture.tenantId());
            assertEquals(first.userId(), second.userId());
            assertEquals(first.userId(), number(db,
                    "SELECT assignee_user_id FROM work_orders WHERE tenant_id=? AND id=?",
                    fixture.tenantId(), fixture.orderId()), "登录账号必须是该工单负责人");

            String run = "S3-CONC-" + UUID.randomUUID().toString().replace("-", "");
            String keyA = run + "-A";
            String keyB = run + "-B";
            String body = JSON.writeValueAsString(Map.of("workOrderId", fixture.orderId(),
                    "warehouseId", fixture.warehouseId(), "sparePartId", fixture.partId(),
                    "quantity", BigDecimal.ONE, "note", run));

            // 3. 使用两个线程，同时提交不同请求号的领料请求。
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2); // 两名选手都就位。
            CountDownLatch start = new CountDownLatch(1); // 一次发令，两边一起开始。
            List<HttpResponse<String>> responses;
            try {
                Future<HttpResponse<String>> a = pool.submit(() -> issue(first, keyA, body, ready, start));
                Future<HttpResponse<String>> b = pool.submit(() -> issue(second, keyB, body, ready, start));
                assertTrue(ready.await(10, TimeUnit.SECONDS), "两个任务没有及时就绪");
                start.countDown();
                responses = List.of(a.get(40, TimeUnit.SECONDS), b.get(40, TimeUnit.SECONDS));
            } finally {
                start.countDown();
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(35, TimeUnit.SECONDS), "测试线程未结束，请核对服务端和测试数据");
            }

            // 4. 检查响应：一次成功，一次库存不足。
            List<Integer> statuses = responses.stream().map(HttpResponse::statusCode).sorted().toList();
            assertEquals(List.of(200, 409), statuses, "应一次成功、一次冲突；401/403等不能算库存竞争通过");
            for (HttpResponse<String> response : responses) {
                JsonNode result = JSON.readTree(response.body());
                if (response.statusCode() == 200) {
                    assertEquals(200, result.path("code").asInt());
                    assertTrue(result.path("data").path("id").asLong() > 0, "成功响应必须有流水ID");
                }
            }

            // 5. 查询数据库，核对库存、用料和流水。
            assertEquals(0, stock(db, fixture).compareTo(BigDecimal.ZERO), "最终库存必须为0");
            assertEquals(0, issued(db, fixture).subtract(beforeIssued).compareTo(BigDecimal.ONE),
                    "工单已领数量只能增加1");
            assertEquals(beforeLogs + 1, ledgerCount(db, fixture), "专用备件只能新增一条流水");
            assertEquals(1, number(db, """
                    SELECT COUNT(*) FROM inventory_transactions
                    WHERE tenant_id=? AND warehouse_id=? AND spare_part_id=? AND work_order_id=?
                      AND request_id IN (?,?) AND transaction_type='OUTBOUND'
                      AND reference_type='WORK_ORDER' AND quantity=1 AND created_by=? AND note=?
                    """, fixture.tenantId(), fixture.warehouseId(), fixture.partId(), fixture.orderId(),
                    keyA, keyB, first.userId(), run), "必须落一条内容正确的出库流水");
            String failedKey = responses.get(0).statusCode() == 409 ? keyA : keyB;
            assertEquals(0, number(db, "SELECT COUNT(*) FROM inventory_transactions WHERE tenant_id=? AND request_id=?",
                    fixture.tenantId(), failedKey), "失败请求先插入的流水应已回滚");
            System.out.println("领料并发核对通过，证据标记：" + run);
            // 6. 清理本轮创建的测试数据。
            // 本测试不创建基础数据，也不删除流水或重置余额。保留证据；下轮重新准备专用数据。
        }
    }

    /** 同一负责人用两个独立Session提交同号同内容，应该回放同一条流水。 */
    @Test
    @EnabledIf("isSameKeyCase")
    void sameRequestKeyMustIssueOnlyOnce() throws Exception {
        // 1. 专用数据必须有1件库存；不与上一轮已用完的数据混用。
        Fixture fixture = new Fixture(id("IT_TENANT_ID"), id("IT_WAREHOUSE_ID"),
                id("IT_SPARE_PART_ID"), id("IT_WORK_ORDER_ID"));
        try (Connection db = openTestDatabase()) {
            verifyFixture(db, fixture);
            BigDecimal beforeIssued = issued(db, fixture);
            long beforeLogs = ledgerCount(db, fixture);

            // 2. 两个独立登录会话，但操作者必须是同一名工单负责人。
            Session first = login(fixture.tenantId());
            Session second = login(fixture.tenantId());
            assertEquals(first.userId(), second.userId());
            assertEquals(first.userId(), number(db,
                    "SELECT assignee_user_id FROM work_orders WHERE tenant_id=? AND id=?",
                    fixture.tenantId(), fixture.orderId()), "必须使用工单负责人账号");
            String key = "S3-SAME-" + UUID.randomUUID().toString().replace("-", "");
            String body = JSON.writeValueAsString(Map.of("workOrderId", fixture.orderId(),
                    "warehouseId", fixture.warehouseId(), "sparePartId", fixture.partId(),
                    "quantity", BigDecimal.ONE, "note", key));

            // 3. 与上一场景的关键区别：两个线程使用完全相同的key和body。
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            HttpResponse<String> responseA;
            HttpResponse<String> responseB;
            try {
                Future<HttpResponse<String>> a = pool.submit(() -> issue(first, key, body, ready, start));
                Future<HttpResponse<String>> b = pool.submit(() -> issue(second, key, body, ready, start));
                assertTrue(ready.await(10, TimeUnit.SECONDS), "两个任务没有及时就绪");
                start.countDown();
                responseA = a.get(40, TimeUnit.SECONDS);
                responseB = b.get(40, TimeUnit.SECONDS);
            } finally {
                start.countDown();
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(35, TimeUnit.SECONDS), "测试线程未结束");
            }

            // 4. 同号重试两边均应成功，且返回相同、真实存在的流水ID。
            long firstId = successfulTransactionId(responseA);
            long secondId = successfulTransactionId(responseB);
            assertEquals(firstId, secondId, "两个同号请求应返回同一条流水");
            // 库存已耗尽后再普通重试一次：仍需返回原流水，不能重新扣库存。
            HttpResponse<String> replay = first.client().send(
                    post("/api/inventory/issues", body, first.csrf())
                            .header("Idempotency-Key", key).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(firstId, successfulTransactionId(replay), "普通重试也应回放原流水");

            // 5. 数据库结果必须只有一次业务效果，不能仅凭两个200判断成功。
            assertEquals(0, stock(db, fixture).compareTo(BigDecimal.ZERO));
            assertEquals(0, issued(db, fixture).subtract(beforeIssued).compareTo(BigDecimal.ONE));
            assertEquals(beforeLogs + 1, ledgerCount(db, fixture));
            assertEquals(1, number(db,
                    "SELECT COUNT(*) FROM inventory_transactions WHERE tenant_id=? AND request_id=?",
                    fixture.tenantId(), key), "同号只能存在一条流水");
            assertEquals(1, number(db, """
                    SELECT COUNT(*) FROM inventory_transactions
                    WHERE id=? AND tenant_id=? AND request_id=? AND warehouse_id=?
                      AND spare_part_id=? AND work_order_id=? AND created_by=?
                      AND transaction_type='OUTBOUND' AND reference_type='WORK_ORDER'
                      AND quantity=1 AND note=?
                    """, firstId, fixture.tenantId(), key, fixture.warehouseId(), fixture.partId(),
                    fixture.orderId(), first.userId(), key), "返回ID必须对应本次真实领料流水");
            System.out.println("同号并发及普通重试核对通过，流水ID=" + firstId + "，请求号=" + key);
            // 6. 保留本轮数据作为证据；不会删除流水或强行恢复库存。
        }
    }

    /** 默认保留旧测试行为；单独配置same-key，避免两个测试消耗同一件库存。 */
    static boolean isLastItemCase() {
        String scenario = System.getenv("IT_ISSUE_CASE");
        return scenario == null || scenario.isBlank() || "last-item".equals(scenario);
    }

    static boolean isSameKeyCase() {
        return "same-key".equals(System.getenv("IT_ISSUE_CASE"));
    }

    private long successfulTransactionId(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), "同号请求应成功回放，不能返回库存不足或其他失败");
        JsonNode result = JSON.readTree(response.body());
        assertEquals(200, result.path("code").asInt());
        long transactionId = result.path("data").path("id").asLong();
        assertTrue(transactionId > 0, "成功响应必须包含流水ID");
        return transactionId;
    }

    /** 同号改数量必须拒绝，且原成功结果仍可回放。 */
    @Test
    @EnabledIf("isChangedContentCase")
    void sameKeyWithChangedQuantityMustBeRejected() throws Exception {
        Fixture fixture = new Fixture(id("IT_TENANT_ID"), id("IT_WAREHOUSE_ID"),
                id("IT_SPARE_PART_ID"), id("IT_WORK_ORDER_ID"));
        try (Connection db = openTestDatabase()) {
            // 1. 初始库存1；先领0.25后剩0.75，足以满足修改后的0.5。
            // 这样409不能由库存不足导致，能区分幂等内容检查与余额检查。
            verifyFixture(db, fixture);
            BigDecimal beforeIssued = issued(db, fixture);
            long beforeLogs = ledgerCount(db, fixture);
            Session session = login(fixture.tenantId());
            assertEquals(session.userId(), number(db,
                    "SELECT assignee_user_id FROM work_orders WHERE tenant_id=? AND id=?",
                    fixture.tenantId(), fixture.orderId()));
            String key = "S3-CHANGE-" + UUID.randomUUID().toString().replace("-", "");
            String original = JSON.writeValueAsString(Map.of("workOrderId", fixture.orderId(),
                    "warehouseId", fixture.warehouseId(), "sparePartId", fixture.partId(),
                    "quantity", new BigDecimal("0.250"), "note", key));
            String changed = JSON.writeValueAsString(Map.of("workOrderId", fixture.orderId(),
                    "warehouseId", fixture.warehouseId(), "sparePartId", fixture.partId(),
                    "quantity", new BigDecimal("0.500"), "note", key));

            // 2. 原请求成功，先核对一次写入后的数据库状态。
            long transactionId = successfulTransactionId(session.client().send(
                    post("/api/inventory/issues", original, session.csrf())
                            .header("Idempotency-Key", key).build(), HttpResponse.BodyHandlers.ofString()));
            assertEquals(0, stock(db, fixture).compareTo(new BigDecimal("0.750")));
            BigDecimal issuedAfterFirst = issued(db, fixture);
            assertEquals(0, issuedAfterFirst.subtract(beforeIssued).compareTo(new BigDecimal("0.250")));
            assertEquals(beforeLogs + 1, ledgerCount(db, fixture));

            // 3. 仅改变数量，保留原请求号、操作人和其他所有参数。
            HttpResponse<String> conflict = session.client().send(
                    post("/api/inventory/issues", changed, session.csrf())
                            .header("Idempotency-Key", key).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(409, conflict.statusCode(), "同号改数量必须冲突，不能当成功重试或新领料");
            assertEquals(0, stock(db, fixture).compareTo(new BigDecimal("0.750")));
            assertEquals(0, issued(db, fixture).compareTo(issuedAfterFirst));
            assertEquals(beforeLogs + 1, ledgerCount(db, fixture));

            // 4. 冲突不能破坏原成功结果；原内容再重试仍返回同一条流水。
            long replayId = successfulTransactionId(session.client().send(
                    post("/api/inventory/issues", original, session.csrf())
                            .header("Idempotency-Key", key).build(), HttpResponse.BodyHandlers.ofString()));
            assertEquals(transactionId, replayId);
            assertEquals(0, stock(db, fixture).compareTo(new BigDecimal("0.750")));
            assertEquals(0, issued(db, fixture).compareTo(issuedAfterFirst));
            assertEquals(beforeLogs + 1, ledgerCount(db, fixture));
            assertEquals(1, number(db,
                    "SELECT COUNT(*) FROM inventory_transactions WHERE tenant_id=? AND request_id=?",
                    fixture.tenantId(), key));
            assertEquals(1, number(db, """
                    SELECT COUNT(*) FROM inventory_transactions
                    WHERE id=? AND tenant_id=? AND request_id=? AND warehouse_id=? AND spare_part_id=?
                      AND work_order_id=? AND created_by=? AND quantity=0.250 AND note=?
                      AND transaction_type='OUTBOUND' AND reference_type='WORK_ORDER'
                    """, transactionId, fixture.tenantId(), key, fixture.warehouseId(), fixture.partId(),
                    fixture.orderId(), session.userId(), key), "原流水内容不能被冲突请求修改");
            System.out.println("同号改数量拒绝及原结果回放核对通过，流水ID=" + transactionId + "，请求号=" + key);
        }
    }

    static boolean isChangedContentCase() {
        return "changed-content".equals(System.getenv("IT_ISSUE_CASE"));
    }

    /** 先就绪、等待发令，然后同步发送HTTP请求；每个请求都有超时。 */
    private HttpResponse<String> issue(Session session, String key, String body,
                                       CountDownLatch ready, CountDownLatch start) throws Exception {
        HttpRequest request = post("/api/inventory/issues", body, session.csrf())
                .header("Idempotency-Key", key).build();
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("等待发令超时");
        return session.client().send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** 真实登录、登录后刷新CSRF、选择企业；不打印口令、Cookie或Token。 */
    private Session login(long tenantId) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER))
                .connectTimeout(Duration.ofSeconds(5)).build();
        Csrf token = csrf(client);
        JsonNode login = sendOk(client, post("/api/auth/login", JSON.writeValueAsString(
                Map.of("account", env("IT_ACCOUNT"), "password", env("IT_PASSWORD"))), token).build());
        long userId = login.path("data").path("userId").asLong();
        assertTrue(userId > 0, "登录响应缺少用户ID");
        token = csrf(client); // 登录会更新会话，不能继续使用登录前的CSRF。
        sendOk(client, post("/api/auth/active-tenant", JSON.writeValueAsString(
                Map.of("tenantId", tenantId)), token).build());
        return new Session(client, csrf(client), userId);
    }

    private Csrf csrf(HttpClient client) throws Exception {
        JsonNode data = sendOk(client, HttpRequest.newBuilder(URI.create(BASE + "/api/auth/csrf"))
                .timeout(Duration.ofSeconds(30)).GET().build()).path("data");
        String header = data.path("headerName").asText();
        String value = data.path("token").asText();
        assertFalse(header.isBlank(), "缺少CSRF头名称");
        assertFalse(value.isBlank(), "缺少CSRF值");
        return new Csrf(header, value);
    }

    private HttpRequest.Builder post(String path, String body, Csrf csrf) {
        return HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").header(csrf.header(), csrf.token())
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private JsonNode sendOk(HttpClient client, HttpRequest request) throws Exception {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "登录/选择企业/CSRF失败：" + request.uri().getPath());
        JsonNode result = JSON.readTree(response.body());
        assertEquals(200, result.path("code").asInt(), "业务响应未成功");
        return result;
    }

    private Connection openTestDatabase() throws Exception {
        // 不接收任意URL，固定本机测试库；不加载应用配置，因此不会执行Flyway。
        Connection db = DriverManager.getConnection(
                "jdbc:mysql://localhost:3306/equipops_test", env("IT_DB_USER"), env("IT_DB_PASSWORD"));
        try {
            assertEquals("equipops_test", db.getCatalog());
            db.setReadOnly(true); // JDBC只查数据，业务写入全部通过HTTP接口。
            db.setAutoCommit(true); // 每次核对读新提交结果，避免沿用旧事务快照。
            return db;
        } catch (Throwable failure) {
            db.close();
            throw failure;
        }
    }

    private void verifyFixture(Connection db, Fixture f) throws Exception {
        assertEquals(1, number(db, "SELECT COUNT(*) FROM spare_parts WHERE tenant_id=? AND id=? "
                + "AND enabled=1 AND LEFT(part_code,8)='S3-CONC-'", f.tenantId(), f.partId()),
                "仅允许S3-CONC-开头的专用测试备件");
        assertEquals(1, number(db, "SELECT COUNT(*) FROM work_orders WHERE tenant_id=? AND id=? "
                + "AND status='IN_PROGRESS' AND LEFT(title,8)='S3-CONC-'", f.tenantId(), f.orderId()),
                "需要S3-CONC-开头的维修中专用工单");
        assertEquals(1, number(db, "SELECT COUNT(*) FROM warehouses w JOIN sites s "
                + "ON s.tenant_id=w.tenant_id AND s.id=w.site_id "
                + "WHERE w.tenant_id=? AND w.id=? AND w.enabled=1 AND s.enabled=1",
                f.tenantId(), f.warehouseId()), "仓库与所属厂区必须启用");
        assertEquals(0, stock(db, f).compareTo(BigDecimal.ONE), "初始库存必须恰好为1，不自动覆盖旧余额");
    }

    private BigDecimal stock(Connection db, Fixture f) throws Exception {
        return decimal(db, "SELECT quantity FROM warehouse_stock WHERE tenant_id=? AND warehouse_id=? AND spare_part_id=?",
                f.tenantId(), f.warehouseId(), f.partId());
    }

    private BigDecimal issued(Connection db, Fixture f) throws Exception {
        return decimal(db, "SELECT COALESCE(SUM(wp.issued_quantity),0) FROM work_order_parts wp "
                + "JOIN work_orders wo ON wo.id=wp.work_order_id "
                + "WHERE wo.tenant_id=? AND wp.work_order_id=? AND wp.spare_part_id=?",
                f.tenantId(), f.orderId(), f.partId());
    }

    private long ledgerCount(Connection db, Fixture f) throws Exception {
        return number(db, "SELECT COUNT(*) FROM inventory_transactions WHERE tenant_id=? AND spare_part_id=?",
                f.tenantId(), f.partId());
    }

    private long number(Connection db, String sql, Object... args) throws Exception {
        return decimal(db, sql, args).longValueExact();
    }

    /** 所有条件使用参数绑定，数据库查询最多等待10秒。 */
    private BigDecimal decimal(Connection db, String sql, Object... args) throws Exception {
        try (PreparedStatement statement = db.prepareStatement(sql)) {
            statement.setQueryTimeout(10);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "测试所需数据不存在");
                BigDecimal value = result.getBigDecimal(1);
                assertNotNull(value, "数据库值不能为空");
                assertFalse(result.next(), "查询不应返回多条记录");
                return value;
            }
        }
    }

    private static String env(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少测试环境变量：" + key);
        return value;
    }

    private static long id(String key) {
        long value = Long.parseLong(env(key));
        if (value <= 0) throw new IllegalArgumentException(key + "必须是正数");
        return value;
    }

    private record Fixture(long tenantId, long warehouseId, long partId, long orderId) { }
    private record Csrf(String header, String token) { }
    private record Session(HttpClient client, Csrf csrf, long userId) { }
}
