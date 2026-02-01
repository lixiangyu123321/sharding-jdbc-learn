# Spring Security 登出（Logout）处理详解
## 概述
在支持用户登录的应用中，登出是必备功能。Spring Security 会**默认创建 `/logout` 端点**，无需额外编码即可实现基础登出能力。本文将详细讲解登出的架构设计、自定义配置及各类特殊场景的处理方案。

## 一、了解 Logout 的架构
当项目引入 `spring-boot-starter-security` 依赖或使用 `@EnableWebSecurity` 注解时，Spring Security 会自动添加登出支持，**默认响应 `GET /logout` 和 `POST /logout`** 两种请求，核心处理逻辑由过滤器链中的 `LogoutFilter` 主导。

### 1. GET /logout 行为
发起 `GET /logout` 请求时，Spring Security 会展示**注销确认页面**，核心作用：
- 为用户提供二次确认机制，防止误操作；
- 自动生成 POST 请求所需的 **CSRF Token**（保障跨域请求安全）。

### 2. POST /logout 行为
发起带合法 CSRF Token 的 `POST /logout` 请求时，框架会通过一系列 **`LogoutHandler`** 执行默认登出操作，流程如下：
1. 使 HTTP Session 失效（由 `SecurityContextLogoutHandler` 实现）；
2. 清理 `SecurityContextHolderStrategy` 中的认证信息（`SecurityContextLogoutHandler`）；
3. 清理 `SecurityContextRepository` 中存储的安全上下文（`SecurityContextLogoutHandler`）；
4. 清除记住我（RememberMe）认证令牌（`TokenRememberMeServices`/`PersistentTokenRememberMeServices`）；
5. 清除已保存的 CSRF Token（`CsrfLogoutHandler`）；
6. 触发 `LogoutSuccessEvent` 事件（`LogoutSuccessEventPublishingLogoutHandler`）；
7. 执行默认的 `LogoutSuccessHandler`，重定向到 `/login?logout` 页面。

### 3. 核心特性
- `LogoutFilter` 在过滤器链中**早于 `AuthorizationFilter`**，因此默认的 `/logout` 端点无需手动配置权限放行；
- 所有登出清理操作由 `LogoutHandler` 完成，处理器之间相互独立，且不允许抛出异常；
- 登出成功后的行为由 `LogoutSuccessHandler` 定义，默认实现为页面重定向。

## 二、自定义注销或注销成功的 URI
### 1. 自定义登出端点 URI
通过 `logoutUrl()` 可修改 Spring Security 监听的登出请求地址，仅需调整 `LogoutFilter` 匹配规则，无需配置权限放行：
```java
http
    .logout((logout) -> logout
        .logoutUrl("/my/logout/uri") // 自定义登出端点，替代默认的 /logout
    );
```

### 2. 自定义登出成功后重定向 URI
通过 `logoutSuccessUrl()` 可指定登出成功后的跳转地址，若需对匿名用户也生效，可配合 `logoutSuccessUrl().permitAll()`：
```java
// 基础配置：登出成功后重定向到自定义页面
http
    .logout((logout) -> logout
        .logoutSuccessUrl("/my/success/endpoint")
    );

// 简化权限配置：自动为所有登出相关 URI 添加入放行列表
http
    .authorizeHttpRequests((authorize) -> authorize
        // 其他授权规则
    )
    .logout((logout) -> logout
        .logoutSuccessUrl("/my/success/endpoint")
        .permitAll() // 无需手动在 authorizeHttpRequests 中配置 permitAll
    );
```

## 三、明确允许 /logout 端点的场景
默认情况下，**无需手动为 `/logout` 或自定义登出端点配置 `permitAll()`**，因为 `LogoutFilter` 在过滤器链中优先级高于授权过滤器 `AuthorizationFilter`，请求会先被登出逻辑处理。

**需要手动放行的唯一场景**：**自定义了 Spring MVC 登出端点**（而非通过 `logout DSL` 配置），此时请求会先经过 Spring MVC 处理，再进入 Spring Security 过滤器链，必须显式配置权限：
```java
http
    .authorizeHttpRequests((authorize) -> authorize
        .requestMatchers("/my/custom/logout").permitAll() // 放行自定义 MVC 登出端点
        .anyRequest().authenticated()
    );
```

## 四、登出时清除 Cookies、Storage 和/或缓存
### 1. 清除指定自定义 Cookie
Spring Security 提供了 `CookieClearingLogoutHandler`，可直接通过 `deleteCookies()` 快速配置需要清除的 Cookie 名称，**无需手动创建处理器**：
```java
http
    .logout((logout) -> logout
        .deleteCookies("our-custom-cookie", "another-cookie") // 清除多个自定义 Cookie
    );
```
> 注意：`JSESSIONID` Cookie 无需手动指定，`SecurityContextLogoutHandler` 在使 Session 失效时会自动清除。

### 2. 清除浏览器端 Storage/缓存（Clear-Site-Data 头）
利用浏览器支持的 **`Clear-Site-Data` HTTP 响应头**，可一次性清除网站的 Cookie、LocalStorage、SessionStorage、缓存等数据，Spring Security 提供了 `ClearSiteDataHeaderWriter` 实现该能力。

#### 清除所有网站数据
```java
import org.springframework.security.web.header.writers.ClearSiteDataHeaderWriter;
import org.springframework.security.web.authentication.logout.HeaderWriterLogoutHandler;

// 清除所有类型的网站数据：cookie、storage、cache 等
HeaderWriterLogoutHandler clearSiteData = new HeaderWriterLogoutHandler(new ClearSiteDataHeaderWriter());
http
    .logout((logout) -> logout
        .addLogoutHandler(clearSiteData)
    );
```

#### 仅清除 Cookie
通过 `ClearSiteDataHeaderWriter.Directives` 指定清理范围，精准控制清理内容：
```java
import org.springframework.security.web.header.writers.ClearSiteDataHeaderWriter;
import org.springframework.security.web.header.writers.ClearSiteDataHeaderWriter.Directives;
import org.springframework.security.web.authentication.logout.HeaderWriterLogoutHandler;

// 仅清除 Cookie
HeaderWriterLogoutHandler clearSiteData = new HeaderWriterLogoutHandler(
    new ClearSiteDataHeaderWriter(Directives.COOKIES)
);
http
    .logout((logout) -> logout
        .addLogoutHandler(clearSiteData)
    );
```

### 3. 自定义登出清理逻辑
`LogoutHandler` 是函数式接口，可通过 Lambda 表达式快速实现自定义清理操作（如清理分布式缓存、删除临时文件等）：
```java
http
    .logout((logout) -> logout
        // 自定义 LogoutHandler，实现额外清理逻辑
        .addLogoutHandler((request, response, authentication) -> {
            // 清理分布式缓存中的用户信息
            // 清理用户临时上传的文件
            // 其他自定义清理操作
        })
    );
```
> 注意：`LogoutHandler` 仅用于清理操作，**禁止抛出异常**，否则会中断整个登出流程。

## 五、自定义注销成功行为
默认的 `LogoutSuccessHandler` 会重定向到指定页面，若需实现自定义行为（如返回 JSON 结果、仅返回状态码），可通过以下方式配置：

### 1. 仅返回 HTTP 状态码（无重定向）
使用框架内置的 `HttpStatusReturningLogoutSuccessHandler`，登出成功后返回指定 HTTP 状态码（默认 200 OK），适用于前后端分离项目：
```java
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;

http
    .logout((logout) -> logout
        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler()) // 返回 200 OK
    );
```
可通过构造函数自定义状态码：
```java
// 登出成功后返回 204 No Content
.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
```

### 2. 自定义 LogoutSuccessHandler（Lambda 方式）
利用 `LogoutSuccessHandler` 是函数式接口的特性，通过 Lambda 实现自定义逻辑（如返回 JSON 响应）：
```java
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;

http
    .logout((logout) -> logout
        .logoutSuccessHandler((request, response, authentication) -> {
            // 自定义登出成功响应：返回 JSON 结果
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            ObjectMapper objectMapper = new ObjectMapper();
            String json = objectMapper.writeValueAsString(
                Map.of("code", 200, "msg", "登出成功")
            );
            response.getWriter().write(json);
        })
    );
```

### 3. 自定义实现 LogoutSuccessHandler 接口
适用于复杂的自定义逻辑，通过实现接口实现可复用的登出成功处理器：
```java
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;

// 自定义登出成功处理器
public class CustomLogoutSuccessHandler implements LogoutSuccessHandler {
    @Override
    public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
        // 复杂的自定义逻辑
        // 如：记录登出日志、推送登出通知、返回多语言响应等
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":200,\"msg\":\"登出成功\"}");
    }
}

// 配置到 Security 中
http
    .logout((logout) -> logout
        .logoutSuccessHandler(new CustomLogoutSuccessHandler())
    );
```

## 六、创建自定义注销端点
**强烈建议优先使用 `logout DSL` 配置登出**，而非自定义 Spring MVC 端点，因为手动实现易遗漏 Spring Security 核心的登出清理逻辑（如未清理 `SecurityContext` 会导致用户未真正登出）。

若业务场景必须自定义 MVC 登出端点，**必须调用 `SecurityContextLogoutHandler`** 完成核心清理操作，步骤如下：

### 1. 配置自定义端点并调用核心处理器
```java
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;

@RestController
public class CustomLogoutController {
    // 注入 Spring Security 核心登出处理器
    private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();

    // 自定义 MVC 登出端点
    @PostMapping("/my/custom/logout")
    public String performLogout(Authentication authentication,
                                HttpServletRequest request,
                                HttpServletResponse response) {
        // 执行 Spring Security 核心登出逻辑：清理 SecurityContext、Session 等
        this.logoutHandler.doLogout(request, response, authentication);
        
        // 自定义业务逻辑：如记录日志、清理缓存等
        return "redirect:/home"; // 或返回 JSON 结果
    }
}
```

### 2. 显式放行自定义端点
```java
http
    .authorizeHttpRequests((authorize) -> authorize
        .requestMatchers("/my/custom/logout").permitAll() // 放行自定义登出端点
        .anyRequest().authenticated()
    )
    // 关闭默认登出端点（可选）
    .logout((logout) -> logout.disable());
```

> 注意：若未调用 `SecurityContextLogoutHandler.doLogout()`，`SecurityContextHolder` 中的认证信息不会被清理，后续请求仍会认为用户处于登录状态，导致**登出失效**。

## 七、特殊协议的登出协调
### 1. OAuth 2.0 与授权服务器协调注销
当应用基于 OAuth 2.0 实现认证时，登出需同时完成**应用本地登出**和**授权服务器端登出**，避免用户在授权服务器的会话仍有效导致重新授权时无需输入密码。

核心思路：
1. 先执行应用本地登出逻辑；
2. 重定向到授权服务器的**登出端点**（需符合 OAuth 2.0 规范），携带必要参数（如 `client_id`、`redirect_uri`）；
3. 授权服务器完成登出后，重定向回应用的指定页面。

### 2. SAML 2.0 与身份提供者（IdP）协调注销
SAML 2.0 协议下的登出为**单点登出（SSO Logout）**，需应用（服务提供者 SP）与身份提供者（IdP）交互，通知 IdP 销毁用户的全局会话，同时销毁所有已集成应用的本地会话。

Spring Security SAML 2.0 模块提供了内置的单点登出支持，只需配置 IdP 的登出端点和协议参数即可实现自动协调。

### 3. CAS 与身份提供者协调注销
CAS（Central Authentication Service）协议的核心特性是单点登录/登出，应用登出时需向 CAS 服务器发送登出请求，CAS 服务器会通知所有已认证的应用销毁本地会话，实现**全局登出**。

Spring Security CAS 模块已封装好 CAS 登出的核心逻辑，只需配置 CAS 服务器的登出端点即可实现自动协调。

## 核心总结
1. Spring Security 登出的核心是 `LogoutFilter` 主导，配合 `LogoutHandler` 完成清理、`LogoutSuccessHandler` 定义成功行为，默认 `/logout` 端点无需手动放行；
2. 自定义登出优先使用 `logout DSL`，而非手动创建 MVC 端点，避免遗漏核心清理逻辑；
3. 登出时的 Cookie、缓存清理可通过 `deleteCookies()` 或 `ClearSiteDataHeaderWriter` 快速实现，自定义清理逻辑可通过 Lambda 实现 `LogoutHandler`；
4. 前后端分离项目可通过自定义 `LogoutSuccessHandler` 返回 JSON 或仅返回 HTTP 状态码，替代默认的页面重定向；
5. 自定义 MVC 登出端点**必须调用 `SecurityContextLogoutHandler.doLogout()`**，否则会导致登出失效；
6. OAuth 2.0/SAML 2.0/CAS 等协议的登出，需在本地登出后与对应的认证/授权服务器协调，实现**全局登出**。