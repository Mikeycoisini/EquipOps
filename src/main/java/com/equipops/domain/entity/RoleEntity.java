package com.equipops.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 角色。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity @Table(name = "roles")
@TableName("roles")
public class RoleEntity {
    /** 角色 ID */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 角色编码 */
    @Column(name = "role_code", nullable = false, length = 40, unique = true)
    private String roleCode;

    /** 角色名称 */
    @Column(nullable = false, length = 80)
    private String name;

    /** 描述 */
    @Column(length = 255)
    private String description;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
