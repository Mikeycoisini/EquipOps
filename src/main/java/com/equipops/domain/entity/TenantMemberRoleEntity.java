package com.equipops.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 企业成员角色关联，使用 MyBatis-Plus 持久化。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("tenant_member_roles")
public class TenantMemberRoleEntity {
    /** 主键 ID，由数据库生成。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 企业 ID，必须与成员关系所属企业一致。 */
    private Long tenantId;

    /** 企业成员关系 ID，不是全局用户 ID。 */
    private Long membershipId;

    /** 角色定义 ID，引用已有 roles 表。 */
    private Long roleId;

    /** 创建时间，由数据库维护。 */
    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    /** 更新时间，由数据库维护。 */
    @TableField(value = "updated_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
