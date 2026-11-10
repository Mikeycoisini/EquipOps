package com.support;

import java.sql.*;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** 显式命令行隔离夹具，不进入产品JAR；仅处理本次UUID标识的合成设备、工单和团队。 */
public final class A4Fixture {
    private A4Fixture() {}
    static String key(String value) {
        if(value==null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalArgumentException("夹具键必须是规范UUID");
        return value;
    }
    public static void main(String[] args) {
        try {
            if(args.length!=2 || !Set.of("create","cleanup").contains(args[0]))throw new IllegalArgumentException("仅支持create/cleanup UUID");
            String tag=key(args[1]);
            try(var db=DriverManager.getConnection(required("DB_URL"),required("DB_USER"),required("DB_PASSWORD"))) {
                // 实际库名、确认值和迁移都必须通过，不能只相信连接URL。
                String schema;
                try(var statement=db.createStatement();var result=statement.executeQuery("SELECT DATABASE()")){result.next();schema=result.getString(1);}
                A1Bootstrap.validateTarget(schema,System.getenv("A1_DB_CONFIRM"),System.getenv("A1_TEST_PASSWORD"));
                if(number(db,"SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL")!=17
                        || number(db,"SELECT COUNT(*) FROM flyway_schema_history WHERE success=0")!=0)throw new IllegalStateException("需要17项正式迁移");
                db.setAutoCommit(false);
                try {
                    var result=args[0].equals("create")?create(db,tag):cleanup(db,tag);
                    db.commit();System.out.println(new ObjectMapper().writeValueAsString(result));
                }catch(Exception error){db.rollback();throw error;}
            }
        }catch(Exception error){System.err.println("A4隔离夹具失败："+error.getClass().getSimpleName());System.exit(1);}
    }
    private static Map<String,Object> create(Connection db,String tag)throws SQLException {
        // 使用已有A1测试身份，不增加用户/企业，不重置任何密码或授权。
        long tenant=number(db,"SELECT id FROM tenants WHERE tenant_code='A1_A' AND enabled=1");
        long other=number(db,"SELECT id FROM tenants WHERE tenant_code='A1_B' AND enabled=1");
        long admin=number(db,"SELECT id FROM users WHERE account='a1_admin' AND enabled=1");
        long user=number(db,"SELECT id FROM users WHERE account='a1_member' AND enabled=1");
        long member=number(db,"SELECT id FROM tenant_memberships WHERE tenant_id=? AND user_id=? AND enabled=1",tenant,user);
        long site=number(db,"SELECT id FROM sites WHERE tenant_id=? AND is_default=1 AND enabled=1",tenant);
        if(number(db,"SELECT COUNT(*) FROM equipment WHERE tenant_id=? AND asset_code=?",tenant,"A4_"+tag)==0)
            execute(db,"INSERT INTO equipment(tenant_id,site_id,asset_code,name,status) VALUES(?,?,?,'A4隔离验证设备','STOPPED')",tenant,site,"A4_"+tag);
        long equipment=number(db,"SELECT id FROM equipment WHERE tenant_id=? AND asset_code=?",tenant,"A4_"+tag);
        for(String prefix:List.of("A4A-","A4B-")) {
            if(number(db,"SELECT COUNT(*) FROM work_orders WHERE tenant_id=? AND order_no=?",tenant,prefix+tag)==0)
                execute(db,"INSERT INTO work_orders(tenant_id,site_id,order_no,title,equipment_id,status,assignee_user_id,created_by) VALUES(?,?,?,'A4隔离验证工单',?,'PENDING',?,?)",tenant,site,prefix+tag,equipment,prefix.equals("A4A-")?user:null,admin);
        }
        // 输出仅含非秘密标识符，浏览器和HTTP检查自行通过真实会话管理个人范围。
        var result=new LinkedHashMap<String,Object>();result.put("fixtureKey",tag);result.put("teamCode","A4_"+tag);
        result.put("tenantAId",Long.toString(tenant));result.put("tenantBId",Long.toString(other));result.put("adminUserId",Long.toString(admin));
        result.put("memberUserId",Long.toString(user));result.put("memberMembershipId",Long.toString(member));result.put("siteId",Long.toString(site));result.put("equipmentId",Long.toString(equipment));
        result.put("workOrderId",Long.toString(number(db,"SELECT id FROM work_orders WHERE tenant_id=? AND order_no=?",tenant,"A4A-"+tag)));
        result.put("otherOrderId",Long.toString(number(db,"SELECT id FROM work_orders WHERE tenant_id=? AND order_no=?",tenant,"A4B-"+tag)));
        return result;
    }
    private static Map<String,Object> cleanup(Connection db,String tag)throws SQLException {
        // 清理只匹配当前UUID完整业务编号，禁止按共享A1企业全量删除。
        long tenant=number(db,"SELECT id FROM tenants WHERE tenant_code='A1_A'");
        var orders=ids(db,"SELECT id FROM work_orders WHERE tenant_id=? AND order_no IN (?,?)",tenant,"A4A-"+tag,"A4B-"+tag);
        for(long order:orders) {
            // 若意外产生库存事务则停止，不能为了清理工单破坏库存守恒。
            if(number(db,"SELECT COUNT(*) FROM inventory_transactions WHERE tenant_id=? AND work_order_id=?",tenant,order)!=0)throw new IllegalStateException("夹具产生库存事务，需单独核查");
            execute(db,"DELETE FROM work_order_reassignments WHERE tenant_id=? AND work_order_id=?",tenant,order);
            execute(db,"DELETE FROM work_order_logs WHERE work_order_id=?",order);
            execute(db,"DELETE FROM work_order_parts WHERE work_order_id=?",order);
            execute(db,"DELETE FROM work_orders WHERE tenant_id=? AND id=?",tenant,order);
        }
        for(long team:ids(db,"SELECT id FROM teams WHERE tenant_id=? AND team_code=?",tenant,"A4_"+tag)) {
            execute(db,"DELETE FROM team_operations WHERE tenant_id=? AND team_id=?",tenant,team);
            execute(db,"DELETE FROM team_members WHERE tenant_id=? AND team_id=?",tenant,team);
            execute(db,"DELETE FROM team_site_scopes WHERE tenant_id=? AND team_id=?",tenant,team);
            execute(db,"DELETE FROM teams WHERE tenant_id=? AND id=?",tenant,team);
        }
        execute(db,"DELETE FROM equipment WHERE tenant_id=? AND asset_code=?",tenant,"A4_"+tag);
        // 成功/失败审计保留为真实验证历史，不猜测归属删除null目标记录。
        return Map.of("fixtureKey",tag,"cleaned",true,"remainingOrders",number(db,"SELECT COUNT(*) FROM work_orders WHERE tenant_id=? AND order_no IN (?,?)",tenant,"A4A-"+tag,"A4B-"+tag),"remainingTeams",number(db,"SELECT COUNT(*) FROM teams WHERE tenant_id=? AND team_code=?",tenant,"A4_"+tag));
    }
    private static List<Long> ids(Connection db,String sql,Object... args)throws SQLException {
        try(var statement=db.prepareStatement(sql)){for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);try(var result=statement.executeQuery()){var ids=new ArrayList<Long>();while(result.next())ids.add(result.getLong(1));return ids;}}
    }
    private static long number(Connection db,String sql,Object... args)throws SQLException {var ids=ids(db,sql,args);if(ids.size()!=1)throw new IllegalStateException("隔离夹具目标不唯一");return ids.get(0);}
    private static void execute(Connection db,String sql,Object... args)throws SQLException {try(var statement=db.prepareStatement(sql)){for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);statement.executeUpdate();}}
    private static String required(String name){String value=System.getenv(name);if(value==null || value.isBlank())throw new IllegalArgumentException("缺少隔离环境变量");return value;}
}
