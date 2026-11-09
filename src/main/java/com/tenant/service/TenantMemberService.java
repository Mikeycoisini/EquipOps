package com.tenant.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.equipops.domain.dto.tenant.TenantMemberCreateDto;
import com.equipops.domain.dto.tenant.TenantMemberRolesUpdateDto;
import com.equipops.domain.dto.tenant.TenantMemberStatusUpdateDto;
import com.equipops.domain.vo.TenantMemberVO;

/** 企业成员业务契约，所有方法必须验证当前企业及管理员权限。 */
public interface TenantMemberService {
    /** 按当前企业分页，keyword 匹配账号或显示名称，enabled 筛选成员状态。 */
    IPage<TenantMemberVO> getMemberPage(long current, long size, String keyword, Boolean enabled);

    /** 将已有账号加入当前企业；账号与角色校验、成员及角色写入须在同一事务。 */
    TenantMemberVO addMember(TenantMemberCreateDto request);

    /** 修改当前企业成员的角色；防止越权授予，撤权后后续请求必须立即失效。 */
    TenantMemberVO updateMemberRoles(Long membershipId, TenantMemberRolesUpdateDto request);

    /** 启用或撤销成员关系，不修改 users.enabled；撤权后后续请求必须立即失效。 */
    TenantMemberVO updateMemberStatus(Long membershipId, TenantMemberStatusUpdateDto request);
}
