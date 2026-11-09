package com.tenant.service.impl;

import com.EquipOpsApplication;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipops.domain.vo.TenantMemberVO;
import com.tenant.mapper.TenantMembershipMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 只读执行真实 SQL；使用不存在的企业 ID 0，不创建或修改测试数据。 */
@SpringBootTest(classes = EquipOpsApplication.class)
@Transactional(readOnly = true)
class TenantMemberMapperIntegrationTest {
    @Autowired private TenantMembershipMapper mapper;

    @Test
    void 分页SQL可以执行且不会返回其他企业成员() {
        var page = mapper.selectMemberPage(new Page<TenantMemberVO>(1, 3), 0L, "admin", false);
        assertTrue(page.getRecords().isEmpty());
        assertEquals(0, page.getTotal());
        assertEquals(3, page.getSize());
    }

    @Test
    void 角色SQL可以执行且空列表不会查出其他成员角色() {
        assertTrue(mapper.selectMemberRoles(0L, List.of(0L)).isEmpty());
        assertTrue(mapper.selectMemberRoles(0L, List.of()).isEmpty());
    }
}
