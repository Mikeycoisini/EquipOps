package com.inventory.service;

import com.equipops.domain.dto.inventory.InventoryExportCreateDto;
import com.equipops.domain.vo.InventoryExportTaskVO;

/** 库存流水导出任务创建与本人任务查询。 */
public interface InventoryExportService {
    /** 校验筛选及权限，创建任务，并在事务提交后安排后台执行。 */
    InventoryExportTaskVO create(InventoryExportCreateDto request);
    /** 分页查询当前企业下本人创建的任务。 */
    com.baomidou.mybatisplus.core.metadata.IPage<InventoryExportTaskVO> getTaskPage(
            com.equipops.domain.dto.inventory.InventoryExportPageQueryDto query);

    /** 读取当前企业下本人的任务；不可见或不存在时返回404。 */
    InventoryExportTaskVO getTaskDetail(Long id);
}
