package com.equipops.domain.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 用户角色关联。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity @Table(name = "user_roles")
public class UserRoleEntity {
    /** 用户角色关联 ID */
    @EmbeddedId
    private UserRoleId id;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
