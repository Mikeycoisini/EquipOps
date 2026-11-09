package com.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.equipops.domain.entity.TenantMembershipEntity;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipops.domain.vo.TenantMemberVO;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** tenant_memberships 表基础操作；租户查询条件由业务实现明确添加。 */
public interface TenantMembershipMapper extends BaseMapper<TenantMembershipEntity> {
    /** 先分页查询成员，再单独查询本页角色。 */
    IPage<TenantMemberVO> selectMemberPage(Page<TenantMemberVO> page,
                                         @Param("tenantId") Long tenantId,
                                         @Param("keyword") String keyword,
                                         @Param("enabled") Boolean enabled);

    /** 仅返回成员关系 ID 和角色列表，供业务层批量回填。 */
    List<TenantMemberVO> selectMemberRoles(@Param("tenantId") Long tenantId,
                                         @Param("membershipIds") List<Long> membershipIds);
    /** 锁定读取当前企业的有效管理员用户 ID，供角色变更保护及权限复核使用。 */
    List<Long> selectEnabledAdminUser(@Param("tenantId") Long tenantId,
                                                @Param("roleCode") String roleCode);
}
