package com.equipops.domain.dto.user;

import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 分配用户角色请求。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserRoleAssignDto {

    /** 角色 ID 集合 */
    private Set<Long> roleIds;
}
