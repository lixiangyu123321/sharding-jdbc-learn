`AuthenticationTrustResolver` 是 Spring Security 中**专门用于识别认证对象「信任类型」**的核心接口，它的核心作用是**区分当前 `SecurityContext` 中的 `Authentication` 属于「匿名认证」「记住我认证」还是「完全认证」**，是实现**精细化授权控制**和**差异化异常处理**的关键组件，匿名认证、记住我认证的很多核心特性都依赖它实现。

简单来说：它是 Spring Security 内部的**「认证状态识别器」**，让框架和开发者能精准判断用户的登录/认证类型，而非简单的「已认证/未认证」二元判断。

### 一、核心解决的问题
在 Spring Security 中，「已认证」状态并非单一类型：
- 有的用户是**匿名访问**（框架自动生成的虚拟身份）；
- 有的用户是**记住我登录**（通过 Remember-Me 令牌自动登录，非手动输入账号密码）；
- 有的用户是**完全认证**（手动输入账号密码/短信/JWT/OAuth2 等真实认证流程登录）。

这三种状态的「信任级别」不同（比如敏感操作不允许记住我/匿名用户访问），但单纯通过 `Authentication` 接口无法直接区分，`AuthenticationTrustResolver` 就是为了解决这个**认证状态精细化识别**的问题。

### 二、核心接口方法（默认实现 `AuthenticationTrustResolverImpl`）
`AuthenticationTrustResolver` 接口仅有3个核心方法，语义清晰，默认实现类 `AuthenticationTrustResolverImpl` 是 Spring Security 自动装配的，无需手动创建：
```java
public interface AuthenticationTrustResolver {
    // 判断当前认证对象是否是「匿名认证」（AnonymousAuthenticationToken）
    boolean isAnonymous(Authentication authentication);

    // 判断当前认证对象是否是「记住我认证」（RememberMeAuthenticationToken）
    boolean isRememberMe(Authentication authentication);

    // 判断当前认证对象是否是「完全认证」（非匿名、非记住我，如UsernamePasswordAuthenticationToken/JwtAuthenticationToken）
    boolean isFullyAuthenticated(Authentication authentication);
}
```
**判断逻辑**：通过判断 `Authentication` 的具体实现类来识别类型：
- `isAnonymous()` → 匹配 `AnonymousAuthenticationToken`；
- `isRememberMe()` → 匹配 `RememberMeAuthenticationToken`；
- `isFullyAuthenticated()` → 既不是匿名也不是记住我，即为完全认证。

### 三、核心使用场景（框架内置 + 开发者手动使用）
`AuthenticationTrustResolver` 是 Spring Security 很多核心功能的**底层依赖**，同时也支持开发者在业务代码中手动调用，实现精细化的业务控制。

#### 场景1：框架内置 - `ExceptionTranslationFilter` 差异化处理授权异常（最核心）
这是 `AuthenticationTrustResolver` 最关键的内置用途，直接影响用户体验：
- 当用户访问无权限资源时，框架会抛出 `AccessDeniedException`（默认返回403 Forbidden）；
- `ExceptionTranslationFilter` 会调用 `AuthenticationTrustResolver` 判断当前认证类型：
    1. **如果是匿名认证** → **不返回403**，而是跳转到 `AuthenticationEntryPoint`（登录入口，如表单登录页），引导用户手动登录；
    2. **如果是记住我/完全认证用户** → 直接返回403 Forbidden（已认证但无权限，无需引导登录）。

**核心价值**：避免匿名用户看到生硬的403页面，而是友好引导登录，同时对已认证用户的无权限操作做正确的403响应。

#### 场景2：框架内置 - 支持 `IS_AUTHENTICATED_*` 系列精细化授权表达式
Spring Security 的**授权投票器 `AuthenticatedVoter`** 依赖 `AuthenticationTrustResolver`，实现了 `IS_AUTHENTICATED_ANONYMOUSLY`/`IS_AUTHENTICATED_REMEMBERED`/`IS_AUTHENTICATED_FULLY` 三个核心授权表达式，替代了简单的角色判断，实现**按认证类型授权**：
| 表达式                     | 允许的认证类型                     | 等价的SpEL表达式          |
|----------------------------|------------------------------------|---------------------------|
| IS_AUTHENTICATED_ANONYMOUSLY | 仅匿名用户                         | `isAnonymous()`           |
| IS_AUTHENTICATED_REMEMBERED | 记住我用户 + 完全认证用户（排除匿名） | `isRememberMe() or isFullyAuthenticated()` |
| IS_AUTHENTICATED_FULLY     | 仅完全认证用户（排除匿名、记住我） | `isFullyAuthenticated()`  |

在 Spring Security 5.7.11 中，可直接在授权规则中使用（SpEL 表达式更推荐）：
```java
// 仅允许匿名用户访问
.antMatchers("/only/anonymous").access("isAnonymous()")
// 允许记住我+完全认证用户
.antMatchers("/user/center").access("isRememberMe() or isFullyAuthenticated()")
// 仅允许完全认证用户
.antMatchers("/user/modify-pwd").access("isFullyAuthenticated()")
```

#### 场景3：开发者手动使用 - 业务代码中区分认证类型
在控制器、服务层、拦截器等组件中，可**直接注入 `AuthenticationTrustResolver`**，手动判断用户的认证类型，实现差异化的业务逻辑（比如敏感操作仅允许完全认证用户执行）。

**示例：控制器中手动判断**
```java
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BusinessController {

    // 自动注入框架默认的AuthenticationTrustResolverImpl
    @Autowired
    private AuthenticationTrustResolver authenticationTrustResolver;

    @GetMapping("/business/operate")
    public String sensitiveOperate() {
        // 获取当前认证对象
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        // 判断认证类型
        if (authenticationTrustResolver.isAnonymous(authentication)) {
            return "错误：匿名用户不允许执行此操作，请先登录！";
        } else if (authenticationTrustResolver.isRememberMe(authentication)) {
            return "错误：记住我用户不允许执行敏感操作，请重新手动登录！";
        } else if (authenticationTrustResolver.isFullyAuthenticated(authentication)) {
            // 执行敏感业务逻辑
            return "操作成功：完全认证用户执行敏感操作！";
        }
        return "操作失败：未知的认证类型！";
    }
}
```

#### 场景4：框架内置 - 配合记住我认证实现状态区分
在 Remember-Me 记住我认证中，`AuthenticationTrustResolver` 是框架区分「记住我用户」和「完全认证用户」的核心，让记住我功能能和匿名认证、完全认证无缝联动，比如：
- 记住我用户访问 `/user/modify-pwd`（仅允许完全认证）会被拒绝；
- 记住我用户访问 `/user/center`（允许记住我）会被放行。

### 四、关键特性&使用注意事项
1. **自动装配**：Spring Security 会自动创建 `AuthenticationTrustResolverImpl` 的 Bean 并注入容器，开发者无需手动配置，直接 `@Autowired` 即可使用；
2. **无感知扩展**：如果自定义了匿名认证/记住我认证的令牌实现类（比如自定义 `MyAnonymousAuthenticationToken`），只需重写 `AuthenticationTrustResolver` 的判断方法，即可让框架识别自定义的认证类型；
3. **和匿名认证强绑定**：匿名认证的核心价值之一（精细化授权），正是通过 `AuthenticationTrustResolver` 实现的，没有它，匿名认证只是一个「虚拟身份」，无法实现差异化控制；
4. **非Servlet规范**：`AuthenticationTrustResolver` 是 Spring Security 的扩展，并非 Servlet 规范内容，因此 Servlet 原生 API（如 `request.getUserPrincipal()`）无法识别这些认证类型。

### 五、和 `AnonymousAuthenticationToken`/`RememberMeAuthenticationToken` 的关系
三者是**「识别器-被识别对象」**的关系，缺一不可：
1. `AnonymousAuthenticationToken`/`RememberMeAuthenticationToken`：是「认证类型的载体」，分别表示匿名、记住我认证的具体对象；
2. `AuthenticationTrustResolver`：是「识别器」，通过判断认证对象的具体实现类，确定其所属的认证类型；
3. 配合完全认证的令牌（如 `UsernamePasswordAuthenticationToken`/`JwtAuthenticationToken`），实现了三种认证类型的完整区分。

### 核心总结
`AuthenticationTrustResolver` 是 Spring Security 中**认证状态精细化识别的核心接口**，核心作用可概括为3点：
1. **框架底层依赖**：支撑匿名认证的异常处理、记住我的状态区分、精细化授权表达式的实现；
2. **认证类型判断**：提供 `isAnonymous()`/`isRememberMe()`/`isFullyAuthenticated()` 三个方法，精准区分三种认证状态；
3. **业务赋能**：允许开发者在业务代码中手动注入并使用，实现按认证类型的差异化业务控制。

简单来说，没有 `AuthenticationTrustResolver`，Spring Security 就无法区分「匿名/记住我/完全认证」，只能做简单的「已认证/未认证」判断，匿名认证和记住我的精细化特性也无法实现。 