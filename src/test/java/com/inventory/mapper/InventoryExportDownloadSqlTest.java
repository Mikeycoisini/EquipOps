package com.inventory.mapper;

import com.inventory.export.InventoryExportDownloadRecord;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryExportDownloadSqlTest {
    Configuration config()throws Exception{var c=new Configuration();try(var in=getClass().getClassLoader().getResourceAsStream("mapper/InventoryExportDownloadMapper.xml")){new XMLMapperBuilder(in,c,"mapper/InventoryExportDownloadMapper.xml",c.getSqlFragments()).parse();}return c;}
    @Test void 下载内部查询与缺失记录始终限定企业本人及同一个文件()throws Exception{
        var c=config();var params=Map.of("tenantId",7L,"userId",4L,"taskId",9L,"task",new InventoryExportDownloadRecord());
        for(var method:List.of("selectOwnDownload","canDownload","markFileMissing")){
            String sql=c.getMappedStatement("com.inventory.mapper.InventoryExportDownloadMapper."+method).getBoundSql(params).getSql().replaceAll("\\s+"," ");
            assertTrue(sql.contains("tenant_id=?"));assertTrue(sql.contains("created_by=?"));assertTrue(sql.contains("id=?"));
            if(!method.equals("selectOwnDownload")){assertTrue(sql.contains("run_token=?"));assertTrue(sql.contains("storage_key=?"));assertTrue(sql.contains("status='SUCCEEDED'"));assertTrue(sql.contains("expires_at > CURRENT_TIMESTAMP(6)"));}
            if(method.equals("canDownload"))assertTrue(sql.contains("u.enabled=1 AND m.enabled=1 AND te.enabled=1"));
            if(method.equals("markFileMissing")){assertTrue(sql.contains("error_code='FILE_MISSING'"));assertFalse(sql.contains("SET status"));}
        }
    }
}
