package com.tenant.service.impl;

import com.auth.model.AuthSessionAttributes;
import com.auth.service.CurrentAccessService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipops.domain.dto.tenant.TenantMemberCreateDto;
import com.equipops.domain.dto.tenant.TenantMemberRolesUpdateDto;
import com.equipops.domain.dto.tenant.TenantMemberStatusUpdateDto;
import com.equipops.domain.entity.*;
import com.equipops.domain.vo.RoleVO;
import com.equipops.domain.vo.TenantMemberVO;
import com.tenant.mapper.RoleMapper;
import com.tenant.mapper.TenantMapper;
import com.tenant.mapper.TenantMemberRoleMapper;
import com.tenant.mapper.TenantMembershipMapper;
import com.tenant.service.TenantMemberService;
import com.workorder.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
@RequiredArgsConstructor
public class TenantMemberServiceImpl implements TenantMemberService {
    private final com.audit.service.AuditEventService auditEventService;
    private final CurrentAccessService currentAccessService;//检查当前登录用户、当前企业和权限
    private final TenantMembershipMapper membershipMapper;//成员关系表,查询成员、修改成员状态,管理员是由成员关系、角色关联和角色共同确定的。
    private final UserMapper userMapper;//操作用户表 user,根据用户 ID 查询账号资料
    private final RoleMapper roleMapper;//操作角色表 roles,查询角色是否存在
    /**
     * 企业行用于串行处理同一企业的角色变更。
     */
    private final TenantMapper tenantMapper;//操作企业表 tenants,查询并锁定当前企业
    /**
     * 保存企业成员与角色的关联。
     */
    private final TenantMemberRoleMapper memberRoleMapper;
    /**
     * 明确允许授予的企业角色编码；默认允许企业管理员和工单验收人。
     */
    @Value("${equipops.tenant.grantable-role-codes:TENANT_ADMIN,ACCEPTOR}")
    private String grantableRoleCodes;

    //企业成员分页查询
    @Override
    @Transactional(readOnly = true)
    public IPage<TenantMemberVO> getMemberPage(long current, long size, String keyword, Boolean enabled) {
        // 1. 验证当前用户是企业管理员，企业 ID 从登录上下文取得。
        Long tenantId = currentAccessService.requireTenantAdminTenantId();

        // 2. 校验页码和每页数量，保持与用户分页相同的规则。
        if (current < 1) {
            current = 1;
        }
        if (size < 1) {
            size = 1;
        } else if (size > 100) {
            size = 100;
        }
        keyword = StringUtils.hasText(keyword) ? keyword.trim() : null;

        // 3. 查询当前企业的成员，关联用户表取得账号和姓名。
        // enabled 筛选的是成员关系状态，不是全局账号状态。
        IPage<TenantMemberVO> page = membershipMapper.selectMemberPage(
                new Page<>(current, size), tenantId, keyword, enabled);
        if (page.getRecords().isEmpty()) {
            return page;
        }

        // 4. 一次查询本页成员的角色，避免角色多时重复计数或逐人查询。
        List<Long> membershipIds = page.getRecords().stream()
                .map(TenantMemberVO::getId).toList();
        List<TenantMemberVO> memberRoles = membershipMapper.selectMemberRoles(tenantId, membershipIds);
        Map<Long, List<RoleVO>> rolesByMemberId = new HashMap<>();
        for (TenantMemberVO member : memberRoles) {
            rolesByMemberId.put(member.getId(), member.getRoles());
        }

        // 5. 按成员关系 ID 回填角色；没有角色时返回空列表，保留原有分页信息。
        for (TenantMemberVO member : page.getRecords()) {
            member.setRoles(rolesByMemberId.getOrDefault(member.getId(), List.of()));
        }
        return page;
    }

    /**
     *
     * 企业范围、角色授予规则，以及多表写入的事务一致性。
     * tenant_memberships：建立成员关系
     * tenant_member_roles：赋予该成员初始角色
     * 操作者必须是当前企业管理员。
     * 被加入的用户必须存在且启用。
     * 同一个用户在同一企业只能有一条成员关系。
     * 已停用的成员再次加入时，提示通过启用接口恢复，不重复创建。
     * 角色必须存在，而且属于允许授予的企业角色范围；不能只判断角色 ID 存在。
     */
    /**
     * 添加企业人员
     *
     * @param request
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TenantMemberVO addMember(TenantMemberCreateDto request) {
        //1. 调用 requireTenantAdminTenantId(),得到可信的当前企业 ID
        Long tenantId = currentAccessService.requireTenantAdminTenantId();
        //2. 校验参数,userId 有效,roleIds 不为 null，元素均有效
        Long userId = request.getUserId();
        Set<Long> roleId = request.getRoleIds();
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户 ID 无效");
        }
        if (roleId == null && request.getRoleIds() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色 ID 无效");
        }

        //3. 根据 userId 查询 users,不存在 → 返回错误账号,已停用 → 拒绝加入
        UserEntity user = userMapper.selectById(userId);
        //用户不存在，不能建立成员关系。
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        }
        //全局账号停用时，不允许加入企业。
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该用户账号已停用");
        }
        //4. 查询 tenant_memberships租户成员关系表,条件：tenant_id = 当前企业、user_id = 目标用户
        //   已存在且启用 → 提示“该用户已是企业成员”
        //   已存在但停用 → 提示“请恢复已有成员关系”
        //SELECT * FROM tenant_memberships WHERE tenant_id = 当前企业ID AND user_id = 当前用户ID;
        TenantMembershipEntity exiting = membershipMapper.selectOne(
                new LambdaQueryWrapper<TenantMembershipEntity>()
                        .eq(TenantMembershipEntity::getTenantId, tenantId)
                        .eq(TenantMembershipEntity::getUserId, userId)
        );
        if (exiting != null) {
            if (Boolean.TRUE.equals(exiting.getEnabled())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "该用户已是企业成员");
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该成员已停用，请恢复已有成员关系");
        }
        //5. 批量查询请求中的角色,有不存在的角色 → 拒绝,有不允许授予的角色 → 拒绝roleIds 为空时，跳过角色查询
        Set<Long> roleIds = request.getRoleIds();
        if (roleIds == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色不能为空");
        }
        // 一次查询所有请求角色
        List<RoleEntity> roleEntity = List.of();
        if (!roleIds.isEmpty()) {
            roleEntity = roleMapper.selectList(
                    new LambdaQueryWrapper<RoleEntity>()
                            .in(RoleEntity::getId, roleIds)//前端可能会发出多个请求查询的角色roleIds，所以用list集合,指定查询条件使用角色的 id 列
            );
            //数量少于请求数量说明有角色不存在。
            if (roleIds.size() != roleEntity.size()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "包含不存在的角色");
            }
        }
        // 5.1 新成员与角色更新使用相同的授予白名单，不允许借添加成员授予其他角色。
        List<String> allowed = Arrays.stream(grantableRoleCodes.split(",")).map(String::trim).toList();
        for (RoleEntity role : roleEntity) {
            // 每个角色均需在当前企业允许授予集合中。
            if (!allowed.contains(role.getRoleCode())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该角色不允许授予");
        }
        //6. 创建 TenantMembershipEntity,tenantId = 服务器取得的当前企业 ID,userId = 请求中的目标用户 ID,enabled = true
        TenantMembershipEntity membership = new TenantMembershipEntity();
        membership.setTenantId(tenantId);
        membership.setUserId(userId);
        membership.setEnabled(Boolean.TRUE);
        //7. 插入成员关系，检查影响行数,取得生成的 membershipId,
        int number = membershipMapper.insert(membership);
        if (number != 1) {//MyBatis-Plus 的 BaseMapper.insert(entity) 返回的是 int，表示"这条 SQL 影响了几行"
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "添加成员失败");
        }
        Long membershipId = membership.getId();
        //8. 为每个角色创建 TenantMemberRoleEntity,tenantId = 当前企业 ID, membershipId = 刚生成的成员关系 ID
        //   roleId = 已校验的角色 ID

        for (RoleEntity role : roleEntity) {
            // 每次循环创建一个新对象，表示一条关联记录。
            TenantMemberRoleEntity memberRole = new TenantMemberRoleEntity();
            memberRole.setTenantId(tenantId);
            memberRole.setMembershipId(membershipId);
            memberRole.setRoleId(role.getId());
            //9. 保存角色关联，检查写入结果,保存一条关联，必须成功影响一行。
            if (memberRoleMapper.insert(memberRole) != 1) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "分配角色失败");
            }
        }
        //10. 查询该成员信息及角色，组装 TenantMemberVO
        // 读取刚新增的成员，取得数据库生成的时间字段。
        TenantMembershipEntity saved = membershipMapper.selectById(membershipId);
        TenantMemberVO result = new TenantMemberVO();
        BeanUtils.copyProperties(saved, result);
        // 复用前面查到的用户资料。
        result.setAccount(user.getAccount());
        result.setDisplayName(user.getDisplayName());
        // 复用前面查到的角色，转换为返回对象。
        result.setRoles(roleEntity.stream().map(role -> {
            RoleVO vo = new RoleVO();
            BeanUtils.copyProperties(role, vo);
            return vo;
        }).toList());
        // 11. 成功事件必须与成员及角色写入同事务，审计失败令本次新增整体回滚。
        auditEventService.appendSuccess(memberAudit(tenantId, com.equipops.domain.enums.AuditAction.MEMBER_ADDED,
                membershipId, Map.of("userId", userId, "enabled", true, "roleCodes", roleEntity.stream().map(RoleEntity::getRoleCode).sorted().toList())));
        return result;
    }

    /** 修改企业成员角色；路径 ID 是成员关系 ID。  */
    /**
     * 确认操作者是当前企业管理员
     * ↓
     * 检查参数是否合法
     * ↓
     * 锁定企业，再找到并锁定目标成员
     * ↓
     * 查出前端指定的角色，确认都存在
     * ↓
     * 检查这些角色是否允许授予
     * 检查是否会移除最后一名有效管理员
     * ↓
     * 删除目标成员原有的角色关联
     * ↓
     * 插入本次提交的新角色关联
     * ↓
     * 组装 VO，返回修改结果
     *
     * @param membershipId
     * @param request
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TenantMemberVO updateMemberRoles(Long membershipId, TenantMemberRolesUpdateDto request) {
        //1. 验证当前企业管理员，取得 tenantId
        Long tenantId = currentAccessService.requireTenantAdminTenantId();
        //2. 校验参数,membershipId 必须有效,request、roleIds 不能为 null
        if (membershipId == null || membershipId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "成员关系 ID 无效");
        }
        if (request == null || request.getRoleIds() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色集合不能为空");
        }
        // 先锁企业，再锁成员：同一企业的角色变更依次执行，避免同时撤销最后的管理员。
        TenantEntity tenant = tenantMapper.selectOne(new LambdaQueryWrapper<TenantEntity>()
                .eq(TenantEntity::getId, tenantId)
                .last("FOR UPDATE"));
        if (tenant == null || !Boolean.TRUE.equals(tenant.getEnabled())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前企业不可用");
        }
        //3. 查询并锁定目标成员,条件：当前企业 ID + 成员关系 ID,查不到 → 返回 404
        //SELECT *FROM tenant_membershipsWHERE tenant_id = ?AND id = ?FOR UPDATE;
        Set<Long> roleIds = request.getRoleIds();
        TenantMembershipEntity membership = membershipMapper.selectOne(
                new LambdaQueryWrapper<TenantMembershipEntity>()
                        .eq(TenantMembershipEntity::getTenantId, tenantId)//必须是当前企业的成员
                        .eq(TenantMembershipEntity::getId, membershipId)//找到这次要修改的成员关系。
                        .last("FOR UPDATE")//只查询当前企业的目标成员，并锁定该成员行。
        );
        if (membership == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "成员不存在");
        }
        //4. 批量查询并校验新角色,检查角色是否存在、是否允许授予,空集合表示清除角色，但仍须满足业务限制
        List<RoleEntity> newRoles = List.of();
        if (!roleIds.isEmpty()) {
            // 检查每个角色 ID 是否有效。
            for (Long roleId : roleIds) {
                if (roleId == null || roleId <= 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色 ID 无效");
                }
            }
            // 一次查询所有指定角色。
            newRoles = roleMapper.selectList(
                    new LambdaQueryWrapper<RoleEntity>()
                            .in(RoleEntity::getId, roleIds)
            );
            // roleIds 是去重后的 Set，数量不一致说明有角色不存在。
            if (newRoles.size() != roleIds.size()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "包含不存在的角色");
            }
        }
        //5. 检查变更限制：只授予明确配置的企业角色，不把全局角色直接当作企业角色。
        List<String> allowedCodes = Arrays.stream(grantableRoleCodes.split(","))
                .map(String::trim).filter(StringUtils::hasText).toList();
        for (RoleEntity role : newRoles) {//newRoles:目标成员设置的角色列表
            if (!allowedCodes.contains(role.getRoleCode())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该角色尚未配置为可授予的企业角色："
                        + role.getRoleCode());//逐个检查新角色的编码是否在允许范围内。
            }
        }
        // 使用锁定读取得最新有效管理员；普通查询可能读取事务之前的快照。
        // 有效管理员要求：用户启用、成员启用，且具有 TENANT_ADMIN 角色。
        List<Long> adminUserIds = membershipMapper.selectEnabledAdminUser(
                tenantId, AuthSessionAttributes.TENANT_ADMIN_ROLE);//管理员角色编码，值为 "TENANT_ADMIN",tenantId：当前企业 ID
        //获取当前操作者的用户 ID
        Long operatorUserId = currentAccessService.requireEnabledUserId();
        // 等待企业锁期间，自己的管理员权限可能已被其他请求撤销，必须再次确认。
        if (!adminUserIds.contains(operatorUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前企业管理员权限已失效");
        }
        //防止把企业最后一名有效管理员的管理员角色取消。
        boolean keepsAdminRole = newRoles.stream().anyMatch(role ->
                AuthSessionAttributes.TENANT_ADMIN_ROLE.equals(role.getRoleCode()));
        if (adminUserIds.contains(membership.getUserId())
                && adminUserIds.size() == 1 && !keepsAdminRole) {//keepsAdminRole 表示修改后是否保留管理员角色
            throw new ResponseStatusException(HttpStatus.CONFLICT, "不能移除企业最后一名有效管理员");
        }

        // 5.1 使用锁定读取得最新角色关联，避免等待企业锁前的RR快照污染审计前值。
        List<TenantMemberRoleEntity> beforeRelations = memberRoleMapper.selectList(new LambdaQueryWrapper<TenantMemberRoleEntity>()
                .eq(TenantMemberRoleEntity::getTenantId, tenantId)
                .eq(TenantMemberRoleEntity::getMembershipId, membershipId)
                .last("FOR UPDATE"));
        // 角色编码来自服务端角色目录，不来自请求正文。
        List<Long> beforeIds = beforeRelations.stream().map(TenantMemberRoleEntity::getRoleId).toList();
        // 空角色不生成无条件查询，非空集合一次读取编码并稳定排序。
        List<String> beforeCodes = beforeIds.isEmpty() ? List.of() : roleMapper.selectList(new LambdaQueryWrapper<RoleEntity>()
                .in(RoleEntity::getId, beforeIds)).stream().map(RoleEntity::getRoleCode).sorted().toList();
        //6. 删除该成员的旧角色关联；两个条件一起限定，不能删到其他企业或成员。
        memberRoleMapper.delete(new LambdaQueryWrapper<TenantMemberRoleEntity>()
                .eq(TenantMemberRoleEntity::getTenantId, tenantId)
                .eq(TenantMemberRoleEntity::getMembershipId, membershipId));
        // 原本没有角色时，删除数量为 0 是正常情况。

        //7. 逐条插入新关联；每次创建新对象，避免复用上次生成的主键。
        for (RoleEntity role : newRoles) {
            TenantMemberRoleEntity memberRole = new TenantMemberRoleEntity();
            memberRole.setTenantId(tenantId);
            memberRole.setMembershipId(membershipId);
            memberRole.setRoleId(role.getId());
            if (memberRoleMapper.insert(memberRole) != 1) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "成员角色保存失败");
            }
        }
        // 空集合不进入循环，表示清除该成员角色；最后一名管理员已在第 5 步保护。
        //8. 组装返回对象：成员资料来自 membership，账号资料来自 users，角色来自 newRoles。
        UserEntity user = userMapper.selectById(membership.getUserId());
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "成员关联的用户不存在");
        }
        TenantMemberVO result = new TenantMemberVO();
        BeanUtils.copyProperties(membership, result);
        result.setAccount(user.getAccount());
        result.setDisplayName(user.getDisplayName());

        result.setRoles(newRoles.stream().map(role -> {
            RoleVO vo = new RoleVO();
            BeanUtils.copyProperties(role, vo);
            return vo;
        }).toList());
        // 任一步抛出异常，事务会回滚删除和新增，恢复原来的角色关联。
        // 9. 仅实际角色集合变化才生成成功事件，原事务中的审计异常不能被吞掉。
        List<String> afterCodes = newRoles.stream().map(RoleEntity::getRoleCode).sorted().toList();
        if (!beforeCodes.equals(afterCodes)) {
            // 固定字段与已校验角色编码，禁止把客户端原始正文作为摘要。
            auditEventService.appendSuccess(memberAudit(tenantId, com.equipops.domain.enums.AuditAction.MEMBER_ROLES_CHANGED,
                    membershipId, Map.of("field", "roleCodes", "before", beforeCodes, "after", afterCodes)));
        }
        return result;
    }

    /**
     * 启用／停用企业成员：
     *tenantId	当前企业 ID
     * membershipId	被修改的成员关系 ID
     * Teme	数据库查出的目标成员关系对象
     * request	前端提交的数据
     * enabled	本次希望设置的启停状态
     * adminUser	有效管理员的用户 ID 列表
     * operatorUserId	当前操作者的用户 ID
     * result	最后返回给前端的数据
     * @param membershipId 管理员是由成员关系、角色关联和角色共同确定的。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TenantMemberVO updateMemberStatus(Long membershipId, TenantMemberStatusUpdateDto request) {
        // 1. 验证管理员，取得当前企业 ID。
        Long tenantId = currentAccessService.requireTenantAdminTenantId();
        // 2. 校验成员关系 ID、请求对象和 enabled。
        if (membershipId == null || membershipId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "成员关系 ID 无效");
        }
        //判断前端请求对象是否有效
        if (request == null || request.getEnabled() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "成员状态不能为空");
        }
        // 3. 先锁企业，再查询并锁定当前企业的目标成员。查不到成员 → 返回 404。
        TenantEntity tenant = tenantMapper.selectOne(
                new LambdaQueryWrapper<TenantEntity>()
                        .eq(TenantEntity::getId, tenantId)
                        .last("FOR UPDATE"));

        if (tenant == null || !Boolean.TRUE.equals(tenant.getEnabled())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "当前企业不可用");
        }
        //拿到成员关系Teme
        TenantMembershipEntity Teme = membershipMapper.selectOne(new LambdaQueryWrapper<TenantMembershipEntity>()
                .eq(TenantMembershipEntity::getId, membershipId)
                .eq(TenantMembershipEntity::getTenantId, tenantId)
                .last("FOR UPDATE"));
        if (Teme == null ) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "查不到成员");
        }
        // 4. 查询最新有效管理员，确认操作者仍有管理员权限。
        List<Long> adminUser = membershipMapper.selectEnabledAdminUser(tenantId, AuthSessionAttributes.TENANT_ADMIN_ROLE);//传入公司ID，和角色编码值
        //获取企业已启用的用户的ID，判断是否有管理员权限
        if (!adminUser.contains(currentAccessService.requireEnabledUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "操作者没有管理员权限");
        }
        // 5. 如果 request.enabled 是 false：目标成员是有效管理员，且管理员只有一名 → 拒绝停用。
        if (!request.getEnabled()) {
            if (adminUser.contains(Teme.getUserId()) && adminUser.size() == 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "不能停用企业最后一名有效管理员");
            }
        }
        // 6. 比较原状态和目标状态。一样 → 不重复更新，直接组装返回结果。
        // 7. 设置 membership.enabled，执行更新并检查结果。
        Boolean beforeEnabled = Teme.getEnabled();
        Boolean enabled = request.getEnabled();
        if (!enabled.equals(Teme.getEnabled())) {
            //设置成员状态。
            Teme.setEnabled(enabled);
            if (membershipMapper.updateById(Teme) != 1) {//预期只修改一条记录
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "成员状态修改失败");
            }
        }
        // 8. 查询用户资料、成员角色，组装 TenantMemberVO 返回。
        //查询用户资料,企业的目标成员。
        UserEntity user = userMapper.selectById(Teme.getUserId());
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "成员关联的用户不存在");
        }
        //组装成员和用户信息
        TenantMemberVO result = new TenantMemberVO();
        // 复制成员关系 ID、企业 ID、用户 ID、enabled 等同名字段。
        // 重新查询，取得数据库更新后的时间字段。
        TenantMembershipEntity saved = membershipMapper.selectById(membershipId);
        if (saved == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "成员不存在");
        }
        // 复制成员关系资料。
        BeanUtils.copyProperties(saved, result);
        // 补上前面已经查到的用户资料。
        result.setAccount(user.getAccount());
        result.setDisplayName(user.getDisplayName());
        // 补上成员的角色列表。
        List<TenantMemberVO> memberRoles =
                membershipMapper.selectMemberRoles(
                        tenantId, List.of(membershipId));
        result.setRoles(List.of());
        if (!memberRoles.isEmpty()) {
            result.setRoles(memberRoles.get(0).getRoles());
        }
        // 9. 相同状态的重复请求不重复记成功；有效状态变化与审计保持同事务。
        if (!enabled.equals(beforeEnabled)) {
            // 只记录布尔前后值，不记录账号或请求正文。
            auditEventService.appendSuccess(memberAudit(tenantId, com.equipops.domain.enums.AuditAction.MEMBER_STATUS_CHANGED,
                    membershipId, Map.of("field", "enabled", "before", beforeEnabled, "after", enabled)));
        }
        return result;
    }
    private com.equipops.domain.dto.audit.AuditAppendCommand memberAudit(Long tenantId,
            com.equipops.domain.enums.AuditAction action, Long objectId, Map<String, Object> changes) {
        // 从服务端上下文取得操作者，失败时让业务事务回滚，不能相信请求中的身份。
        return new com.equipops.domain.dto.audit.AuditAppendCommand(tenantId, currentAccessService.requireEnabledUserId(),
                action, com.equipops.domain.enums.AuditObjectType.MEMBER, objectId, changes, null, null);
    }
}
