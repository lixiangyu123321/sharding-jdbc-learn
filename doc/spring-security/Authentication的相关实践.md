你提的这个点是理解 `Authentication` 核心的关键——它既是「用户提交的待认证凭证」，也是「认证成功后的用户身份信息」，我用**两个实战场景+代码示例**，把这两种用途、以及 `principal`/`credentials`/`authorities` 三个核心字段的含义和变化过程讲透，让你一眼看懂。

### 场景1：作为「待认证的用户凭证」（isAuthenticated() = false）
这是 `Authentication` 最开始的形态——用户提交账号密码后，框架先创建一个「未认证」的 `Authentication`，交给 `AuthenticationManager` 去校验。

#### 核心流程+代码示例
假设用户在登录页输入 `用户名：admin`、`密码：123456`，想通过表单登录认证：
```java
// 1. 第一步：用户提交凭证，框架创建「未认证」的 Authentication
// 实现类：UsernamePasswordAuthenticationToken（表单登录专用）
Authentication unauthenticatedAuth = 
    new UsernamePasswordAuthenticationToken(
        "admin",          // principal：待认证的用户名（用户输入的）
        "123456",         // credentials：待认证的密码（用户输入的）
        Collections.emptyList() // authorities：未认证时权限为空
    );

// 2. 此时的核心状态：未认证
System.out.println(unauthenticatedAuth.isAuthenticated()); // 输出：false

// 3. 第二步：交给 AuthenticationManager 校验凭证
AuthenticationManager authManager = ...; // 框架自动配置的认证管理器
// 校验成功会返回「已认证」的 Authentication；校验失败抛异常（如 BadCredentialsException）
Authentication authenticatedAuth = authManager.authenticate(unauthenticatedAuth);
```

#### 这个阶段的字段含义：
| 字段 | 具体值 | 作用 |
|------|--------|------|
| principal | `"admin"`（字符串） | 仅表示用户输入的「待验证用户名」，还不是完整的用户信息 |
| credentials | `"123456"`（字符串） | 仅表示用户输入的「待验证密码」，是敏感凭证 |
| authorities | 空集合 | 未认证前，用户还没有任何权限 |
| isAuthenticated() | false | 明确标识：这只是待校验的凭证，不是已认证用户 |

### 场景2：作为「已认证的用户身份」（isAuthenticated() = true）
这是 `Authentication` 最终的形态——`AuthenticationManager` 校验账号密码成功后，会生成一个「已认证」的 `Authentication`，存入 `SecurityContext`，代表当前登录用户。

#### 核心流程+代码示例
接上面的流程，`AuthenticationManager` 校验成功后，会基于数据库/内存中的用户信息，创建「已认证」的 `Authentication`：
```java
// 1. 先从数据库查询完整的用户信息（框架通过 UserDetailsService 实现）
UserDetails userDetails = new User(
    "admin",                          // 数据库中的用户名
    "{bcrypt}$2a$10$xxx",             // 数据库中的加密密码（不再存明文）
    // authorities：数据库中配置的用户权限（如 ROLE_ADMIN、READ_ORDER）
    AuthorityUtils.createAuthorityList("ROLE_ADMIN", "READ_ORDER")
);

// 2. 第二步：创建「已认证」的 Authentication
Authentication authenticatedAuth = 
    new UsernamePasswordAuthenticationToken(
        userDetails,      // principal：从 UserDetails 变成完整的用户信息（核心变化！）
        null,             // credentials：清空密码（敏感信息，避免泄露）
        userDetails.getAuthorities() // authorities：数据库配置的真实权限
    );
// 手动标记为已认证（框架内部会自动做这一步）
authenticatedAuth.setAuthenticated(true);

// 3. 第三步：存入 SecurityContext，供全局使用
SecurityContextHolder.getContext().setAuthentication(authenticatedAuth);

// 4. 此时的核心状态：已认证
System.out.println(authenticatedAuth.isAuthenticated()); // 输出：true
```

#### 这个阶段的字段含义：
| 字段 | 具体值 | 作用 |
|------|--------|------|
| principal | `UserDetails` 对象（包含用户名、加密密码、权限） | 代表「已认证的完整用户信息」，后续可通过它获取用户名、权限等 |
| credentials | `null` | 密码已校验完成，清空避免泄露（核心安全设计） |
| authorities | `[ROLE_ADMIN, READ_ORDER]` | 用户真正拥有的权限，是后续接口权限校验的依据（如判断是否能访问 /admin） |
| isAuthenticated() | true | 明确标识：这是已通过认证的合法用户 |

### 补充：不同认证方式的 `Authentication` 示例（更全面）
除了表单登录，OAuth2 登录、记住我登录的 `Authentication` 形态略有不同，但核心逻辑一致，举两个常见例子：

#### 示例1：OAuth2 登录（GitHub 登录）
```java
// 已认证的 OAuth2AuthenticationToken（OAuth2 专用实现类）
OAuth2AuthenticationToken oauth2Auth = 
    new OAuth2AuthenticationToken(
        // principal：OAuth2User 对象（封装 GitHub 用户信息：login 名、邮箱、头像等）
        new DefaultOAuth2User(
            AuthorityUtils.createAuthorityList("SCOPE_user:email"), // 权限（GitHub 授权的 scope）
            Map.of("login", "github_admin", "email", "admin@github.com"), // GitHub 用户信息
            "login" // 唯一标识字段（GitHub 的用户名）
        ),
        AuthorityUtils.createAuthorityList("SCOPE_user:email"), // authorities：授权的 scope
        "github" // 客户端名称（对应配置中的 github 注册信息）
    );
oauth2Auth.setAuthenticated(true);

// 核心字段
System.out.println(oauth2Auth.getPrincipal().getAttribute("login")); // 输出：github_admin
System.out.println(oauth2Auth.getCredentials()); // 输出：null（清空令牌）
System.out.println(oauth2Auth.getAuthorities()); // 输出：[SCOPE_user:email]
System.out.println(oauth2Auth.isAuthenticated()); // 输出：true
```

#### 示例2：记住我（Remember-Me）登录
```java
// 已认证的 RememberMeAuthenticationToken（记住我专用实现类）
Authentication rememberMeAuth = 
    new RememberMeAuthenticationToken(
        "remember-me-key", // 记住我专用密钥（框架配置）
        "admin",           // principal：用户名
        AuthorityUtils.createAuthorityList("ROLE_USER") // 权限（通常比普通登录低）
    );
rememberMeAuth.setAuthenticated(true);

// 核心字段
System.out.println(rememberMeAuth.getPrincipal()); // 输出：admin
System.out.println(rememberMeAuth.getCredentials()); // 输出：null
System.out.println(rememberMeAuth.isAuthenticated()); // 输出：true
```

### 核心总结（关键点回顾）
1. **`Authentication` 的两个核心用途**：
    - 待认证阶段：存储用户输入的账号密码（isAuthenticated=false），交给 `AuthenticationManager` 校验；
    - 已认证阶段：存储完整的用户信息+权限（isAuthenticated=true），代表当前登录用户；
2. **三个核心字段的变化**：
    - `principal`：从「用户输入的用户名（字符串）」→「完整的用户信息（UserDetails/OAuth2User）」；
    - `credentials`：从「用户输入的密码（明文）」→「null（清空敏感信息）」；
    - `authorities`：从「空集合」→「用户真实拥有的权限（角色/scope）」；
3. **核心设计思想**：
   无论哪种认证方式（表单/OAuth2/记住我），`Authentication` 都统一了「用户身份信息」的格式，让后续的权限校验、用户信息读取无需区分认证方式，实现了 Spring Security 认证体系的标准化。

简单说：`Authentication` 就像一张「身份卡」——提交登录时是「待审核的临时卡」，审核通过后变成「生效的正式卡」，正式卡上会标注你的身份、权限，且会撕掉敏感的密码信息，保证安全。