你问到的是 `ProviderManager` 中「父级 `AuthenticationManager`」和「认证成功后清理凭证」这两个核心特性，我会先拆解「父级 `AuthenticationManager`」的作用（含多过滤器链场景示例），再解释「清理凭证」的安全设计，让你彻底理解这两个关键点。

### 一、先理解：`ProviderManager` 父级 `AuthenticationManager` 的核心作用
#### 1. 核心含义
`ProviderManager` 允许配置一个「父级 `AuthenticationManager`」：当当前 `ProviderManager` 自身注册的所有 `AuthenticationProvider` 都**不支持/无法处理**某个认证请求时，会委托给「父级 `AuthenticationManager`」继续尝试认证——本质是「分层认证」，实现“通用认证逻辑共享 + 专属认证逻辑隔离”。

#### 2. 适用场景：多 `SecurityFilterChain` 场景（核心！）
Spring Security 支持配置**多个 `SecurityFilterChain`**（不同URL匹配不同的安全规则），比如：
- 场景：后台管理系统（`/admin/**`）和移动端API（`/api/**`）有不同的认证规则，但共享“短信验证码登录”这个通用认证逻辑。
    - 专属逻辑：`/admin/**` 用「表单登录」，`/api/**` 用「Token认证」；
    - 共享逻辑：两者都支持「短信验证码登录」（父级 `AuthenticationManager` 处理）。

#### 3. 代码示例：多 `ProviderManager` 共享父级
##### 步骤1：定义父级 `AuthenticationManager`（共享通用认证逻辑）
```java
import org.springframework.context.annotation.Bean;
import org.springframework.org.lix.mycatdemo.security.authentication.AuthenticationManager;
import org.springframework.org.lix.mycatdemo.security.authentication.ProviderManager;
import org.springframework.org.lix.mycatdemo.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.org.lix.mycatdemo.security.core.userdetails.UserDetailsService;
import org.springframework.org.lix.mycatdemo.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Collections;

// 父级AuthenticationManager：处理通用认证（短信验证码登录）
@Component
public class SharedAuthManagerConfig {
    // 1. 通用认证Provider：短信验证码认证（所有过滤器链共享）
    @Bean
    public SmsCodeAuthenticationProvider smsCodeAuthenticationProvider() {
        return new SmsCodeAuthenticationProvider();
    }

    // 2. 父级ProviderManager：只包含通用认证Provider
    @Bean
    public AuthenticationManager parentAuthenticationManager() {
        return new ProviderManager(Collections.singletonList(smsCodeAuthenticationProvider()));
    }
}
```

##### 步骤2：定义两个子级 `ProviderManager`（专属认证逻辑）
```java
import org.springframework.context.annotation.Bean;
import org.springframework.org.lix.mycatdemo.security.authentication.AuthenticationManager;
import org.springframework.org.lix.mycatdemo.security.authentication.ProviderManager;
import org.springframework.org.lix.mycatdemo.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.org.lix.mycatdemo.security.core.userdetails.UserDetailsService;
import org.springframework.org.lix.mycatdemo.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Collections;

@Component
public class SubAuthManagerConfig {
    private final AuthenticationManager parentAuthenticationManager;
    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;

    public SubAuthManagerConfig(AuthenticationManager parentAuthenticationManager,
                                UserDetailsService userDetailsService,
                                PasswordEncoder passwordEncoder) {
        this.parentAuthenticationManager = parentAuthenticationManager;
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
    }

    // 子级1：后台管理系统专用（表单登录）
    @Bean
    public AuthenticationManager adminAuthManager() {
        // 专属Provider：表单登录
        DaoAuthenticationProvider adminProvider = new DaoAuthenticationProvider();
        adminProvider.setUserDetailsService(userDetailsService);
        adminProvider.setPasswordEncoder(passwordEncoder);

        // 配置子级ProviderManager，指定父级
        return new ProviderManager(
                Collections.singletonList(adminProvider), // 自身专属Provider
                parentAuthenticationManager // 父级：共享通用Provider
        );
    }

    // 子级2：移动端API专用（Token认证）
    @Bean
    public AuthenticationManager apiAuthManager() {
        // 专属Provider：Token认证（自定义）
        TokenAuthenticationProvider apiProvider = new TokenAuthenticationProvider();

        // 配置子级ProviderManager，指定父级
        return new ProviderManager(
                Collections.singletonList(apiProvider), // 自身专属Provider
                parentAuthenticationManager // 父级：共享通用Provider
        );
    }
}
```

##### 步骤3：配置多 `SecurityFilterChain` 关联不同 `ProviderManager`
```java
import org.springframework.context.annotation.Bean;
import org.springframework.org.lix.mycatdemo.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.org.lix.mycatdemo.security.web.SecurityFilterChain;
import org.springframework.stereotype.Configuration;

@Configuration
public class MultiFilterChainConfig {
    private final AuthenticationManager adminAuthManager;
    private final AuthenticationManager apiAuthManager;

    public MultiFilterChainConfig(AuthenticationManager adminAuthManager,
                                  AuthenticationManager apiAuthManager) {
        this.adminAuthManager = adminAuthManager;
        this.apiAuthManager = apiAuthManager;
    }

    // 过滤器链1：匹配/admin/**，关联后台专用AuthManager
    @Bean
    public SecurityFilterChain adminFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/admin/**") // 只匹配/admin/**
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("ADMIN"))
                .formLogin()
                .and()
                .authenticationManager(adminAuthManager); // 关联子级1
        return http.build();
    }

    // 过滤器链2：匹配/api/**，关联API专用AuthManager
    @Bean
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/**") // 只匹配/api/**
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .csrf().disable()
                .authenticationManager(apiAuthManager); // 关联子级2
        return http.build();
    }
}
```

#### 4. 执行逻辑（核心流程）
以 `/admin/login`（表单登录）和 `/admin/sms/login`（短信登录）为例：
1. 访问 `/admin/login`：
    - 当前 `ProviderManager`（adminAuthManager）先尝试自身的 `DaoAuthenticationProvider`（表单登录Provider）；
    - 支持并处理认证，无需调用父级；
2. 访问 `/admin/sms/login`：
    - 当前 `ProviderManager` 自身的 `DaoAuthenticationProvider` 不支持短信认证；
    - 自动委托给父级 `parentAuthenticationManager`；
    - 父级的 `SmsCodeAuthenticationProvider` 处理认证；
3. 访问 `/api/sms/login`：
    - 当前 `ProviderManager`（apiAuthManager）自身的 `TokenAuthenticationProvider` 不支持；
    - 委托给同一个父级，复用短信认证逻辑。

#### 5. 核心价值
- **逻辑复用**：通用认证逻辑（如短信登录）只需写一次，所有子级 `ProviderManager` 都能复用；
- **隔离性**：不同过滤器链的专属认证逻辑（表单/Token）相互隔离，互不影响；
- **扩展性**：新增过滤器链时，只需实现专属Provider，复用父级通用逻辑即可。

### 二、再理解：`ProviderManager` 认证成功后清理凭证的安全设计
#### 1. 核心含义
你提到的「默认情况下，`ProviderManager` 尝试从认证成功返回的 `Authentication` 中清理凭证」，翻译过来就是：
> 认证成功后，`ProviderManager` 会确保返回的 `Authentication` 对象中 `credentials` 字段（如密码、Token、短信验证码）为 `null`，防止敏感信息被存入 `HttpSession` 泄露。

#### 2. 为什么要这么做？
- `Authentication` 对象最终会存入 `SecurityContext`，而 `SecurityContext` 可能被序列化到 `HttpSession` 中（默认 `HttpSessionSecurityContextRepository`）；
- 如果 `credentials` 保留明文密码/Token，会导致敏感信息持久化到服务器内存/磁盘（`HttpSession` 存储），存在泄露风险；
- 认证成功后，`credentials` 已无用处（后续授权只需要 `principal` 和 `authorities`），清理是最优安全实践。

#### 3. 代码层面的体现
`ProviderManager` 的 `authenticate()` 方法核心逻辑（简化版）：
```java
public Authentication authenticate(Authentication authentication) throws AuthenticationException {
    // 1. 遍历自身Provider，尝试认证
    for (AuthenticationProvider provider : getProviders()) {
        if (provider.supports(authentication.getClass())) {
            Authentication result = provider.authenticate(authentication);
            // 2. 认证成功：清理凭证 + 返回
            return clearCredentials(result); 
        }
    }
    // 3. 自身处理不了，委托父级
    if (parent != null) {
        return parent.authenticate(authentication);
    }
    throw new ProviderNotFoundException("无可用的AuthenticationProvider");
}

// 核心：清理凭证
private Authentication clearCredentials(Authentication auth) {
    if (auth instanceof CredentialsContainer) {
        // CredentialsContainer接口的实现类（如UsernamePasswordAuthenticationToken）
        // 会调用eraseCredentials()方法，将credentials置为null
        ((CredentialsContainer) auth).eraseCredentials();
    }
    return auth;
}
```

#### 4. 示例：清理前后的对比
```java
// 认证前：待认证的Authentication（credentials有值）
Authentication unauthenticated = new UsernamePasswordAuthenticationToken("admin", "123456");
System.out.println(unauthenticated.getCredentials()); // 输出：123456

// 认证后：ProviderManager返回的Authentication（credentials被清空）
Authentication authenticated = providerManager.authenticate(unauthenticated);
System.out.println(authenticated.getCredentials()); // 输出：null
```

#### 5. 自定义 `Authentication` 如何支持清理？
只需让自定义 `Authentication` 实现 `CredentialsContainer` 接口，重写 `eraseCredentials()` 方法：
```java
// 自定义短信认证的Authentication
public class SmsCodeAuthenticationToken extends AbstractAuthenticationToken implements CredentialsContainer {
    private final Object principal; // 手机号
    private Object credentials; // 短信验证码

    // 构造方法（未认证）
    public SmsCodeAuthenticationToken(String phone, String smsCode) {
        super(null);
        this.principal = phone;
        this.credentials = smsCode;
        setAuthenticated(false);
    }

    // 构造方法（已认证）
    public SmsCodeAuthenticationToken(String phone, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = phone;
        this.credentials = null; // 已认证直接置空
        setAuthenticated(true);
    }

    // 核心：清理凭证
    @Override
    public void eraseCredentials() {
        this.credentials = null;
    }

    // 省略getter/setter
}
```

### 三、核心总结（关键点回顾）
#### 1. 父级 `AuthenticationManager` 核心
- **作用**：实现多 `ProviderManager` 共享通用认证逻辑，适配多 `SecurityFilterChain` 场景；
- **执行逻辑**：当前 `ProviderManager` 处理不了的认证请求，委托给父级继续处理；
- **价值**：通用逻辑复用、专属逻辑隔离，降低代码冗余。

#### 2. 认证成功后清理凭证核心
- **目的**：防止密码、Token等敏感信息存入 `HttpSession` 泄露；
- **实现**：`ProviderManager` 自动调用 `CredentialsContainer.eraseCredentials()`，将 `credentials` 置为 `null`；
- **要求**：自定义 `Authentication` 需实现 `CredentialsContainer` 接口，才能被自动清理。

简单说：
- 父级 `AuthenticationManager` 是「认证逻辑的分层复用设计」，解决多场景下的代码复用问题；
- 清理凭证是「安全兜底设计」，从根源上避免敏感认证信息泄露——这两个特性都是 `ProviderManager` 作为默认 `AuthenticationManager` 的核心优势。