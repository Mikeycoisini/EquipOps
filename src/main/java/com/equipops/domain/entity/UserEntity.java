package com.equipops.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 用户。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity @Table(name = "users")
@TableName("users")
public class UserEntity {
    /** 用户 ID */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 登录账号 */
    @Column(nullable = false, length = 100, unique = true)
    private String account;

    /** 密码哈希值 */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    /** 用户显示名称 */
    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    /** 电子邮箱 */
    @Column(length = 254, unique = true)
    private String email;

    /** 联系电话 */
    @Column(length = 32)
    private String phone;

    /** 是否启用 */
    @Column(nullable = false)
    private Boolean enabled = true;

    /** 上次登录时间 */
    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
