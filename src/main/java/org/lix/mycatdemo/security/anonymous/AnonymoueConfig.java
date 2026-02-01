package org.lix.mycatdemo.security.anonymous;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class AnonymoueConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // 公开资源：允许匿名用户（ROLE_ANONYMOUS）访问
                        //.requestMatchers("/", "/login", "/register").permitAll()
                        // 所有其他资源：必须已认证（排除匿名）
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form.permitAll())
                // 自定义匿名认证（可选，默认开启）
                .anonymous(anon -> anon
                                //.username("myAnonymousUser") // 自定义匿名用户名
                                .authorities("ROLE_MY_ANONYMOUS") // 自定义匿名权限
                        // .disable() // 禁用匿名认证（谨慎使用，会导致SecurityContextHolder可能为null）
                );
        return http.build();
    }
}
