package com.tenant.service.impl;

import com.auth.service.CurrentAccessService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.equipops.domain.entity.TenantEntity;
import com.equipops.domain.entity.TenantMembershipEntity;
import com.equipops.domain.vo.TenantVO;
import com.tenant.mapper.TenantMapper;
import com.tenant.mapper.TenantMembershipMapper;
import com.tenant.service.TenantService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class TenantServiceImpl implements TenantService {

    @Autowired
    private CurrentAccessService currentAccessService;
    @Autowired
    private TenantMembershipMapper tenantMembershipMapper;
    @Autowired
    private TenantMapper tenantMapper;

    //TODO
    @Override
    public List<TenantVO> getMyTenants() {
        /**
         * 方法：getMyTenants()
         *
         * 1. 调用 requireEnabledUserId()
         *    获取当前已登录且启用的用户 ID
         *    未登录或账号失效 → 结束并返回错误
         */
        Long userId = currentAccessService.requireEnabledUserId();
        /*
         * 2. 查询 tenant_memberships
         *    条件：
         *      user_id = 当前用户 ID
         *      enabled = true
         */
        LambdaQueryWrapper<TenantMembershipEntity> membershipWrapper = new LambdaQueryWrapper<>();
        membershipWrapper.eq(TenantMembershipEntity::getUserId, userId).eq(TenantMembershipEntity::getEnabled, true);
        List<TenantMembershipEntity> memberships = tenantMembershipMapper.selectList(membershipWrapper);
        /*
         * 3. 判断成员关系列表是否为空
         *    为空 → 返回空列表，表示尚未加入企业
         */
        if (memberships.isEmpty()){
            return List.of();
        }
        /*
         * 4. 从成员关系中提取 tenant_id
         *    去重，得到企业 ID 集合
         */
        //Set 的特性是元素不重复，所以重复的 tenantId 只会保留一个
        //LinkedHashSet 在去重的同时保留插入顺序
        Set<Long> tenantIds = new LinkedHashSet<>();
        //遍历集合 `memberships` 里每一个租户成员对象 `membership`；
        //如果该对象的 `tenantId`（租户 ID）不为空，就把这个租户 ID 添加到 `tenantIds` 集合中。
        for (TenantMembershipEntity membership : memberships){
            //防御性判断。数据库有外键约束时一般不会为 null，但多一层判断成本极低，避免把 null 塞进 SQL 的 IN (null, ...)。
            if (membership.getTenantId() != null){
                tenantIds.add(membership.getTenantId());
            }
        }
        //判断memberships非空
        if (tenantIds.isEmpty()){
            return List.of();
        }
        /*
         * 5. 批量查询 tenants
         *    条件：
         *      id 在企业 ID 集合中
         *      enabled = true
         *    按 ID 排序
         */
        LambdaQueryWrapper<TenantEntity> tenantWrapper = new LambdaQueryWrapper<>();
        tenantWrapper
                .in(TenantEntity::getId, tenantIds)
                .eq(TenantEntity::getEnabled, true)
                .orderByAsc(TenantEntity::getId);
        List<TenantEntity> tenants = tenantMapper.selectList(tenantWrapper);
        /*
         * 6. 将每个 TenantEntity 转换为 TenantVO
         */
        List<TenantVO> result = new ArrayList<>();
        for (TenantEntity tenant : tenants){
            TenantVO vo = new TenantVO();
            //读取源对象，在目标对象里找同名通类型的的属性，调用目标setter把值写进去
            BeanUtils.copyProperties(tenant, vo);
            result.add(vo);
        }
        /*
         * 7. 返回企业列表
         */
        return result;

    }
}
