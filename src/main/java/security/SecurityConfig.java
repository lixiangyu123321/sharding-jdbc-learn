package security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    /**
     * 过滤链配置
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 认证所有请求
                .authorizeHttpRequests((authorize) -> authorize
                        .anyRequest().authenticated()
                )
                // 启用HTTP Basic和表单登录
                // HTTP Basic认证
                .httpBasic(Customizer.withDefaults())
                // Customizer.withDefaults()表示使用默认配置
                .formLogin(Customizer.withDefaults());

        return http.build();
    }

//    /**
//     * 使用默认的密码编码器配置内存用户
//     * @return
//     */
//    @Bean
//    public UserDetailsService userDetailsService() {
//        // 配置用户信息
//        // XXX Spring Security禁止明文存储密码（强制安全规范），如果密码不加密，启动项目会直接报错。
//        // withDefaultPasswordEncoder()方法用于创建一个使用默认密码编码器的用户，自动对密码进行编码
//        UserDetails userDetails = User.withDefaultPasswordEncoder()
//                .username("user")
//                .password("password")
//                .roles("USER")
//                .build();
//
//        // 内存用户管理器
//        return new InMemoryUserDetailsManager(userDetails);
//    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // 推荐使用BCrypt加密算法（Spring Security首选，不可逆、带盐值）
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailService(PasswordEncoder passwordEncoder) {
        UserDetails user = User.builder()
                .username("user") // 用户名
                .password(passwordEncoder.encode("password")) // 手动加密原始密码
                .roles("USER") // 角色（自动拼接为ROLE_USER）
                .build();

        // 扩展：可添加多个测试用户（如管理员）
        UserDetails admin = User.builder()
                .username("admin")
                .password(passwordEncoder.encode("admin123"))
                .roles("ADMIN", "USER") // 多角色
                .build();

        // XXX 可以添加多个用户
        return new InMemoryUserDetailsManager(user, admin);
    }
}
