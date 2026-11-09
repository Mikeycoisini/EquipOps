package com.inventory.service;

import com.equipops.domain.vo.InventoryExportTaskVO;
import com.inventory.export.InventoryExportDownloadFile;

public interface InventoryExportDownloadService {
    /** 重新验证当前身份与本人任务，打开有效文件，调用者负责关闭返回句柄。 */
    InventoryExportDownloadFile openDownload(Long taskId);
    /** 管理员取消当前企业下本人任务；排队直接取消，运行中提交取消请求。 */
    InventoryExportTaskVO cancel(Long id);
}
