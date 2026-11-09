package com.inventory.service.impl;

import com.auth.service.CurrentAccessService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipops.domain.dto.inventory.SparePartPageQueryDto;
import com.equipops.domain.dto.inventory.SparePartUpsertDto;
import com.equipops.domain.entity.SparePartEntity;
import com.equipops.domain.vo.SparePartVO;
import com.inventory.mapper.SparePartMapper;
import com.inventory.service.SparePartService;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/** 备件目录实现；库存余额只能由后续库存事务处理。
 *
 *
 *
 * */
@Service
@RequiredArgsConstructor
public class SparePartServiceImpl implements SparePartService {
    /** 可信当前企业及管理员检查。 */
    private final CurrentAccessService currentAccessService;
    /** 备件表操作。 */
    private final SparePartMapper sparePartMapper;

    /** 编号或名称采用同一个括号分组，保证 OR 条件不会绕过企业限制。 */
    @Override
    public IPage<SparePartVO> getSparePartPage(SparePartPageQueryDto query) {
        // 1. 验证当前企业及成员关系，取得本次查询的企业范围。
        Long tenantId = currentAccessService.requireActiveTenantId();
        // 2. hasText 判断是否包含非空白字符；trim 去除首尾空白，空关键字不筛选。
        String keyword = StringUtils.hasText(query.getKeyword()) ? query.getKeyword().trim() : null;
        // 3. 先固定企业，再按需要添加启用状态和分类条件。
        // eq(condition, column, value) 只有 condition 为 true 时才添加该条件。
        LambdaQueryWrapper<SparePartEntity> wrapper = new LambdaQueryWrapper<SparePartEntity>()
                .eq(SparePartEntity::getTenantId, tenantId)
                .eq(query.getEnabled() != null, SparePartEntity::getEnabled, query.getEnabled())
                .eq(StringUtils.hasText(query.getCategory()), SparePartEntity::getCategory, query.getCategory());
        // 4. 编号或名称匹配任意一个即可，但两者必须放在同一括号中。
        // 等价于 tenant_id = ? AND (part_code LIKE ? OR name LIKE ?)。
        if (keyword != null) {
            wrapper.and(part -> part.like(SparePartEntity::getPartCode, keyword)
                    .or().like(SparePartEntity::getName, keyword));
        }
        // 5. 按 ID 倒序，让最近登记的备件优先显示。
        wrapper.orderByDesc(SparePartEntity::getId);
        // 6. 限制页码及每页条数，再由分页插件查询本页和总数。
        // convert(this::toVO) 对本页每条记录调用 toVO，并保留分页信息。
        return sparePartMapper.selectPage(new Page<>(Math.max(1, query.getCurrent()),
                Math.max(1, Math.min(100, query.getSize()))), wrapper).convert(this::toVO);
    }

    /** 只返回当前企业可见的备件。 */
    @Override
    public SparePartVO getSparePartDetail(Long id) {
        // 依次执行：获取当前企业 → 按企业和 ID 查备件 → 转换 VO 返回。
        // 最后的 false 表示此次是普通详情查询，不追加 FOR UPDATE 行锁。
        return toVO(requirePart(id, currentAccessService.requireActiveTenantId(), false));
    }

    /** 显式选择可保存的目录字段，拒绝通过目录接口写库存。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public SparePartVO addSparePart(SparePartUpsertDto request) {
        // 1. 验证企业管理员；企业归属只能由服务端设置。
        Long tenantId = currentAccessService.requireTenantAdminTenantId();
        // 2. 检查编号、名称、安全库存等参数，同时拒绝传入库存余额。
        validateRequest(request);
        // 3. 预检查本企业编号是否重复；最后一个 null 表示新增时没有需要排除的自身 ID。
        ensureUniqueCode(tenantId, request.getPartCode().trim(), null);
        // 4. 创建新实体，只设置允许保存的目录字段，不直接复制客户端所有属性。
        SparePartEntity part = new SparePartEntity();
        part.setTenantId(tenantId);
        copyCatalogFields(request, part);
        // 5. 新目录的库存初始为0，后续通过正式入库业务增加，不能在这里伪造库存。
        part.setStockQuantity(BigDecimal.ZERO);
        // 6. 插入数据库并检查影响行数。@Transactional 让本方法异常时回滚。
        try {
            if (sparePartMapper.insert(part) != 1) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "备件保存失败");
            }
        } catch (DuplicateKeyException exception) {
            // 查询预检查不能阻止并发新增，数据库唯一索引负责最终兜底。
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前企业的备件编号已存在");
        }
        // 7. 插入成功后 MP 会回填主键；按主键重新查询，取得数据库生成的时间，再转 VO。
        return toVO(requirePart(part.getId(), tenantId, false));
    }

    /** 仅更新目录列，避免覆盖并发库存变更及数据库审计时间。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public SparePartVO updateSparePart(Long id, SparePartUpsertDto request) {
        // 1. 验证当前企业管理员，再检查请求参数。
        Long tenantId = currentAccessService.requireTenantAdminTenantId();
        validateRequest(request);
        // 2. true 表示追加 FOR UPDATE，在当前事务中锁住目标备件行，避免同时修改目录。
        SparePartEntity part = requirePart(id, tenantId, true);
        // 3. 检查新编号是否被其他备件使用；排除当前 ID，保留原编号不算重复。
        ensureUniqueCode(tenantId, request.getPartCode().trim(), id);
        // 4. 将本次允许修改的目录资料填入对象，不改企业归属和库存数量。
        copyCatalogFields(request, part);
        // 5. 目录字段的更新 SQL 放在 SparePartMapper.xml，支持清空规格、分类或库位。
        // SQL 同时限定当前企业和备件 ID，不修改库存数量。
        // 6. 执行更新；若并发使用了重复编号，唯一索引抛出的异常转为409冲突。
        try {
            // 相同值重复提交允许成功；已锁定且确认存在，不以影响0行判定记录不存在。
            sparePartMapper.updateCatalog(tenantId, part);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前企业的备件编号已存在");
        }
        // 7. 重新读取保存后的数据，返回数据库中的最新字段与更新时间。
        return toVO(requirePart(id, tenantId, false));
    }

    /** 参数注解处理 HTTP 校验，此处同时保护 Service 的直接调用。 */
    private void validateRequest(SparePartUpsertDto request) {
        // 请求对象为空时，|| 短路，后面不会调用 request 的方法，避免空指针。
        if (request == null || !StringUtils.hasText(request.getPartCode()) || !StringUtils.hasText(request.getName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "备件编号和名称不能为空");
        }
        // 库存数量即使传0也拒绝：目录资料与库存变动应走不同业务入口。
        if (request.getStockQuantity() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目录接口不允许设置库存数量");
        }
        // 安全库存允许不传，但传入后不能是负数；signum 小于0表示负数。
        if (request.getMinStockQuantity() != null && request.getMinStockQuantity().signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "安全库存不能小于0");
        }
        // 单位不传可以沿用默认或原值；明确传入空白字符串则属于无效输入。
        if (request.getUnit() != null && !StringUtils.hasText(request.getUnit())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "计量单位不能是空白字符");
        }
    }

    /** 只复制允许修改的目录字段；可空文本字段传空会清空，默认字段不传则保留。 */
    private void copyCatalogFields(SparePartUpsertDto request, SparePartEntity part) {
        // 必填编号、名称统一去掉首尾空白；可空文本字段按请求值保存。
        part.setPartCode(request.getPartCode().trim());
        part.setName(request.getName().trim());
        part.setSpec(request.getSpec());
        part.setCategory(request.getCategory());
        part.setLocation(request.getLocation());
        // 这三个字段不传时保留对象原值；新增对象已有默认值，修改对象来自数据库。
        if (request.getMinStockQuantity() != null) part.setMinStockQuantity(request.getMinStockQuantity());
        if (request.getUnit() != null) part.setUnit(request.getUnit().trim());
        if (request.getEnabled() != null) part.setEnabled(request.getEnabled());
    }

    /** 企业内编号唯一；不同企业可使用相同编号。 */
    private void ensureUniqueCode(Long tenantId, String code, Long excludedId) {
        // selectCount 查询符合条件的记录数。新增时不排除任何记录，修改时用 ne 排除自身。
        Long count = sparePartMapper.selectCount(new LambdaQueryWrapper<SparePartEntity>()
                .eq(SparePartEntity::getTenantId, tenantId).eq(SparePartEntity::getPartCode, code)
                .ne(excludedId != null, SparePartEntity::getId, excludedId));
        // 找到其他相同编号说明冲突；这个预检查不能替代数据库的最终唯一约束。
        if (count > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "当前企业的备件编号已存在");
    }

    /** 写操作可选择锁定读取；不存在与其他企业的数据均返回404。 */
    private SparePartEntity requirePart(Long id, Long tenantId, boolean lock) {
        // 1. ID 必须有效；不存在或其他企业的备件采用同一个404提示。
        if (id == null || id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "备件 ID 无效");
        // 2. tenantId 和 id 同时限定，防止其他企业的 ID 被用于详情或修改。
        LambdaQueryWrapper<SparePartEntity> wrapper = new LambdaQueryWrapper<SparePartEntity>()
                .eq(SparePartEntity::getTenantId, tenantId).eq(SparePartEntity::getId, id);
        // 3. 仅写入场景加行锁；依赖外层事务，事务结束后数据库释放锁。
        if (lock) wrapper.last("FOR UPDATE");
        // 4. 执行查询；未找到则抛异常，不继续后续写入。
        SparePartEntity part = sparePartMapper.selectOne(wrapper);
        if (part == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "备件不存在");
        return part;
    }

    /** 转换成页面返回对象，保留数据库生成的时间字段。 */
    private SparePartVO toVO(SparePartEntity part) {
        // 为当前记录创建返回对象；只复制 VO 中存在的同名、兼容类型字段。
        SparePartVO vo = new SparePartVO();
        BeanUtils.copyProperties(part, vo);
        return vo;
    }
}
