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

/** 企业租户，使用 MyBatis-Plus 持久化。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("tenants")
public class TenantEntity {
    /** 主键 ID，由数据库生成。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 企业编号，全局唯一。 */
    private String tenantCode;

    /** 企业名称。 */
    private String name;

    /** 企业是否启用。 */
    private Boolean enabled;

    /** 创建时间，由数据库维护。 */
    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    /** 更新时间，由数据库维护。 */
    @TableField(value = "updated_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
