package com.inventory.mapper;

import com.inventory.export.InventoryExportDownloadRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 下载专用SQL，不向原导出ServiceImpl添加下载逻辑。 */
@Mapper
public interface InventoryExportDownloadMapper {
    InventoryExportDownloadRecord selectOwnDownload(@Param("tenantId") Long tenantId,
            @Param("userId") Long userId,@Param("taskId") Long taskId);
    boolean canDownload(@Param("task") InventoryExportDownloadRecord task);
    int markFileMissing(@Param("task") InventoryExportDownloadRecord task);
}
