package com.config; // 放在你的配置包下

import com.baomidou.mybatisplus.annotation.DbType;
import com.auth.scope.SiteDataPermissionHandler;
import com.auth.service.CurrentAccessService;
import org.springframework.beans.factory.ObjectProvider;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(ObjectProvider<CurrentAccessService> access) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 厂区权限先于分页与COUNT，防止总数暴露未授权数据。
        interceptor.addInnerInterceptor(new DataPermissionInterceptor(new SiteDataPermissionHandler(access::getObject)));
        // 添加分页插件，并指定数据库类型（MySQL为例）
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}