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

/** 企业成员关系，使用 MyBatis-Plus 持久化。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("tenant_memberships")
public class TenantMembershipEntity {
    /** 主键 ID，由数据库生成。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属企业 ID。 */
    private Long tenantId;

    /** 全局用户 ID。 */
    private Long userId;

    /** 用户在该企业内的成员关系是否有效。 */
    private Boolean enabled;

    /** 首次加入时间，由数据库维护。 */
    @TableField(value = "joined_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime joinedAt;

    /** 创建时间，由数据库维护。 */
    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    /** 更新时间，由数据库维护。 */
    @TableField(value = "updated_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
