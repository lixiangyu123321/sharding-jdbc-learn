package org.lix.mycatdemo.security.rememberme;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFilter;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

import javax.annotation.Resource;

@Configuration
public class RememeberMeConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, UsernamePasswordAuthenticationFilter usernamePasswordAuthenticationFilter) throws Exception {
        http
                .csrf(csrf -> csrf
                                .csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                        // 6.1.0版本以上才有该类
                        //.csrfTokenRequestHandler(new XorCsrfTokenRequestAttributeHandler())
                )
                .sessionManagement((session) -> session
                        // 配置会话过期后跳转到登录页，携带原请求地址
                        .invalidSessionUrl("/login?error=session_expired")
                )
                //.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
                .authorizeHttpRequests(authorize -> authorize
                        //.requestMatchers("/login").permitAll()
                        .anyRequest().authenticated()
                )
                .rememberMe(rememberme -> rememberme
                        .rememberMeServices(usernamePasswordAuthenticationFilter.getRememberMeServices()))
                .addFilterAfter(usernamePasswordAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)

                .httpBasic(Customizer.withDefaults())
                .formLogin(Customizer.withDefaults());
        return http.build();
    }


    @Bean
    public UsernamePasswordAuthenticationFilter authenticationFilter(RememberMeServices rememberMeServices) {
        UsernamePasswordAuthenticationFilter usernamePasswordAuthenticationFilter = new UsernamePasswordAuthenticationFilter();
        usernamePasswordAuthenticationFilter.setRememberMeServices(rememberMeServices);
        return usernamePasswordAuthenticationFilter;
    }
}
