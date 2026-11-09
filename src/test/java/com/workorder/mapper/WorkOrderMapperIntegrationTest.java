package com.workorder.mapper;

import com.EquipOpsApplication;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipops.domain.dto.workorder.WorkOrderPageQueryDto;
import com.equipops.domain.entity.WorkOrderEntity;
import com.equipops.domain.enums.WorkOrderPriority;
import com.equipops.domain.enums.WorkOrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

/** 用不存在的企业和工单 ID 执行只读查询，不创建或派发任何真实工单。 */
@SpringBootTest(classes = EquipOpsApplication.class)
@Transactional(readOnly = true)
class WorkOrderMapperIntegrationTest {
    @Autowired private WorkOrderMapper workOrderMapper;
    @Autowired private WorkOrderLogMapper workOrderLogMapper;

    @Test
    void 分页插件和枚举筛选可以在MySQL执行() {
        WorkOrderPageQueryDto query = new WorkOrderPageQueryDto();
        query.setStatus(WorkOrderStatus.PENDING);
        query.setPriority(WorkOrderPriority.HIGH);
        var page = workOrderMapper.selectWorkOrderPage(new Page<WorkOrderEntity>(1, 3), 0L, query, "test");
        assertTrue(page.getRecords().isEmpty());
        assertEquals(0, page.getTotal());
        assertEquals(3, page.getSize());
    }

    @Test
    void 日志关联查询可以在MySQL执行() {
        assertTrue(workOrderLogMapper.selectByWorkOrderId(0L, 0L).isEmpty());
    }
}
