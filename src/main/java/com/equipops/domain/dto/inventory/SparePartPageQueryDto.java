package com.equipops.domain.dto.inventory;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.validation.constraints.*;


/** 列表查询条件；企业范围从登录上下文取得，不接收客户端 tenantId。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SparePartPageQueryDto {
    /** 当前页码，小于1时按第1页处理。 */
    private long current = 1;

    /** 每页条数，业务层限制为1到100。 */
    private long size = 10;

    /** 备件编号或名称关键字。 */
    @Size(max = 160, message = "关键字不能超过160个字符")
    private String keyword;

    /** 备件分类，不传时不筛选。 */
    @Size(max = 100, message = "分类不能超过100个字符")
    private String category;

    /** 是否启用，不传时查询两种状态。 */
    private Boolean enabled;
}
