你问到的 `AuthenticationManager` 是 Spring Security 认证流程的**核心入口**，而 `ProviderManager` 是它最常用的实现类——我会用「核心作用+实战代码+执行流程」拆解这两个组件的用法，让你明白“认证管理器是如何统筹所有认证方式（表单/OAuth2/自定义）的”。

### 一、先理清核心概念
| 组件 | 核心定位 | 关键作用 |
|------|----------|----------|
| `AuthenticationManager` | 认证流程的**顶层接口** | 定义唯一核心方法 `authenticate(Authentication)`：接收「待认证凭证」，返回「已认证身份信息」（失败则抛异常） |
| `ProviderManager` | `AuthenticationManager` 的**默认实现** | 统筹多个 `AuthenticationProvider`（认证提供者），按顺序尝试认证，实现“多认证方式共存”（如同时支持表单登录、OAuth2、短信登录） |
| `AuthenticationProvider` | 具体认证逻辑的**执行者** | 每个 `Provider` 对应一种认证方式（如 `DaoAuthenticationProvider` 处理表单登录，`OAuth2LoginAuthenticationProvider` 处理OAuth2登录） |

### 二、`AuthenticationManager`/`ProviderManager` 的核心使用场景
#### 场景1：默认用法（框架自动配置，无需手动写）
Spring Security 会自动配置 `ProviderManager` 作为默认的 `AuthenticationManager`，并根据你的依赖/配置自动注册对应的 `AuthenticationProvider`：
- 引入 `spring-boot-starter-security` → 自动注册 `DaoAuthenticationProvider`（处理表单登录/HTTP Basic 认证）；
- 引入 `spring-boot-starter-oauth2-client` → 自动注册 `OAuth2LoginAuthenticationProvider`（处理OAuth2登录）；

此时你无需手动创建 `AuthenticationManager`，框架会在认证过滤器（如 `UsernamePasswordAuthenticationFilter`）中自动使用它。

#### 场景2：手动配置 `AuthenticationManager`（自定义认证规则）
如果需要自定义认证逻辑（比如添加短信验证码认证），可以手动配置 `ProviderManager`，并注册自定义的 `AuthenticationProvider`。

##### 步骤1：定义自定义 `AuthenticationProvider`（短信验证码认证）
```java
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.Collections;

// 自定义AuthenticationProvider：处理短信验证码认证
@Component
public class SmsCodeAuthenticationProvider implements AuthenticationProvider {

    // 核心方法：执行具体的认证逻辑
    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        // 1. 获取待认证的凭证（手机号+短信验证码）
        String phone = authentication.getName(); // 手机号
        String smsCode = (String) authentication.getCredentials(); // 短信验证码

        // 2. 模拟校验短信验证码（实际项目中替换为Redis查询）
        String validCode = "666666"; // 假设正确验证码是666666
        if (!validCode.equals(smsCode)) {
            throw new BadCredentialsException("短信验证码错误");
        }

        // 3. 认证成功：返回已认证的Authentication（isAuthenticated=true）
        return new UsernamePasswordAuthenticationToken(
                phone, // principal：手机号（用户标识）
                null,  // credentials：清空敏感凭证
                // authorities：给用户分配角色/权限
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }

    // 关键：判断当前Provider是否支持该类型的Authentication
    @Override
    public boolean supports(Class<?> authentication) {
        // 支持 UsernamePasswordAuthenticationToken（也可自定义Authentication实现类）
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
```

##### 步骤2：配置 `ProviderManager` 作为 `AuthenticationManager`
```java
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Configuration;

import java.util.Arrays;

@Configuration
public class SecurityConfig {

    private final SmsCodeAuthenticationProvider smsCodeAuthenticationProvider;
    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;

    // 注入自定义Provider、UserDetailsService、密码编码器
    public SecurityConfig(SmsCodeAuthenticationProvider smsCodeAuthenticationProvider,
                          UserDetailsService userDetailsService,
                          PasswordEncoder passwordEncoder) {
        this.smsCodeAuthenticationProvider = smsCodeAuthenticationProvider;
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
    }

    // 1. 配置默认的DaoAuthenticationProvider（处理表单登录）
    @Bean
    public DaoAuthenticationProvider daoAuthenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService); // 关联用户信息查询
        provider.setPasswordEncoder(passwordEncoder); // 关联密码编码器
        return provider;
    }

    // 2. 配置AuthenticationManager：使用ProviderManager，注册多个AuthenticationProvider
    @Bean
    public AuthenticationManager authenticationManager() {
        // ProviderManager接收一个AuthenticationProvider列表，按顺序尝试认证
        return new ProviderManager(
                Arrays.asList(
                        smsCodeAuthenticationProvider, // 自定义：短信验证码认证
                        daoAuthenticationProvider()    // 默认：表单登录认证
                )
        );
    }

    // 3. 配置SecurityFilterChain，关联自定义的AuthenticationManager
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().authenticated()
                )
                .formLogin() // 表单登录：使用上面配置的AuthenticationManager
                .and()
                .csrf().disable(); // 测试用，实际项目需开启

        // 关联自定义的AuthenticationManager（Spring Boot 3.x+ 需手动关联）
        http.authenticationManager(authenticationManager());

        return http.build();
    }

    // 配置密码编码器
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

##### 步骤3：`ProviderManager` 的执行逻辑（核心！）
当用户发起认证请求时，`ProviderManager` 会按以下规则执行：
1. 遍历注册的 `AuthenticationProvider` 列表（先短信验证码Provider，后表单登录Provider）；
2. 对每个 `Provider`，调用 `supports()` 方法判断是否支持当前 `Authentication` 类型；
3. 如果支持，调用 `authenticate()` 方法执行认证：
    - 认证成功 → 返回已认证的 `Authentication`，停止遍历；
    - 认证失败 → 抛异常，停止遍历；
    - 不支持 → 跳过当前 `Provider`，尝试下一个；
4. 如果所有 `Provider` 都不支持/认证失败 → 抛 `ProviderNotFoundException` 或 `AuthenticationException`。

#### 场景3：手动调用 `AuthenticationManager`（非过滤器场景）
如果你的认证不是通过 Spring Security 过滤器（比如小程序后端、API 接口认证），可以直接注入 `AuthenticationManager` 手动调用：
```java
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final AuthenticationManager authenticationManager;

    public AuthController(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    // 手动调用AuthenticationManager进行短信验证码认证
    @PostMapping("/sms/login")
    public String smsLogin(@RequestParam String phone, @RequestParam String smsCode) {
        // 1. 创建待认证的Authentication（isAuthenticated=false）
        Authentication unauthenticatedAuth = new UsernamePasswordAuthenticationToken(phone, smsCode);

        try {
            // 2. 调用AuthenticationManager.authenticate()执行认证
            Authentication authenticatedAuth = authenticationManager.authenticate(unauthenticatedAuth);

            // 3. 认证成功：将已认证的Authentication存入SecurityContext
            SecurityContextHolder.getContext().setAuthentication(authenticatedAuth);

            return "登录成功，当前用户：" + authenticatedAuth.getName();
        } catch (Exception e) {
            return "登录失败：" + e.getMessage();
        }
    }
}
```

### 三、`ProviderManager` 的关键特性
1. **多 Provider 支持**：可同时注册多个 `AuthenticationProvider`，实现“表单登录+短信登录+OAuth2登录”共存；
2. **顺序优先级**：按注册顺序尝试认证，先注册的 Provider 优先级更高；
3. **父 Manager 兜底**：可配置 `parent AuthenticationManager`，如果当前 `ProviderManager` 的所有 Provider 都不支持，会调用父 Manager 认证（适合多模块系统）；
4. **线程安全**：`ProviderManager` 是线程安全的，可全局复用。

### 四、核心总结（关键点回顾）
1. **`AuthenticationManager` 核心作用**：
    - 是认证流程的统一入口，定义 `authenticate()` 方法处理认证；
    - 过滤器场景：由 Spring Security 过滤器自动调用，认证成功后存入 `SecurityContext`；
    - 非过滤器场景：可手动注入调用，灵活适配自定义认证流程；
2. **`ProviderManager` 核心用法**：
    - 是 `AuthenticationManager` 的默认实现，统筹多个 `AuthenticationProvider`；
    - 按顺序调用 `AuthenticationProvider` 的 `authenticate()`，实现多认证方式共存；
    - 支持自定义 `AuthenticationProvider`，扩展专属认证逻辑（如短信、人脸、硬件认证）；
3. **核心设计思想**：
    - 「单一职责」：`AuthenticationManager` 只负责“调度认证”，`AuthenticationProvider` 负责“执行具体认证”；
    - 「开放扩展」：新增认证方式只需实现 `AuthenticationProvider`，无需修改核心逻辑，符合开闭原则。

简单说：`AuthenticationManager` 是“认证总调度”，`ProviderManager` 是“默认总调度”，`AuthenticationProvider` 是“具体认证工人”——总调度按顺序安排工人干活，工人干完活返回结果，总调度再把结果交给框架/业务逻辑，完美实现了认证流程的“标准化+可扩展”。