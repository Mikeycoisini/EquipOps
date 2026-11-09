package com.equipops.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 用户角色关联的复合主键。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Embeddable
public class UserRoleId implements Serializable {
    /** 用户 ID */
    @Column(name = "user_id")
    private Long userId;

    /** 角色 ID */
    @Column(name = "role_id")
    private Long roleId;
}
