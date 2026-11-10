package com.equipops.domain.vo;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 角色。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoleVO {

    /** 角色 ID */
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
    private Long id;

    /** 角色编码 */
    private String roleCode;

    /** 角色名称 */
    private String name;

    /** 描述 */
    private String description;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
