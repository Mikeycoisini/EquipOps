package com.equipops.domain.dto.inventory;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.*;

/** 仓库余额分页条件；不接收客户端指定的企业 ID。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseStockPageQueryDto {
    /** 页码，小于 1 时按第 1 页处理。 */
    private long current = 1;
    /** 每页条数，业务层限制在 1 到 100。 */
    private long size = 10;
    /** 仓库 ID；省略时查询当前企业全部仓库的已建账余额。 */
    @Positive(message = "仓库 ID 必须大于0")
    private Long warehouseId;
    /** 备件 ID；提供时必须属于当前企业。 */
    @Positive(message = "备件 ID 必须大于0")
    private Long sparePartId;
    /** 按备件编号或名称模糊查询，最多 160 个字符。 */
    @Size(max = 160, message = "关键字不能超过160个字符")
    private String keyword;
    /** true 仅查低库存；false 或省略不限制，低库存指数量严格小于安全库存。 */
    private Boolean lowStockOnly;
}
