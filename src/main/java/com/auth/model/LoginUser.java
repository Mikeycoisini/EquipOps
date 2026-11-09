package com.auth.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.io.Serial;
import java.util.Collection;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Spring Security 认识的用户资料：UserDetails 是框架要求实现的接口。
 * 它不是数据库 Entity，也不是返回前端的 VO；认证成功后它作为 principal 保存。
 * 企业角色会随所选企业和授权变化，因此不缓存到这个全局账号主体中。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser implements UserDetails, CredentialsContainer {
    /** UserDetails 可被保存在会话中，因此声明序列化版本号。 */
    @Serial
    private static final long serialVersionUID = 1L;

    /** 全局用户 ID。 */
    private Long userId;

    /** 数据库中的规范账号。 */
    private String account;

    /** 仅供认证校验，认证成功后擦除，不允许输出或记录。 */
    @JsonIgnore
    @ToString.Exclude
    private String passwordHash;

    /** 用户显示名称。 */
    private String displayName;

    /** 加载用户时的账号状态；业务请求仍须查询最新状态。 */
    private Boolean enabled;

    /** 框架统一调用 getUsername；业务字段名称仍可保持 account。 */
    @Override
    public String getUsername() {
        return account;
    }

    /** 返回待比对的哈希而非明文；@JsonIgnore 防止它进入 JSON。 */
    @Override
    @JsonIgnore
    public String getPassword() {
        return passwordHash;
    }

    /**
     * 不把企业角色塞进全局登录角色。空集合不代表匿名，登录结果仍是已认证身份。
     * 企业管理员等权限由 CurrentAccessService 读取当前企业成员角色后判断。
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    /** Boolean 可能为 null，TRUE.equals 只有明确为 true 时才启用。 */
    @Override
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    /** 当前数据库没有账号有效期字段，因此暂不启用账号到期机制。 */
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    /** 当前没有独立锁定状态；账号停用由 isEnabled 判断。 */
    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    /** 当前没有密码到期策略，后续有明确需求时再增加字段与规则。 */
    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /** CredentialsContainer 让认证管理器能在认证成功后擦除已不再需要的哈希。 */
    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }
}
