package com.support;

import java.sql.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** 仅供显式调用的A1隔离夹具，不进入产品JAR，不提供生产管理员入口。 */
public final class A1Bootstrap {
    private A1Bootstrap() {}

    static void validateTarget(String schema, String confirmation, String password) {
        // 1. 实际连接的库名必须是明确的A1隔离库，且与操作者确认值一致。
        if (schema == null || !schema.matches("equipops_a1_[A-Za-z0-9_]+") || !schema.equals(confirmation)) {
            throw new IllegalArgumentException("只允许显式确认的equipops_a1_隔离库");
        }
        // 2. 测试密码只从环境取得，限制BCrypt的UTF-8字节上限。
        if (password == null || password.length() < 16 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("A1_TEST_PASSWORD必须为16字符以上且不超过72字节");
        }
    }

    public static void main(String[] args) {
        try {
            boolean checkOnly = args.length == 1 && "--check-target".equals(args[0]);
            if (args.length > 0 && !checkOnly) throw new IllegalArgumentException("未知初始化参数");
            initialize(checkOnly);
            System.out.println(checkOnly ? "A1隔离目标只读核对通过" : "A1隔离夹具初始化/重复核对通过");
        } catch (Exception error) {
            // 不将JDBC异常正文、连接串或密码输出到CI日志。
            System.err.println("A1初始化失败：" + error.getClass().getSimpleName());
            System.exit(1);
        }
    }

    private static void initialize(boolean checkOnly) throws Exception {
        // 1. 连接后读取实际库名，不能只相信连接URL中的字符串。
        try (Connection db = DriverManager.getConnection(required("DB_URL"), required("DB_USER"), required("DB_PASSWORD"))) {
            // 只读预检不触发Spring或Flyway，不写任何业务数据。
            if (checkOnly) db.setReadOnly(true);
            String schema;
            try (var statement = db.createStatement(); var result = statement.executeQuery("SELECT DATABASE()")) {
                result.next(); schema = result.getString(1);
            }
            String password = System.getenv("A1_TEST_PASSWORD");
            validateTarget(schema, System.getenv("A1_DB_CONFIRM"), password);
            if (checkOnly) return;
            // 2. 只接受已完成当前15个正式迁移的库，不在初始化器执行DDL。
            if (number(db, "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL") != 15
                    || number(db, "SELECT COUNT(*) FROM flyway_schema_history WHERE success=0") != 0) {
                throw new IllegalStateException("需先验证V1—V15迁移");
            }
            db.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            db.setAutoCommit(false);
            try {
                // 3. 非空库仅允许完整的既有A1夹具核对；不覆盖用户密码或修改其他数据。
                long users = number(db, "SELECT COUNT(*) FROM users");
                long tenants = number(db, "SELECT COUNT(*) FROM tenants");
                if (users != 0 || tenants != 0) {
                    verifyExisting(db, password);
                } else {
                    // 4. 注册必要角色，保留V4已经登记的验收角色。
                    execute(db, "INSERT INTO roles(role_code,name) SELECT 'TENANT_ADMIN','企业管理员' WHERE NOT EXISTS (SELECT 1 FROM roles WHERE role_code='TENANT_ADMIN')");
                    var encoder = new BCryptPasswordEncoder();
                    execute(db, "INSERT INTO users(account,password_hash,display_name) VALUES(?,?,?),(?,?,?)",
                            "a1_admin", encoder.encode(password), "A1验证管理员", "a1_member", encoder.encode(password), "A1验证成员");
                    // 5. 两企业各建默认厂区和仓库；管理员在B企业只具有普通成员身份。
                    execute(db, "INSERT INTO tenants(tenant_code,name) VALUES('A1_A','A1验证企业A'),('A1_B','A1验证企业B')");
                    execute(db, "INSERT INTO sites(tenant_id,site_code,name,is_default) SELECT id,'DEFAULT','A1默认厂区',1 FROM tenants");
                    execute(db, "INSERT INTO warehouses(tenant_id,site_id,warehouse_code,name,is_default) SELECT tenant_id,id,'DEFAULT','A1默认仓库',1 FROM sites");
                    execute(db, "INSERT INTO tenant_memberships(tenant_id,user_id) SELECT t.id,u.id FROM tenants t CROSS JOIN users u WHERE u.account='a1_admin' OR t.tenant_code='A1_A'");
                    execute(db, "INSERT INTO tenant_member_roles(tenant_id,membership_id,role_id) SELECT m.tenant_id,m.id,r.id FROM tenant_memberships m JOIN tenants t ON t.id=m.tenant_id JOIN users u ON u.id=m.user_id CROSS JOIN roles r WHERE t.tenant_code='A1_A' AND u.account='a1_admin' AND r.role_code='TENANT_ADMIN'");
                    // 6. 在同一事务核对插入结果，任一步失败全部回滚。
                    verifyExisting(db, password);
                }
                db.commit();
            } catch (Exception failure) {
                db.rollback();
                throw failure;
            }
        }
    }

    private static void verifyExisting(Connection db, String password) throws Exception {
        // 1. 不能把一个已有业务库误认作可重复初始化的测试库。
        if (number(db, "SELECT COUNT(*) FROM users") != 2 || number(db, "SELECT COUNT(*) FROM tenants") != 2
                || number(db, "SELECT COUNT(*) FROM tenants WHERE tenant_code IN ('A1_A','A1_B') AND enabled=1") != 2
                || number(db, "SELECT COUNT(*) FROM tenant_memberships WHERE enabled=1") != 3
                || number(db, "SELECT COUNT(*) FROM tenant_memberships") != 3
                || number(db, "SELECT COUNT(*) FROM tenant_memberships m JOIN tenants t ON t.id=m.tenant_id JOIN users u ON u.id=m.user_id WHERE (u.account='a1_admin' AND t.tenant_code IN ('A1_A','A1_B')) OR (u.account='a1_member' AND t.tenant_code='A1_A')") != 3
                || number(db, "SELECT COUNT(*) FROM sites WHERE is_default=1 AND enabled=1") != 2
                || number(db, "SELECT COUNT(*) FROM warehouses WHERE is_default=1 AND enabled=1") != 2
                || number(db, "SELECT COUNT(*) FROM tenant_member_roles") != 1
                || number(db, "SELECT COUNT(*) FROM tenant_member_roles mr JOIN tenant_memberships m ON m.id=mr.membership_id AND m.tenant_id=mr.tenant_id JOIN tenants t ON t.id=m.tenant_id JOIN users u ON u.id=m.user_id JOIN roles r ON r.id=mr.role_id WHERE u.account='a1_admin' AND t.tenant_code='A1_A' AND r.role_code='TENANT_ADMIN'") != 1) {
            throw new IllegalStateException("既有库并非预期A1夹具");
        }
        // 2. 既有密码必须匹配本次受保护环境变量，不重置密码或授予新权限。
        var encoder = new BCryptPasswordEncoder();
        try (var statement = db.prepareStatement("SELECT account,password_hash,enabled FROM users" ); var rows = statement.executeQuery()) {
            while (rows.next()) {
                if (!java.util.Set.of("a1_admin", "a1_member").contains(rows.getString(1))
                        || !rows.getBoolean(3) || !encoder.matches(password, rows.getString(2))) {
                    throw new IllegalStateException("既有测试身份与环境不匹配");
                }
            }
        }
    }

    private static long number(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement(); var result = statement.executeQuery(sql)) {
            result.next(); return result.getLong(1);
        }
    }

    private static void execute(Connection db, String sql, String... values) throws SQLException {
        try (var statement = db.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setString(index + 1, values[index]);
            statement.executeUpdate();
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少变量：" + name);
        return value;
    }
}
