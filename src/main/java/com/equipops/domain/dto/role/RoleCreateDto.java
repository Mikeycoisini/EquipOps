package com.equipops.domain.dto.role;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 创建角色请求。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoleCreateDto {

    /** 角色编码 */
    private String roleCode;

    /** 角色名称 */
    private String name;

    /** 描述 */
    private String description;
}
