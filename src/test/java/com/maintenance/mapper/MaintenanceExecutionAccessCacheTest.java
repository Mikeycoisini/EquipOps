package com.maintenance.mapper;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.BaseExecutor;
import org.apache.ibatis.executor.BatchResult;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.defaults.DefaultSqlSession;
import org.apache.ibatis.transaction.Transaction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实SqlSession和BaseExecutor一级缓存，数据库行读取为内存替身。 */
class MaintenanceExecutionAccessCacheTest {
    private static class AccessExecutor extends BaseExecutor {
        boolean enabled=true;
        int reads;
        AccessExecutor(Configuration config) {
            super(config,new Transaction() {
                public Connection getConnection(){return null;}
                public void commit(){}
                public void rollback(){}
                public void close(){}
                public Integer getTimeout(){return null;}
            });
        }
        @Override protected int doUpdate(MappedStatement ms,Object parameter){throw new AssertionError("不能写入");}
        @Override protected List<BatchResult> doFlushStatements(boolean rollback){return List.of();}
        @Override @SuppressWarnings("unchecked")
        protected <E> List<E> doQuery(MappedStatement ms,Object parameter,RowBounds bounds,ResultHandler handler,BoundSql sql) {
            reads++; return enabled ? List.of((E)Long.valueOf(30L)) : List.of();
        }
        @Override protected <E> Cursor<E> doQueryCursor(MappedStatement ms,Object parameter,RowBounds bounds,BoundSql sql){throw new AssertionError("不能使用游标");}
    }
    @Test void 锁后权限查询不会复用同一SqlSession中的早期启用结果() throws Exception {
        var config=new Configuration(); String resource="mapper/MaintenancePlanMapper.xml";
        try(var input=getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input); new XMLMapperBuilder(input,config,resource,config.getSqlFragments()).parse();
        }
        String statement="com.maintenance.mapper.MaintenancePlanMapper.selectEnabledExecutorForUpdate";
        var executor=new AccessExecutor(config);
        try(var session=new DefaultSqlSession(config,executor)) {
            var params=Map.of("tenantId",7L,"userId",30L);
            assertEquals(30L,(Long)session.selectOne(statement,params));
            executor.enabled=false;
            assertNull(session.selectOne(statement,params));
            assertEquals(2,executor.reads,"权限状态变化后必须再次执行数据库边界读取");
            String sql=config.getMappedStatement(statement).getBoundSql(params).getSql().replaceAll("\\s+"," ").trim();
            assertTrue(sql.endsWith("FOR UPDATE"));
            assertTrue(sql.contains("tm.tenant_id = ? AND tm.user_id = ?"));
            assertTrue(sql.contains("tm.enabled = TRUE AND u.enabled = TRUE AND t.enabled = TRUE"));
        }
    }
}
