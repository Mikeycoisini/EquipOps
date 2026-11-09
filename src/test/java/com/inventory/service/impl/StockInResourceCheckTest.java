package com.inventory.service.impl;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 保留第三步资源反例，使用完整入库方法验证，不连接数据库。 */
class StockInResourceCheckTest {
    private final StockInTestFixture f = new StockInTestFixture();
    private void rejects(int status) {
        var error = assertThrows(ResponseStatusException.class, () -> f.service().stocktoIn("request-1", f.request()));
        assertEquals(status, error.getStatusCode().value());
        assertTrue(f.writes.isEmpty());
    }
    @Test void 未知或其他企业仓库返回不存在() { f.warehouse = null; rejects(404); }
    @Test void 仓库未启用时拒绝入库() {
        for (Boolean enabled : Arrays.asList(false, null)) { f.warehouse.setWarehouseEnabled(enabled); rejects(409); }
    }
    @Test void 厂区未关联或未启用时拒绝入库() {
        f.warehouse.setSiteId(null); rejects(409);
        f.warehouse.setSiteId(30L);
        for (Boolean enabled : Arrays.asList(false, null)) { f.warehouse.setSiteEnabled(enabled); rejects(409); }
    }
    @Test void 未知或其他企业备件返回不存在() { f.partEnabled = null; rejects(404); }
    @Test void 停用备件不能入库() { f.partEnabled = false; rejects(409); }
    @Test void 同企业启用的仓库厂区备件允许完成入库() {
        assertNotNull(f.service().stocktoIn("request-1", f.request()));
    }
}
