package com.inventory.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.equipops.domain.dto.inventory.SparePartPageQueryDto;
import com.equipops.domain.dto.inventory.SparePartUpsertDto;
import com.equipops.domain.vo.SparePartVO;

/** 备件目录业务；不通过目录接口修改库存余额。 */
public interface SparePartService {
    /** 分页查询当前企业备件目录。 */
    IPage<SparePartVO> getSparePartPage(SparePartPageQueryDto query);

    /** 查询当前企业备件详情。 */
    SparePartVO getSparePartDetail(Long id);

    /** 企业管理员新增备件目录，初始库存为0。 */
    SparePartVO addSparePart(SparePartUpsertDto request);

    /** 企业管理员修改备件目录，库存余额保持不变。 */
    SparePartVO updateSparePart(Long id, SparePartUpsertDto request);
}
