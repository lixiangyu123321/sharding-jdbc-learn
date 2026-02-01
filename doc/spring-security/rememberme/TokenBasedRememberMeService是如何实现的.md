你想了解的`TokenBasedRememberMeServices`是Spring Security中**简单版记住我功能**的官方实现类，它完整实现了`RememberMeServices`接口的三个核心方法，核心特点是**令牌仅加密存储在客户端Cookie、服务端不做持久化**，是轻量但安全性较低的记住我方案。

简单来说，`TokenBasedRememberMeServices`的实现核心是**「服务端无状态的加密令牌机制」**：登录成功时生成**用户信息+过期时间+服务端密钥**加密的令牌，写入前端Cookie；自动登录时从Cookie解析令牌、验签+校验过期时间，合法则直接构建认证对象，全程服务端不存储任何令牌数据，下面从**核心实现原理、三个接口方法的具体实现、关键特性&局限性**三方面，讲清它的完整实现逻辑，同时结合源码级的核心逻辑（简化版），让你直观看到底层怎么做的。

### 一、先明确核心设计前提
`TokenBasedRememberMeServices`是`RememberMeServices`接口的**官方基础实现**，设计之初就是为了**快速实现记住我功能、无需依赖数据库/Redis等存储**，所以它的实现围绕「**无持久化、加密令牌、Cookie传输**」展开，核心依赖两个关键配置：
1. **服务端签名密钥**：用于令牌的生成和验签，防止令牌被伪造/篡改（核心安全保障）；
2. **`UserDetailsService`**：用于根据令牌中的用户信息，查询完整的用户详情（含权限），构建认证对象。

它的令牌核心模型是**无持久化的加密字符串**，所有令牌信息都编码在Cookie中，服务端仅做「生成+验签+解析」，不做任何存储。

---

### 二、`TokenBasedRememberMeServices`的核心实现原理
整体流程分**「登录成功生成令牌」**和**「自动登录校验令牌」**两步，核心是**令牌的生成规则**和**验签规则**，这是理解它实现`RememberMeServices`接口的基础：
#### 1. 令牌生成规则（登录成功时）
生成的令牌是一个**加密的字符串**，核心组成（拼接后做MD5/SHA加密，或直接Base64编码）：
```
令牌原始内容 = 用户名 + ":" + 令牌过期时间戳 + ":" + MD5(用户名 + ":" + 过期时间戳 + ":" + 密码 + ":" + 服务端密钥)
最终令牌 = 对原始内容做Base64编码（方便Cookie传输）
```
**关键细节**：
- 令牌中包含**用户密码的MD5摘要**：这是核心校验点之一，若用户修改密码，旧令牌会立即失效（因为密码变了，验签通不过）；
- 包含**过期时间戳**：用于自动登录时判断令牌是否失效；
- 最后的MD5串是**签名**：防止攻击者篡改令牌中的用户名/过期时间（比如把普通用户改成管理员）。

#### 2. 令牌验签规则（自动登录时）
从Cookie中解析出令牌后，服务端按以下步骤校验合法性，**一步不通过则自动登录失败**：
1. 对令牌做Base64解码，拆分出「用户名、过期时间戳、签名」；
2. 判断令牌是否**过期**（当前时间戳 > 令牌中的过期时间戳），过期则直接失败；
3. 通过`UserDetailsService`查询该用户名的**最新用户详情**（含最新密码）；
4. 用**最新的用户密码**+「用户名+过期时间戳+服务端密钥」重新生成MD5签名，对比令牌中的签名是否一致；
5. 签名一致则令牌合法，否则伪造/篡改，校验失败。

---

### 三、`TokenBasedRememberMeServices`对`RememberMeServices`三个方法的具体实现
这是核心部分，`TokenBasedRememberMeServices`重写了`autoLogin`、`loginSuccess`、`loginFail`三个方法，每个方法的实现都严格贴合「无持久化加密令牌」的设计，下面结合**源码级简化逻辑**（剔除无关细节，保留核心）讲清每个方法的实现，和你自定义的`CustomRememberMeService`形成对比。

#### 前置：核心成员变量（所有方法的基础）
```java
public class TokenBasedRememberMeServices implements RememberMeServices {
    // 前端记住我参数名，默认"remember-me"，和前端复选框对应
    public static final String DEFAULT_PARAMETER = "remember-me";
    // Cookie名，默认"remember-me"
    public static final String DEFAULT_COOKIE_NAME = "remember-me";
    // 服务端签名密钥（构造器传入，必须配置，否则报错）
    private final String key;
    // 用户详情服务（构造器传入，用于查询用户信息）
    private final UserDetailsService userDetailsService;
    // 令牌有效期，默认14天（秒），可通过setTokenValiditySeconds修改
    private int tokenValiditySeconds = 14 * 24 * 60 * 60;
    // Cookie路径，默认"/"
    private String cookiePath = "/";
    // 密码编码器（适配加密密码，Spring Security5+后推荐配置）
    private PasswordEncoder passwordEncoder;

    // 构造器：必须传入密钥和UserDetailsService，这是核心依赖
    public TokenBasedRememberMeServices(String key, UserDetailsService userDetailsService) {
        this.key = key;
        this.userDetailsService = userDetailsService;
    }

    // 省略get/set方法...
}
```

#### 1. `loginSuccess`方法实现：生成令牌并写入Cookie
**触发时机**：用户手动登录成功，且前端提交`remember-me=true`（勾选记住我）；
**核心职责**：判断是否勾选记住我 → 生成加密令牌 → 写入带安全属性的Cookie；
**源码级简化实现**：
```java
@Override
public void loginSuccess(HttpServletRequest request, HttpServletResponse response,
                         Authentication successfulAuthentication) {
    // 步骤1：获取前端的记住我参数，判断是否勾选（默认参数名remember-me）
    String rememberMe = request.getParameter(getParameter());
    if (rememberMe == null || !"true".equals(rememberMe.trim())) {
        return; // 未勾选，直接返回，不处理
    }

    // 步骤2：从认证对象中获取登录成功的用户详情
    UserDetails userDetails = (UserDetails) successfulAuthentication.getPrincipal();
    String username = userDetails.getUsername();
    String password = userDetails.getPassword(); // 注意：是加密后的密码（若配置了PasswordEncoder）

    // 步骤3：生成令牌过期时间戳（当前时间 + 有效期，单位：毫秒）
    long expiryTime = System.currentTimeMillis() + (long) getTokenValiditySeconds() * 1000;
    // 步骤4：生成加密令牌（核心方法，按之前的规则生成）
    String token = generateToken(userDetails, expiryTime);
    // 步骤5：创建记住我Cookie，设置安全属性并写入响应
    createCookie(token, expiryTime, request, response);
}

// 核心工具方法：生成加密令牌
private String generateToken(UserDetails userDetails, long expiryTime) {
    String username = userDetails.getUsername();
    String password = userDetails.getPassword();
    // 生成签名：MD5(用户名+过期时间+密码+密钥)，防止篡改
    String signature = DigestUtils.md5DigestAsHex(
            (username + ":" + expiryTime + ":" + password + ":" + key).getBytes(StandardCharsets.UTF_8)
    );
    // 拼接原始内容并Base64编码，得到最终令牌
    String tokenRaw = username + ":" + expiryTime + ":" + signature;
    return Base64.getEncoder().encodeToString(tokenRaw.getBytes(StandardCharsets.UTF_8));
}

// 核心工具方法：创建Cookie并写入响应（自带安全属性）
private void createCookie(String token, long expiryTime, HttpServletRequest request, HttpServletResponse response) {
    Cookie cookie = new Cookie(DEFAULT_COOKIE_NAME, token);
    cookie.setPath(cookiePath); // 限定作用路径
    cookie.setMaxAge(getTokenValiditySeconds()); // 设置有效期（秒）
    cookie.setHttpOnly(true); // 核心安全属性：防XSS，默认开启
    // Secure属性：若请求是HTTPS则自动开启，否则关闭（可手动强制设置）
    cookie.setSecure(request.isSecure());
    // SameSite属性：Spring Security高版本已默认设置Lax，防CSRF
    response.addCookie(cookie);
}
```
**关键细节**：
- 未勾选记住我则直接返回，无任何操作；
- Cookie默认开启`HttpOnly=true`，这是基础安全防护，杜绝XSS窃取；
- 令牌生成依赖**用户的加密密码**，密码修改后旧令牌立即失效。

#### 2. `autoLogin`方法实现：解析Cookie令牌+验签+构建认证对象
**触发时机**：用户无有效Session，访问受保护资源，Spring Security触发自动登录；
**核心职责**：从Cookie解析令牌 → 验签+校验过期 → 查询用户详情 → 构建并返回`Authentication`；
**源码级简化实现**：
```java
@Override
public Authentication autoLogin(HttpServletRequest request, HttpServletResponse response) {
    // 步骤1：从请求中获取记住我Cookie，无则返回null（自动登录失败）
    Cookie cookie = WebUtils.getCookie(request, DEFAULT_COOKIE_NAME);
    if (cookie == null || cookie.getValue().isEmpty()) {
        return null;
    }
    String token = cookie.getValue();

    // 步骤2：解析并校验令牌，返回合法的用户详情（核心方法，失败返回null）
    UserDetails userDetails = processAutoLoginCookie(token.split(":"), request, response);
    if (userDetails == null) {
        return null; // 令牌非法/过期，自动登录失败
    }

    // 步骤3：构建Spring Security的认证对象，完成自动登录
    // 注意：credentials传null，因为记住我是无密码认证；权限从UserDetails中获取
    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            userDetails,
            null,
            userDetails.getAuthorities()
    );
    // 添加上下文信息（如请求详情），符合Spring Security认证规范
    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
    return auth;
}

// 核心工具方法：解析、验签令牌，返回用户详情（失败返回null）
protected UserDetails processAutoLoginCookie(String[] cookieTokens, HttpServletRequest request, HttpServletResponse response) {
    // 校验令牌格式：必须是[用户名, 过期时间戳, 签名]三段，否则非法
    if (cookieTokens.length != 3) {
        cancelCookie(request, response); // 清理非法Cookie
        return null;
    }

    String username = cookieTokens[0];
    long expiryTime;
    try {
        expiryTime = Long.parseLong(cookieTokens[1]);
    } catch (NumberFormatException e) {
        cancelCookie(request, response);
        return null;
    }
    String signature = cookieTokens[2];

    // 步骤1：判断令牌是否过期，过期则清理Cookie并返回null
    if (System.currentTimeMillis() > expiryTime) {
        cancelCookie(request, response);
        return null;
    }

    // 步骤2：通过UserDetailsService查询最新的用户详情（关键：查最新数据）
    UserDetails userDetails;
    try {
        userDetails = userDetailsService.loadUserByUsername(username);
    } catch (UsernameNotFoundException e) {
        cancelCookie(request, response);
        return null;
    }

    // 步骤3：重新生成签名，对比令牌中的签名是否一致（核心验签，防止篡改/伪造）
    String expectedSignature = DigestUtils.md5DigestAsHex(
            (username + ":" + expiryTime + ":" + userDetails.getPassword() + ":" + key).getBytes(StandardCharsets.UTF_8)
    );
    if (!expectedSignature.equals(signature)) {
        cancelCookie(request, response); // 签名不一致，令牌伪造，清理Cookie
        return null;
    }

    // 所有校验通过，返回用户详情
    return userDetails;
}

// 工具方法：清理记住我Cookie（令牌非法/过期时调用）
protected void cancelCookie(HttpServletRequest request, HttpServletResponse response) {
    Cookie cookie = new Cookie(DEFAULT_COOKIE_NAME, null);
    cookie.setPath(cookiePath);
    cookie.setMaxAge(0); // 立即失效
    cookie.setHttpOnly(true);
    cookie.setSecure(request.isSecure());
    response.addCookie(cookie);
}
```
**关键细节**：
- 令牌格式错误、过期、签名不一致，都会**立即清理Cookie**，防止无效令牌残留；
- 必须查询**最新的用户详情**：若用户被删除、密码修改，都会导致验签失败，自动登录失败；
- 返回非`null`的`Authentication`即代表自动登录成功，Spring Security会将该对象存入安全上下文。

#### 3. `loginFail`方法实现：清理无效的记住我Cookie
**触发时机**：用户手动登录失败（账号/密码错误）；
**核心职责**：清理前端的记住我Cookie，防止无效令牌残留；
**源码级简化实现**：
```java
@Override
public void loginFail(HttpServletRequest request, HttpServletResponse response) {
    // 直接调用清理Cookie的方法，让记住我Cookie立即失效
    cancelCookie(request, response);
}
```
**关键细节**：
- 实现极简，仅做**Cookie清理**，因为服务端无持久化令牌，无需做其他操作；
- 目的是防止用户登录失败后，前端仍保留旧的记住我Cookie，导致后续自动登录异常。

---

### 四、`TokenBasedRememberMeServices`的关键特性&局限性
理解它的实现后，就能清楚它的适用场景和短板，这也是为什么生产环境更推荐`PersistentTokenBasedRememberMeServices`（持久化版）的原因：
#### ✅ 核心特性（优点）
1. **轻量无依赖**：无需数据库/Redis等存储，只需配置密钥和`UserDetailsService`，快速集成；
2. **基础安全保障**：令牌加密+签名，防止伪造/篡改；Cookie默认`HttpOnly=true`，防XSS；
3. **密码联动失效**：用户修改密码后，旧令牌因签名校验失败立即失效，无需手动清理；
4. **开箱即用**：Spring Security原生实现，无需自定义任何代码，配置即可使用。

#### ❌ 核心局限性（缺点）
1. **服务端无法主动失效令牌**：因为令牌仅存在客户端Cookie，服务端无存储，即使用户想「退出所有设备」，也无法主动作废令牌，只能等令牌过期；
2. **令牌被盗用风险高**：令牌在有效期内永久有效，一旦Cookie被窃取（如设备盗用），攻击者可一直使用，直到令牌过期或用户修改密码；
3. **无令牌刷新机制**：令牌生成后直到过期都不会变化，不像持久化版那样每次自动登录刷新令牌；
4. **性能损耗**：每次自动登录都需要通过`UserDetailsService`查询用户详情，若查询是数据库操作，会增加数据库访问压力；
5. **不支持多设备管理**：无法查询用户有哪些设备开启了记住我，也无法单独下线某台设备的令牌。

---

### 五、和`PersistentTokenBasedRememberMeServices`的核心实现区别
`PersistentTokenBasedRememberMeServices`是`TokenBasedRememberMeServices`的**进阶扩展版**，继承了它的核心逻辑，但修复了所有局限性，两者的实现核心区别集中在**令牌存储**和**令牌生命周期管理**，对比更清晰：
| 实现类                          | 令牌存储方式       | 令牌刷新 | 主动失效 | 多设备管理 | 核心安全机制               |
|---------------------------------|--------------------|----------|----------|------------|----------------------------|
| `TokenBasedRememberMeServices`  | 仅客户端Cookie（无服务端存储） | 不支持   | 不支持   | 不支持     | 加密签名+密码联动失效       |
| `PersistentTokenBasedRememberMeServices` | 客户端Cookie+服务端持久化（数据库/Redis） | 支持（每次自动登录刷新） | 支持（服务端删令牌） | 支持（服务端查令牌） | 双令牌机制（series+tokenValue）+ 持久化管控 |

**关键**：`PersistentTokenBasedRememberMeServices`继承了`TokenBasedRememberMeServices`的`loginSuccess`、`loginFail`基础逻辑，但重写了`autoLogin`方法，增加了**令牌持久化**和**令牌刷新**的核心逻辑，同时依赖`PersistentTokenRepository`做数据操作。

---

### 总结
`TokenBasedRememberMeServices`对`RememberMeServices`接口的实现，核心可以用3句话概括：
1. **`loginSuccess`**：判断是否勾选记住我 → 按「用户名+过期时间+密码+密钥」生成**加密签名令牌** → 写入带`HttpOnly`的Cookie；
2. **`autoLogin`**：从Cookie解析令牌 → 验签+校验过期+查询最新用户详情 → 合法则构建`Authentication`完成自动登录，非法则清理Cookie；
3. **`loginFail`**：极简实现，仅清理前端记住我Cookie，防止无效令牌残留。

它是**轻量、快速的记住我解决方案**，适合对安全性要求不高、无需主动管控令牌的简单业务（如个人博客、小工具）；但对于企业级业务（如电商、金融、后台管理系统），因**无法主动失效、被盗用风险高**等局限性，**不推荐使用**，而是选择持久化的`PersistentTokenBasedRememberMeServices`。