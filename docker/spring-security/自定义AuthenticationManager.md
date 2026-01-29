# Spring Security 自定义 AuthenticationManager Bean 配置示例
本文整理 Spring Security 中**自定义 AuthenticationManager Bean** 的核心配置示例，包含**发布 AuthenticationManager 用于自定义身份验证**、**配置全局 AuthenticationManagerBuilder**、**配置 Spring Security 本地 AuthenticationManager** 三种核心场景，均为可直接复用的 Java 代码实现。

## 一、发布 AuthenticationManager Bean 用于自定义身份验证
适用于自定义身份验证场景（如 REST API 登录认证），将 `AuthenticationManager` 作为 Bean 发布，可在 `@Service`/`@RestController` 中注入使用。

### 1. 核心配置类 `SecurityConfig`
```java
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.authentication.ProviderManager;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests((authorize) -> authorize
                        .requestMatchers("/login").permitAll() // 登录接口放行
                        .anyRequest().authenticated() // 其他接口需认证
                );

        return http.build();
    }

    /**
     * 发布 AuthenticationManager Bean，供自定义认证使用
     */
    @Bean
    public AuthenticationManager authenticationManager(
            UserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) {
        // 配置 Dao 认证提供者，基于用户名/密码认证
        DaoAuthenticationProvider authenticationProvider = new DaoAuthenticationProvider();
        authenticationProvider.setUserDetailsService(userDetailsService);
        authenticationProvider.setPasswordEncoder(passwordEncoder);

        // 构建并返回认证管理器
        return new ProviderManager(authenticationProvider);
    }

    /**
     * 内存级用户详情服务，测试用
     */
    @Bean
    public UserDetailsService userDetailsService() {
        UserDetails userDetails = User.withDefaultPasswordEncoder()
                .username("user")
                .password("password")
                .roles("USER")
                .build();

        return new InMemoryUserDetailsManager(userDetails);
    }

    /**
     * 密码编码器，使用 Spring 内置的委托式编码器（支持多种加密方式）
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
```

### 2. 配套登录控制器 `LoginController`
在 REST 接口中注入上述 `AuthenticationManager`，实现自定义用户名/密码认证：
```java
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LoginController {

    // 注入自定义的 AuthenticationManager
    private final AuthenticationManager authenticationManager;

    public LoginController(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    /**
     * 自定义登录接口
     */
    @PostMapping("/login")
    public ResponseEntity<Void> login(@RequestBody LoginRequest loginRequest) {
        // 构建未认证的身份令牌
        UsernamePasswordAuthenticationToken authenticationRequest =
                UsernamePasswordAuthenticationToken.unauthenticated(loginRequest.username(), loginRequest.password());
        // 执行认证（认证失败会抛出异常，认证成功返回已认证的 Authentication）
        Authentication authenticationResponse =
                this.authenticationManager.authenticate(authenticationRequest);
        // 后续可将认证结果存入 SecurityContextRepository（如 HttpSession）
        // ...

        return ResponseEntity.ok().build();
    }

    /**
     * 登录请求参数实体
     */
    public record LoginRequest(String username, String password) {
    }
}
```

## 二、配置全局 AuthenticationManagerBuilder
适用于全局修改 Spring Security 内置 `AuthenticationManager` 的行为（如**禁用凭据擦除**），通过 `@Autowired` 注入 `AuthenticationManagerBuilder` 进行全局配置。

```java
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // 自定义接口权限规则，示例为所有接口需认证
        http
                .authorizeHttpRequests((authorize) -> authorize
                        .anyRequest().authenticated()
                )
                .httpBasic(Customizer.withDefaults()) // 启用HTTP基本认证
                .formLogin(Customizer.withDefaults()); // 启用表单登录

        return http.build();
    }

    /**
     * 自定义用户详情服务（示例为缓存用户的实现，需自行完善）
     */
    @Bean
    public UserDetailsService userDetailsService() {
        // Return a UserDetailsService that caches users
        // ... 业务实现代码
    }

    /**
     * 配置全局 AuthenticationManagerBuilder
     * 示例：禁用凭据擦除（适用于缓存用户场景，避免重复加载用户信息）
     */
    @Autowired
    public void configure(AuthenticationManagerBuilder builder) {
        builder.eraseCredentials(false);
    }
}
```

## 三、配置 Spring Security 本地 AuthenticationManager
适用于**覆盖全局 AuthenticationManager**，为当前 Security 过滤器链定制专属的认证管理器，可精细化配置 `DaoAuthenticationProvider` 和 `ProviderManager` 的行为。

```java
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.Customizer;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests((authorize) -> authorize
                        .anyRequest().authenticated()
                )
                .httpBasic(Customizer.withDefaults())
                .formLogin(Customizer.withDefaults())
                .authenticationManager(authenticationManager()); // 绑定本地认证管理器

        return http.build();
    }

    /**
     * 定义本地 AuthenticationManager，覆盖全局配置
     */
    private AuthenticationManager authenticationManager() {
        // 配置 Dao 认证提供者
        DaoAuthenticationProvider authenticationProvider = new DaoAuthenticationProvider();
        authenticationProvider.setUserDetailsService(userDetailsService());
        authenticationProvider.setPasswordEncoder(passwordEncoder());

        // 构建认证管理器并自定义行为
        ProviderManager providerManager = new ProviderManager(authenticationProvider);
        // 禁用认证后擦除凭据，适用于缓存用户场景
        providerManager.setEraseCredentialsAfterAuthentication(false);

        return providerManager;
    }

    /**
     * 内存级用户详情服务（测试用）
     */
    private UserDetailsService userDetailsService() {
        UserDetails userDetails = User.withDefaultPasswordEncoder()
                .username("user")
                .password("password")
                .roles("USER")
                .build();

        return new InMemoryUserDetailsManager(userDetails);
    }

    /**
     * 密码编码器
     */
    private PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
```

### 关键说明
1. 本地 `AuthenticationManager` 为**私有方法**，无需标注 `@Bean`，仅为当前 `SecurityFilterChain` 提供服务；
2. 核心自定义点：`setEraseCredentialsAfterAuthentication(false)` 禁用凭据擦除，避免缓存用户的凭据被清除导致重复查询；
3. 需手动为 `DaoAuthenticationProvider` 绑定 `UserDetailsService` 和 `PasswordEncoder`，完成用户名/密码认证的核心配置。