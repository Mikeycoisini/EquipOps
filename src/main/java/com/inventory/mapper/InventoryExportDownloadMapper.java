package com.inventory.mapper;

import com.inventory.export.InventoryExportDownloadRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 下载专用SQL，不向原导出ServiceImpl添加下载逻辑。 */
@Mapper
public interface InventoryExportDownloadMapper {
    InventoryExportDownloadRecord selectOwnDownload(@Param("tenantId") Long tenantId,
            @Param("userId") Long userId,@Param("taskId") Long taskId);
    /** 旧任务没有可信厂区快照时要求重新导出。 */
    boolean hasTaskSites(@Param("task") InventoryExportDownloadRecord task);
    boolean canDownload(@Param("task") InventoryExportDownloadRecord task);
    int markFileMissing(@Param("task") InventoryExportDownloadRecord task);
}
