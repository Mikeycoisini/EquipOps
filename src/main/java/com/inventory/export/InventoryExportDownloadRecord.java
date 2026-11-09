package com.inventory.export;

import com.equipops.domain.enums.InventoryExportStatus;
import lombok.Data;

/** 下载内部查询结果，不作为JSON返回；expired由数据库时间计算。 */
@Data
public class InventoryExportDownloadRecord {
    private Long id;
    private Long tenantId;
    private Long createdBy;
    private InventoryExportStatus status;
    private String storageKey;
    private String runToken;
    private Long fileSize;
    private Boolean expired;
}
