package com.inventory.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实Session成员撤权回归。仅在已确认8081指向equipops_test时显式运行。 */
@EnabledIfEnvironmentVariable(named="EQUIPOPS_PERMISSION_IT", matches="true")
public class TenantPermissionIT {
    private static final String BASE="http://localhost:8081";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Path EVIDENCE=Path.of("E:/ChatGPT/EquipPos/artifacts/s3-permission-revocation");

    @Test
    void disabledMembershipMustBlockExistingSession() throws Exception {
        try(Connection db=DriverManager.getConnection("jdbc:mysql://localhost:3306/equipops_test",
                env("IT_DB_USER"),env("IT_DB_PASSWORD"))) {
            assertEquals("equipops_test",db.getCatalog());
            db.setReadOnly(true);
            db.setAutoCommit(true);
            Session admin=login(env("IT_ADMIN_ACCOUNT"),env("IT_ADMIN_PASSWORD"));
            activate(admin);
            String marker="S3-AUTH-"+UUID.randomUUID().toString().replace("-", "");
            String password=UUID.randomUUID().toString(); // 仅内存中使用，不保存或打印。
            // 1. 通过现有API创建独立账号和无角色的普通成员，避免停用种子账号。
            long userId=ok(call(admin,"POST","/api/users",Map.of("account",marker,
                    "password",password,"displayName","权限回归专用成员"))).path("id").asLong();
            assertTrue(userId>0);
            long membershipId=ok(call(admin,"POST","/api/tenant-members",
                    Map.of("userId",userId,"roleIds",List.of()))).path("id").asLong();
            assertTrue(membershipId>0);
            assertEquals(1,count(db,"SELECT COUNT(*) FROM tenant_memberships WHERE tenant_id=91001 AND id=? AND user_id=? AND enabled=1",membershipId,userId));
            evidence(marker+"-fixture",Map.of("marker",marker,"userId",userId,"membershipId",membershipId));

            // 2. 普通成员登录选企业，确认原先有正常访问权限。
            Session member=login(marker,password);
            activate(member);
            List<String> originalCookies=cookies(member);
            assertFalse(originalCookies.isEmpty(),"必须已经建立真实登录Session");
            assertReadable(member);
            String statusPath="/api/tenant-members/"+membershipId+"/status";

            // 3. 管理员停用成员；成员不退出、不重新登录、不重新选择企业。
            // 即使停用提交后出现网络错误，也会进入finally尝试恢复。
            try {
                JsonNode disabled=ok(call(admin,"PATCH",statusPath,Map.of("enabled",false)));
                assertFalse(disabled.path("enabled").asBoolean(true));
                assertEquals(1,count(db,"SELECT COUNT(*) FROM tenant_memberships WHERE tenant_id=91001 AND id=? AND enabled=0",membershipId));
                HttpResponse<String> equipment=call(member,"GET","/api/equipment",null);
                HttpResponse<String> statistics=call(member,"GET","/api/statistics/work-orders/overdue-summary",null);
                assertEquals(403,equipment.statusCode(),"停用后的原会话不能读取企业设备");
                assertEquals(403,statistics.statusCode(),"停用后的原会话不能读取企业统计");
                assertEquals(403,JSON.readTree(equipment.body()).path("code").asInt());
                assertEquals(403,JSON.readTree(statistics.body()).path("code").asInt());
                assertTrue(originalCookies.equals(cookies(member)),"不能通过换会话或重新登录来制造拒绝结果");
            } finally {
                // 4. 恢复的是本轮新成员原来的启用状态，不改其他成员。
                JsonNode restored=ok(call(admin,"PATCH",statusPath,Map.of("enabled",true)));
                assertTrue(restored.path("enabled").asBoolean());
                assertEquals(1,count(db,"SELECT COUNT(*) FROM tenant_memberships WHERE tenant_id=91001 AND id=? AND user_id=? AND enabled=1",membershipId,userId),
                        "测试结束必须确认本轮成员已恢复");
            }
            // 5. 同一个Session恢复访问，不需要重新登录。
            assertReadable(member);
            assertTrue(originalCookies.equals(cookies(member)),"恢复访问时仍应使用原会话");
            assertEquals(1,count(db,"SELECT COUNT(*) FROM users WHERE id=? AND enabled=1",userId),"全局账号没有被停用");
            assertEquals(0,count(db,"SELECT COUNT(*) FROM tenant_member_roles WHERE tenant_id=91001 AND membership_id=?",membershipId),
                    "测试成员始终无管理员角色");
            evidence(marker+"-passed",Map.of("marker",marker,"userId",userId,"membershipId",membershipId,
                    "before",List.of(200,200),"disabled",List.of(403,403),"restored",List.of(200,200),
                    "sameSession",true,"membershipRestored",true));
            System.out.println("成员停用后的原会话拒绝及恢复验证通过，userId="+userId+", membershipId="+membershipId);
        }
    }

    private void assertReadable(Session member) throws Exception {
        ok(call(member,"GET","/api/equipment",null));
        ok(call(member,"GET","/api/statistics/work-orders/overdue-summary",null));
    }

    /** P2：动态授予/撤销只影响角色，普通成员和原会话仍有效。 */
    @Test
    void revokedAdminRoleMustBlockExistingSession() throws Exception {
        try(Connection db=readDb()) {
            Session admin=admin(false);
            Member member=createMember(admin);
            List<String> original=cookies(member.session());
            long adminRole=count(db,"SELECT id FROM roles WHERE role_code='TENANT_ADMIN'");
            String roles="/api/tenant-members/"+member.membershipId()+"/roles";
            try {
                ok(call(admin,"PUT",roles,Map.of("roleIds",List.of(adminRole))));
                assertTrue(hasAdmin(ok(call(member.session(),"GET","/api/auth/me",null))));
                ok(call(member.session(),"GET","/api/tenant-members",null));
                ok(call(admin,"PUT",roles,Map.of("roleIds",List.of())));
                assertEquals(403,call(member.session(),"GET","/api/tenant-members",null).statusCode());
                assertEquals(403,call(member.session(),"PATCH","/api/tenant-members/"+member.membershipId()+"/status",Map.of("enabled",false)).statusCode());
                assertReadable(member.session());
                assertFalse(hasAdmin(ok(call(member.session(),"GET","/api/auth/me",null))));
                assertTrue(original.equals(cookies(member.session())),"撤权不依赖重新登录");
                assertEquals(1,count(db,"SELECT COUNT(*) FROM tenant_memberships WHERE id=? AND enabled=1",member.membershipId()));
                assertEquals(0,count(db,"SELECT COUNT(*) FROM tenant_member_roles WHERE tenant_id=91001 AND membership_id=?",member.membershipId()));
                matrixEvidence("P2",Map.of("userId",member.userId(),"membershipId",member.membershipId(),
                        "beforeAdminRead",200,"afterAdminRead",403,"afterAdminWrite",403,"ordinaryRead",200,"sameSession",true));
            } finally {
                ok(call(admin,"PUT",roles,Map.of("roleIds",List.of())));
                assertEquals(0,count(db,"SELECT COUNT(*) FROM tenant_member_roles WHERE tenant_id=91001 AND membership_id=?",member.membershipId()));
            }
        }
    }

    /** P3：两个租户的详情、分页和统计，明确有正例而非全都拒绝。 */
    @Test
    void crossTenantReadsMustStayIsolated() throws Exception {
        try(Connection db=readDb()) {
            Session a=admin(false),b=admin(true);
            String marker=matrixMarker();
            long plan=createPlan(a,92001,91003,marker);
            try {
                ok(call(a,"GET","/api/maintenance-plans/"+plan,null));
                ok(call(b,"GET","/api/equipment/"+otherEquipment(db),null));
                assertEquals(404,call(b,"GET","/api/equipment/92001",null).statusCode());
                assertEquals(404,call(b,"GET","/api/maintenance-plans/"+plan,null).statusCode());
                JsonNode hidden=ok(call(b,"GET","/api/maintenance-plans?keyword="+marker,null));
                assertEquals(0,hidden.path("total").asLong());
                JsonNode page=ok(call(b,"GET","/api/equipment?size=100",null));
                assertTrue(page.path("records").size()>0,"企业B必须确实有可读数据");
                assertEquals(count(db,"SELECT COUNT(*) FROM equipment WHERE tenant_id=91002"),page.path("total").asLong());
                for(JsonNode row:page.path("records")) {
                    assertEquals(91002,row.path("tenantId").asLong());
                    assertEquals(1,count(db,"SELECT COUNT(*) FROM equipment WHERE tenant_id=91002 AND id=?",row.path("id").asLong()));
                }
                verifyStatistics(db,a,91001);
                verifyStatistics(db,b,91002);
                matrixEvidence("P3",Map.of("aPlanId",plan,"crossDetails",404,"bEquipmentTotal",page.path("total").asLong(),"crossPlanTotal",0,"statisticsTenants",List.of(91001,91002)));
            } finally {disablePlan(a,plan);}
        }
    }

    /** P4：跨租户状态、编辑、执行及成员写入都应拒绝且不改变目标数据。 */
    @Test
    void crossTenantWritesMustNotChangeTargetData() throws Exception {
        try(Connection db=readDb()) {
            Session a=admin(false),b=admin(true);
            String marker=matrixMarker();
            long plan=createPlan(a,92001,91003,marker);
            Member member=createMember(a);
            try {
                assertEquals(404,call(b,"PATCH","/api/maintenance-plans/"+plan+"/enabled",Map.of("enabled",false)).statusCode());
                assertEquals(404,call(b,"PUT","/api/maintenance-plans/"+plan,planBody(92001,91003,marker+" changed")).statusCode());
                HttpResponse<String> execute=callWithKey(b,"POST","/api/maintenance-plans/"+plan+"/executions",Map.of("result",marker),marker);
                assertEquals(404,execute.statusCode());
                assertEquals(404,call(b,"PATCH","/api/tenant-members/"+member.membershipId()+"/status",Map.of("enabled",false)).statusCode());
                long equipment=otherEquipment(db);
                ok(call(b,"GET","/api/equipment/"+equipment,null));
                long links=count(db,"SELECT COUNT(*) FROM equipment_spare_parts WHERE equipment_id=? AND spare_part_id=94001",equipment);
                assertEquals(404,call(b,"POST","/api/equipment/"+equipment+"/compatible-spare-parts",Map.of("sparePartId",94001)).statusCode());
                assertEquals(links,count(db,"SELECT COUNT(*) FROM equipment_spare_parts WHERE equipment_id=? AND spare_part_id=94001",equipment));
                assertEquals(1,count(db,"SELECT COUNT(*) FROM maintenance_plans WHERE id=? AND tenant_id=91001 AND name=? AND enabled=1 AND cycle_days=7",plan,marker));
                assertEquals(0,count(db,"SELECT COUNT(*) FROM maintenance_executions WHERE plan_id=?",plan));
                assertEquals(1,count(db,"SELECT COUNT(*) FROM tenant_memberships WHERE id=? AND tenant_id=91001 AND enabled=1",member.membershipId()));
                matrixEvidence("P4",Map.of("planId",plan,"membershipId",member.membershipId(),"statuses",List.of(404,404,404,404,404),"targetUnchanged",true));
            } finally {disablePlan(a,plan);}
        }
    }

    /** P5：请求体合法，避免用参数校验失败冒充权限拒绝。 */
    @Test
    void ordinaryMemberCannotUseAdminWriteEndpoints() throws Exception {
        try(Connection db=readDb()) {
            Session admin=admin(false);
            Member member=createMember(admin);
            String marker=matrixMarker();
            long plan=createPlan(admin,92001,91003,marker);
            try {
                long plans=count(db,"SELECT COUNT(*) FROM maintenance_plans WHERE tenant_id=91001");
                long links=count(db,"SELECT COUNT(*) FROM equipment_spare_parts WHERE tenant_id=91001");
                long role=count(db,"SELECT id FROM roles WHERE role_code='TENANT_ADMIN'");
                List<Integer> statuses=List.of(
                    call(member.session(),"PUT","/api/tenant-members/"+member.membershipId()+"/roles",Map.of("roleIds",List.of(role))).statusCode(),
                    call(member.session(),"PATCH","/api/tenant-members/"+member.membershipId()+"/status",Map.of("enabled",false)).statusCode(),
                    call(member.session(),"POST","/api/maintenance-plans",planBody(92001,91003,marker+" denied")).statusCode(),
                    call(member.session(),"PATCH","/api/maintenance-plans/"+plan+"/enabled",Map.of("enabled",false)).statusCode(),
                    call(member.session(),"POST","/api/equipment/92001/compatible-spare-parts",Map.of("sparePartId",94001)).statusCode());
                assertEquals(List.of(403,403,403,403,403),statuses);
                assertEquals(plans,count(db,"SELECT COUNT(*) FROM maintenance_plans WHERE tenant_id=91001"));
                assertEquals(links,count(db,"SELECT COUNT(*) FROM equipment_spare_parts WHERE tenant_id=91001"));
                assertEquals(0,count(db,"SELECT COUNT(*) FROM tenant_member_roles WHERE membership_id=?",member.membershipId()));
                assertEquals(1,count(db,"SELECT COUNT(*) FROM tenant_memberships WHERE id=? AND enabled=1",member.membershipId()));
                assertEquals(1,count(db,"SELECT COUNT(*) FROM maintenance_plans WHERE id=? AND enabled=1",plan));
                assertReadable(member.session());
                matrixEvidence("P5",Map.of("membershipId",member.membershipId(),"planId",plan,"statuses",statuses,"noWriteEffects",true,"ordinaryRead",200));
            } finally {disablePlan(admin,plan);}
        }
    }

    /** P6后端部分：同账号在A为管理员，在B为普通成员，来回切换不串权限。 */
    @Test
    void tenantSwitchMustReplaceContextAndRoles() throws Exception {
        try(Connection db=readDb()) {
            Session a=admin(false),b=admin(true);
            Member member=createMember(a);
            long bMembership=ok(call(b,"POST","/api/tenant-members",Map.of("userId",member.userId(),"roleIds",List.of()))).path("id").asLong();
            assertTrue(bMembership>0);
            long adminRole=count(db,"SELECT id FROM roles WHERE role_code='TENANT_ADMIN'");
            long aPlan=createPlan(a,92001,91003,matrixMarker()),bPlan=0;
            List<String> original=cookies(member.session());
            try {
                bPlan=createPlan(b,otherEquipment(db),91008,matrixMarker());
                ok(call(a,"PUT","/api/tenant-members/"+member.membershipId()+"/roles",Map.of("roleIds",List.of(adminRole))));
                JsonNode identity=ok(call(member.session(),"GET","/api/auth/me",null));
                assertTrue(hasAdmin(identity));assertEquals(91001,identity.path("activeTenant").path("id").asLong());
                ok(call(member.session(),"GET","/api/tenant-members",null));
                ok(call(member.session(),"GET","/api/maintenance-plans/"+aPlan,null));
                assertEquals(404,call(member.session(),"GET","/api/maintenance-plans/"+bPlan,null).statusCode());
                JsonNode selected=activate(member.session(),91002);
                assertEquals(91002,selected.path("activeTenant").path("id").asLong());assertFalse(hasAdmin(selected));
                assertEquals(bMembership,selected.path("membershipId").asLong());
                assertEquals(403,call(member.session(),"GET","/api/tenant-members",null).statusCode());
                assertEquals(404,call(member.session(),"GET","/api/maintenance-plans/"+aPlan,null).statusCode());
                ok(call(member.session(),"GET","/api/maintenance-plans/"+bPlan,null));
                verifyStatistics(db,member.session(),91002);
                // 无权企业切换失败，不得覆盖此前已选企业。
                assertEquals(403,call(member.session(),"POST","/api/auth/active-tenant",Map.of("tenantId",Long.MAX_VALUE)).statusCode());
                assertEquals(91002,ok(call(member.session(),"GET","/api/auth/me",null)).path("activeTenant").path("id").asLong());
                assertTrue(hasAdmin(activate(member.session(),91001)));
                ok(call(member.session(),"GET","/api/tenant-members",null));
                assertEquals(404,call(member.session(),"GET","/api/maintenance-plans/"+bPlan,null).statusCode());
                assertTrue(original.equals(cookies(member.session())),"切换企业不应靠更换登录身份");
                matrixEvidence("P6-backend",Map.of("userId",member.userId(),"aMembership",member.membershipId(),"bMembership",bMembership,
                        "aPlan",aPlan,"bPlan",bPlan,"sameSession",true,"aAdmin",true,"bAdmin",false,"invalidSwitchPreservesB",true));
            } finally {
                try {
                    ok(call(a,"PUT","/api/tenant-members/"+member.membershipId()+"/roles",Map.of("roleIds",List.of())));
                    assertEquals(0,count(db,"SELECT COUNT(*) FROM tenant_member_roles WHERE membership_id=?",member.membershipId()));
                } finally {
                    try {disablePlan(a,aPlan);} finally {if(bPlan>0)disablePlan(b,bPlan);}
                }
            }
        }
    }

    private Connection readDb() throws Exception {
        Connection db=DriverManager.getConnection("jdbc:mysql://localhost:3306/equipops_test",env("IT_DB_USER"),env("IT_DB_PASSWORD"));
        if(!"equipops_test".equals(db.getCatalog())){db.close();throw new IllegalStateException("错误测试库");}
        db.setReadOnly(true);db.setAutoCommit(true);return db;
    }
    private Session admin(boolean other) throws Exception {
        Session s=login(env(other?"IT_OTHER_ADMIN_ACCOUNT":"IT_ADMIN_ACCOUNT"),env(other?"IT_OTHER_ADMIN_PASSWORD":"IT_ADMIN_PASSWORD"));
        activate(s,other?91002:91001);return s;
    }
    private Member createMember(Session admin) throws Exception {
        String marker=matrixMarker(),password=UUID.randomUUID().toString();
        long user=ok(call(admin,"POST","/api/users",Map.of("account",marker,"password",password,"displayName","权限矩阵专用成员"))).path("id").asLong();
        assertTrue(user>0);
        long membership=ok(call(admin,"POST","/api/tenant-members",Map.of("userId",user,"roleIds",List.of()))).path("id").asLong();
        assertTrue(membership>0);
        Session session=login(marker,password);activate(session);
        matrixEvidence("fixture",Map.of("userId",user,"membershipId",membership,"marker",marker));
        return new Member(user,membership,session);
    }
    private boolean hasAdmin(JsonNode identity){for(JsonNode role:identity.path("roleCodes"))if("TENANT_ADMIN".equals(role.asText()))return true;return false;}
    private Map<String,Object> planBody(long equipment,long assignee,String marker){return Map.of("name",marker,"equipmentId",equipment,
            "planType","INSPECTION","content",marker,"cycleDays",7,"nextDueDate",java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(2).toString(),"assigneeUserId",assignee);}
    private long createPlan(Session admin,long equipment,long assignee,String marker) throws Exception {
        long id=ok(call(admin,"POST","/api/maintenance-plans",planBody(equipment,assignee,marker))).path("id").asLong();assertTrue(id>0);
        matrixEvidence("plan-fixture",Map.of("planId",id,"equipmentId",equipment,"marker",marker));return id;
    }
    private void disablePlan(Session admin,long id) throws Exception {ok(call(admin,"PATCH","/api/maintenance-plans/"+id+"/enabled",Map.of("enabled",false)));}
    private long otherEquipment(Connection db) throws Exception {long id=count(db,"SELECT MIN(id) FROM equipment WHERE tenant_id=91002");assertTrue(id>0);return id;}
    private JsonNode activate(Session s,long tenant) throws Exception{return ok(call(s,"POST","/api/auth/active-tenant",Map.of("tenantId",tenant)));}
    private HttpResponse<String> callWithKey(Session s,String method,String path,Object body,String key) throws Exception {
        return s.client().send(HttpRequest.newBuilder(URI.create(BASE+path)).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json")
                .header(s.header(),s.token()).header("Idempotency-Key",key).method(method,HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    /** 按接口给出的同一asOf独立核对统计，不能让A/B都返回同一个全局统计而误判通过。 */
    private void verifyStatistics(Connection db,Session s,long tenant) throws Exception {
        JsonNode stats=ok(call(s,"GET","/api/statistics/work-orders/overdue-summary",null));
        String asOf=stats.path("asOf").asText();assertFalse(asOf.isBlank());
        java.sql.Timestamp instant=java.sql.Timestamp.valueOf(java.time.LocalDateTime.parse(asOf));
        String where=" FROM work_orders WHERE tenant_id=? AND status IN ('PENDING','IN_PROGRESS','AWAITING_ACCEPTANCE')";
        assertEquals(count(db,"SELECT COUNT(*)"+where+" AND due_at<?",tenant,instant),stats.path("overdueCount").asLong());
        assertEquals(count(db,"SELECT COUNT(*)"+where+" AND due_at IS NULL",tenant),stats.path("withoutDeadlineCount").asLong());
        for(String[] item:List.of(new String[]{"PENDING","pendingOverdueCount"},new String[]{"IN_PROGRESS","inProgressOverdueCount"},new String[]{"AWAITING_ACCEPTANCE","awaitingAcceptanceOverdueCount"}))
            assertEquals(count(db,"SELECT COUNT(*)"+where+" AND status=? AND due_at<?",tenant,item[0],instant),stats.path(item[1]).asLong());
    }
    private String matrixMarker(){return "S3-PERM-"+UUID.randomUUID().toString().replace("-","");}
    private void matrixEvidence(String scenario,Map<String,Object> values) throws Exception {
        Path dir=Path.of("E:/ChatGPT/EquipPos/artifacts/s3-permission-matrix");Files.createDirectories(dir);
        Files.writeString(dir.resolve(scenario+"-"+UUID.randomUUID()+".json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(values));
    }
    private record Member(long userId,long membershipId,Session session){}

    private Session login(String account,String password) throws Exception {
        CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
        Session session=new Session(client,cookies,null,null);
        session=csrf(session);
        assertTrue(ok(call(session,"POST","/api/auth/login",Map.of("account",account,"password",password))).path("userId").asLong()>0);
        return csrf(session);
    }

    private void activate(Session session) throws Exception {
        ok(call(session,"POST","/api/auth/active-tenant",Map.of("tenantId",91001)));
    }

    private Session csrf(Session session) throws Exception {
        JsonNode token=ok(session.client().send(HttpRequest.newBuilder(URI.create(BASE+"/api/auth/csrf"))
                .timeout(Duration.ofSeconds(30)).GET().build(),HttpResponse.BodyHandlers.ofString()));
        assertFalse(token.path("token").asText().isBlank());
        assertFalse(token.path("headerName").asText().isBlank());
        return new Session(session.client(),session.cookies(),token.path("headerName").asText(),token.path("token").asText());
    }

    private HttpResponse<String> call(Session session,String method,String path,Object body) throws Exception {
        HttpRequest.Builder r=HttpRequest.newBuilder(URI.create(BASE+path)).timeout(Duration.ofSeconds(30));
        if(method.equals("GET"))r.GET();
        else r.header("Content-Type","application/json").header(session.header(),session.token())
                .method(method,HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return session.client().send(r.build(),HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200,response.statusCode());JsonNode payload=JSON.readTree(response.body());
        assertEquals(200,payload.path("code").asInt());return payload.path("data");
    }

    /** Cookie只在内存中比较，不写入断言错误消息或证据文件。 */
    private List<String> cookies(Session session) {
        return session.cookies().getCookieStore().getCookies().stream().map(c->c.getName()+"="+c.getValue()).sorted().toList();
    }

    private long count(Connection db,String sql,Object... args) throws Exception {
        try(PreparedStatement p=db.prepareStatement(sql)) {
            p.setQueryTimeout(10);for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);
            try(ResultSet r=p.executeQuery()){assertTrue(r.next());return r.getLong(1);}
        }
    }

    private void evidence(String name,Map<String,Object> values) throws Exception {
        Files.createDirectories(EVIDENCE);
        Files.writeString(EVIDENCE.resolve(name+".json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(values));
    }
    private static String env(String key){String v=System.getenv(key);if(v==null||v.isBlank())throw new IllegalStateException("缺少测试配置："+key);return v;}
    private record Session(HttpClient client,CookieManager cookies,String header,String token){}
}
