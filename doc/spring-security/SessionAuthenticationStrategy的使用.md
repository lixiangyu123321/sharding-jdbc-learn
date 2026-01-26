你问到的 `SessionAuthenticationStrategy`（简称 SAS）是 Spring Security 中**专门处理「认证成功后 Session 相关策略」的核心接口**——简单说，它的作用是：**在用户认证成功（登录）时，统一管理 Session 的创建、更新、销毁等行为，解决 Session 安全（如固定会话攻击）、并发登录控制、Session 过期策略等问题**。

你可以把它理解为：用户登录成功后，Spring Security 会“委托” `SessionAuthenticationStrategy` 来执行一系列和 Session 相关的“善后操作”，确保 Session 符合你配置的安全规则。

### 一、核心定位：什么时候会触发 SAS？
`SessionAuthenticationStrategy` 会在**认证成功的瞬间**被调用（比如表单登录、OAuth2 登录、自定义登录认证成功后），触发点通常是：
1. `AbstractAuthenticationProcessingFilter`（表单登录过滤器的父类）的认证成功回调；
2. `AuthenticationManager` 认证成功后，框架自动调用；
3. 记住我（Remember-Me）登录成功时也会触发。

### 二、`SessionAuthenticationStrategy` 的核心作用（解决什么问题？）
它的核心价值是**标准化 Session 安全策略**，Spring Security 内置了多个实现类，覆盖绝大多数场景，先看核心实现类的作用：

| 实现类 | 核心作用 | 解决的问题 |
|--------|----------|------------|
| `ConcurrentSessionControlAuthenticationStrategy` | 控制同一用户的并发登录数 | 比如限制“一个账号最多同时登录2台设备”，超出则踢掉旧会话 |
| `SessionFixationProtectionStrategy` | 会话固定攻击防护 | 登录成功后，创建新 Session ID（丢弃旧 ID），防止攻击者利用固定 Session ID 劫持会话 |
| `RegisterSessionAuthenticationStrategy` | 将认证后的 Session 注册到 Session 注册表 | 配合并发登录控制，记录用户所有活跃 Session |
| `ChangeSessionIdAuthenticationStrategy` | 登录成功后更换 Session ID（不销毁 Session） | 轻量级会话固定防护（比创建新 Session 性能更好） |
| `NullAuthenticatedSessionStrategy` | 空实现，不做任何操作 | 无需处理 Session 策略时使用（如无状态 API 认证） |

#### 关键补充：会话固定攻击（Session Fixation）
这是 SAS 最核心的安全场景，举个例子理解：
1. 攻击者诱导你访问网站，获取你当前的 Session ID（比如 `JSESSIONID=abc123`）；
2. 你用这个 Session ID 登录网站（认证成功）；
3. 攻击者用同一个 `JSESSIONID=abc123` 就能直接访问你的登录态（劫持会话）；
4. `SessionFixationProtectionStrategy` 会在你登录成功后，**创建新的 Session ID**（比如 `JSESSIONID=def456`），丢弃旧 ID，攻击者的旧 ID 就失效了。

### 三、核心用法：如何配置 SAS？
#### 场景1：基础配置（默认会话固定防护）
Spring Security 5.x+ 中，**默认已经启用 `ChangeSessionIdAuthenticationStrategy`**（轻量级会话固定防护），无需手动配置。如果需要更严格的防护（创建新 Session），可以手动指定：

```java
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.SessionFixationProtectionStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;

@Configuration
public class SecurityConfig {

    // 1. 配置会话固定防护策略（创建新Session，而非仅换ID）
    @Bean
    public SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        SessionFixationProtectionStrategy strategy = new SessionFixationProtectionStrategy();
        // 设置为创建新Session（默认值就是NEW_SESSION，可省略）
        strategy.setMigrateSessionAttributes(true); // 迁移旧Session的属性到新Session
        return strategy;
    }

    // 2. 关联到SecurityFilterChain
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .formLogin()
                // 配置会话认证策略
                .and()
                .sessionManagement(session -> session
                        .sessionAuthenticationStrategy(sessionAuthenticationStrategy())
                        // 可选：其他Session配置（如超时时间）
                        .invalidSessionUrl("/login?sessionExpired") // Session失效后跳转
                        .maximumSessions(2) // 配合并发登录控制，最多2个并发会话
                        .expiredUrl("/login?expired") // 并发登录超出限制时跳转
                );
        return http.build();
    }
}
```

#### 场景2：并发登录控制（核心实战场景）
限制同一用户最多登录2台设备，超出则踢掉旧会话，核心是组合 `ConcurrentSessionControlAuthenticationStrategy` + `RegisterSessionAuthenticationStrategy`：

```java
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ConcurrentSessionControlAuthenticationStrategy;
import org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;

import java.util.Arrays;

@Configuration
public class SessionSecurityConfig {

    // 1. 配置Session注册表（记录用户所有活跃Session）
    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    // 2. 组合多个Session策略（并发控制 + 注册Session + 会话固定防护）
    @Bean
    public SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        // 策略1：并发登录控制（最多2个会话）
        ConcurrentSessionControlAuthenticationStrategy concurrentStrategy = 
                new ConcurrentSessionControlAuthenticationStrategy(sessionRegistry());
        concurrentStrategy.setMaximumSessions(2); // 最多2台设备登录
        concurrentStrategy.setExceptionIfMaximumExceeded(false); // 超出时踢旧会话（而非抛异常）

        // 策略2：注册Session到注册表
        RegisterSessionAuthenticationStrategy registerStrategy = 
                new RegisterSessionAuthenticationStrategy(sessionRegistry());

        // 策略3：会话固定防护
        SessionFixationProtectionStrategy fixationStrategy = new SessionFixationProtectionStrategy();

        // 组合策略（按顺序执行）
        CompositeSessionAuthenticationStrategy compositeStrategy = 
                new CompositeSessionAuthenticationStrategy(
                        Arrays.asList(concurrentStrategy, fixationStrategy, registerStrategy)
                );
        return compositeStrategy;
    }

    // 3. 配置SecurityFilterChain，关联策略和Session注册表
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .formLogin()
                .and()
                .sessionManagement(session -> session
                        .sessionAuthenticationStrategy(sessionAuthenticationStrategy())
                        .sessionRegistry(sessionRegistry()) // 关联Session注册表
                        .maximumSessions(2) // 和并发策略保持一致
                        .expiredUrl("/login?expired") // 会话过期跳转
                )
                // 必须启用ConcurrentSessionFilter（清理过期会话）
                .addFilter(new ConcurrentSessionFilter(sessionRegistry(), event -> {
                    // 会话过期/被踢时的回调（可选）
                    event.getResponse().sendRedirect("/login?kicked");
                }));
        return http.build();
    }
}
```

### 四、核心流程：SAS 如何工作？
以“表单登录成功 + 并发登录控制 + 会话固定防护”为例，完整流程：
1. 用户提交账号密码，`AuthenticationManager` 认证成功；
2. 框架调用 `SessionAuthenticationStrategy` 的 `onAuthentication()` 方法；
3. 执行组合策略：
    - 第一步：`ConcurrentSessionControlAuthenticationStrategy` 检查该用户的并发会话数，若超出2个，销毁最旧的会话；
    - 第二步：`SessionFixationProtectionStrategy` 创建新 Session ID，迁移旧属性，丢弃旧 ID；
    - 第三步：`RegisterSessionAuthenticationStrategy` 将新 Session 注册到 `SessionRegistry`；
4. 最终，用户的新 Session 符合安全规则，且并发数被控制。

### 五、自定义 SessionAuthenticationStrategy（扩展场景）
如果内置策略满足不了需求（比如“登录成功后给 Session 加自定义属性”“根据用户角色设置不同的 Session 超时时间”），可以自定义实现：

```java
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@Component
public class CustomSessionAuthenticationStrategy implements SessionAuthenticationStrategy {

    @Override
    public void onAuthentication(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        // 1. 获取认证后的用户信息
        String username = authentication.getName();
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        // 2. 获取Session（认证成功后一定有Session）
        HttpSession session = request.getSession();

        // 3. 自定义策略1：给Session添加自定义属性
        session.setAttribute("loginTime", System.currentTimeMillis());
        session.setAttribute("userType", isAdmin ? "admin" : "user");

        // 4. 自定义策略2：根据角色设置Session超时时间
        if (isAdmin) {
            session.setMaxInactiveInterval(3600); // 管理员Session 1小时过期
        } else {
            session.setMaxInactiveInterval(1800); // 普通用户30分钟过期
        }

        // 5. 可选：调用内置策略（组合使用）
        SessionFixationProtectionStrategy fixationStrategy = new SessionFixationProtectionStrategy();
        fixationStrategy.onAuthentication(authentication, request, response);
    }
}
```

### 总结
#### 关键点回顾
1. **核心作用**：认证成功后统一管理 Session 策略，解决 Session 安全、并发登录、过期控制等问题；
2. **触发时机**：用户登录（认证成功）的瞬间，由 Spring Security 自动调用；
3. **核心实现**：
    - 会话固定防护：`SessionFixationProtectionStrategy`/`ChangeSessionIdAuthenticationStrategy`；
    - 并发登录控制：`ConcurrentSessionControlAuthenticationStrategy` + `SessionRegistry`；
4. **扩展方式**：自定义实现 `SessionAuthenticationStrategy`，可叠加内置策略实现复杂需求；
5. **默认行为**：Spring Security 5.x+ 默认启用 `ChangeSessionIdAuthenticationStrategy`，无需手动配置即可防会话固定攻击。

简单说，`SessionAuthenticationStrategy` 是 Spring Security 留给开发者的“Session 安全策略扩展点”——它把认证成功后和 Session 相关的所有操作都封装成“策略”，你只需配置/自定义策略，无需关心何时触发、如何集成，框架会自动完成。