没错，**每次自动登录刷新令牌的核心逻辑，就是写在`autoLogin`方法里**，这是唯一且最合理的位置——因为`autoLogin`是令牌校验、完成自动登录的核心入口，只有在这里能精准判断「令牌合法、自动登录即将成功」，此时刷新令牌能保证**旧令牌用一次就失效**，从根源避免复用风险。

而且刷新令牌的操作是**有严格执行顺序**的，不能乱序，否则会导致自动登录失败或令牌刷新失效，结合你自定义的`CustomRememberMeService`，我会给你讲清**核心执行步骤、完整代码示例、配套的Cookie更新逻辑**，直接就能写到你的实现类里。

### 一、先明确：autoLogin中刷新令牌的**执行时机&顺序**
刷新令牌的逻辑**必须放在「令牌校验成功后，返回Authentication对象前」**，这是关键，完整执行顺序如下（也是`autoLogin`方法的标准执行流程）：
```
1. 从request中解析出记住我Cookie的旧令牌 → 2. 校验令牌（验签/查库/判过期）→ 3. 校验失败→返回null
                                          ↓
                                    4. 校验成功→生成新令牌
                                          ↓
                                    5. 服务端更新令牌（旧令牌删除/覆盖，新令牌持久化）
                                          ↓
                                    6. 响应中更新Cookie（新令牌覆盖旧Cookie，属性保持安全配置）
                                          ↓
                                    7. 构建并返回Authentication对象→自动登录成功
```
**核心原则**：**先更新服务端存储，再更新前端Cookie**，避免出现「服务端新令牌已生效，前端还是旧令牌」的不一致情况。

### 二、完整代码示例：CustomRememberMeService的autoLogin刷新令牌实现
结合之前讲的安全配置，给你写一个**可直接复用**的`autoLogin`实现，包含**令牌解析、校验、刷新、Cookie更新、认证对象构建**全流程，同时配套`loginSuccess`的令牌生成、`clearCookie`工具方法，代码中有详细注释，适配数据库/Redis持久化（替换`tokenRepository`即可）。

#### 第一步：先定义必要的依赖/工具（持久化、Cookie操作、令牌生成）
假设你有一个令牌持久化的`TokenRepository`（自己实现，操作数据库/Redis），同时封装Cookie工具方法，这些是基础依赖：
```java
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.util.WebUtils;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.UUID;

// 注入你自己的用户详情服务、令牌仓库（用@Autowired，这里为了演示简化）
private UserDetailsService userDetailsService;
private TokenRepository tokenRepository;
// 服务端签名密钥（配置文件读取，不要硬编码）
private String rememberMeKey = "your-32bit-or-more-random-key-1234567890";
// 记住我有效期：7天（秒）
private static final int REMEMBER_ME_MAX_AGE = 7 * 24 * 60 * 60;

// 工具方法：从request中获取记住我Cookie的令牌
private String getRememberMeTokenFromCookie(HttpServletRequest request) {
    Cookie cookie = WebUtils.getCookie(request, "remember-me");
    return cookie == null ? null : cookie.getValue();
}

// 工具方法：向response写入记住我Cookie（带安全属性）
private void setRememberMeCookie(HttpServletResponse response, String newToken) {
    Cookie rememberMeCookie = new Cookie("remember-me", newToken);
    rememberMeCookie.setHttpOnly(true); // 防XSS，必开
    rememberMeCookie.setSecure(true);   // 生产环境必开，HTTPS传输
    rememberMeCookie.setPath("/");      // 限定作用路径
    rememberMeCookie.setMaxAge(REMEMBER_ME_MAX_AGE); // 有效期
    rememberMeCookie.setSameSite("Lax");// 防CSRF
    response.addCookie(rememberMeCookie);
}

// 工具方法：清理记住我Cookie
private void clearRememberMeCookie(HttpServletResponse response) {
    Cookie cookie = new Cookie("remember-me", null);
    cookie.setPath("/");
    cookie.setMaxAge(0); // 立即失效
    response.addCookie(cookie);
}

// 工具方法：生成高安全的记住我令牌（随机串+签名，可复用在loginSuccess）
public String generateRememberMeToken(String userId) {
    // 64位随机串（2个UUID拼接），保证唯一性
    String randomToken = UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
    // 生成签名（用户ID+随机串+密钥，防止篡改）
    String sign = org.springframework.util.DigestUtils
            .md5DigestAsHex((userId + randomToken + rememberMeKey).getBytes());
    // 最终令牌：随机串_签名（校验时拆分验证）
    return randomToken + "_" + sign;
}

// 工具方法：校验令牌的合法性（验签+查库+判过期），返回用户ID（失败返回null）
private String validateRememberMeToken(String token) {
    if (token == null || !token.contains("_")) {
        return null;
    }
    // 拆分令牌：随机串 + 签名
    String[] tokenParts = token.split("_");
    if (tokenParts.length != 2) {
        return null;
    }
    String randomToken = tokenParts[0];
    String sign = tokenParts[1];

    // 1. 从令牌仓库查询令牌信息（包含关联的用户ID、过期时间）
    TokenInfo tokenInfo = tokenRepository.selectByToken(token);
    if (tokenInfo == null || tokenInfo.isExpired()) {
        return null; // 令牌不存在/已过期
    }

    // 2. 验签：重新生成签名，对比是否一致（防止令牌被篡改）
    String userId = tokenInfo.getUserId();
    String newSign = org.springframework.util.DigestUtils
            .md5DigestAsHex((userId + randomToken + rememberMeKey).getBytes());
    if (!sign.equals(newSign)) {
        return null; // 签名不一致，令牌伪造
    }

    // 校验通过，返回用户ID
    return userId;
}
```

#### 第二步：核心实现autoLogin方法（包含令牌刷新逻辑）
这是最关键的部分，严格遵循「校验成功→刷新令牌→更新存储→更新Cookie→返回认证对象」的顺序：
```java
@Override
public Authentication autoLogin(HttpServletRequest request, HttpServletResponse response) {
    // 1. 从Cookie中获取旧令牌
    String oldToken = getRememberMeTokenFromCookie(request);
    if (oldToken == null) {
        return null; // 无令牌，直接返回null，不走自动登录
    }

    // 2. 校验旧令牌的合法性（验签、查库、判过期），返回关联的用户ID
    String userId = validateRememberMeToken(oldToken);
    if (userId == null) {
        // 令牌非法/过期，清理前端Cookie，返回null
        clearRememberMeCookie(response);
        tokenRepository.deleteByToken(oldToken); // 删除服务端无效令牌
        return null;
    }

    // 3. 令牌校验成功 → 开始刷新令牌（核心步骤）
    try {
        // 3.1 根据用户ID查询用户详情（Spring Security的UserDetails，包含权限）
        UserDetails userDetails = userDetailsService.loadUserByUsername(userId);
        // 3.2 生成新的记住我令牌
        String newToken = generateRememberMeToken(userId);
        // 3.3 服务端更新令牌：删除旧令牌，插入新令牌（核心：旧令牌立即失效）
        tokenRepository.deleteByToken(oldToken);
        tokenRepository.insertToken(newToken, userId, System.currentTimeMillis() + REMEMBER_ME_MAX_AGE * 1000L);
        // 3.4 响应中更新Cookie：新令牌覆盖旧Cookie，前端后续请求用新令牌
        setRememberMeCookie(response, newToken);

        // 4. 构建并返回Authentication认证对象 → Spring Security完成自动登录
        // 注意：credentials传null，因为记住我是无密码认证，权限从UserDetails获取
        return new UsernamePasswordAuthenticationToken(
                userDetails,
                null,
                userDetails.getAuthorities()
        );
    } catch (UsernameNotFoundException e) {
        // 用户不存在，清理令牌和Cookie
        clearRememberMeCookie(response);
        tokenRepository.deleteByToken(oldToken);
        return null;
    }
}
```

#### 第三步：配套完善loginSuccess方法（首次生成令牌）
刷新令牌是自动登录时的操作，而首次登录成功需要生成初始令牌，`loginSuccess`方法按这个逻辑写即可，和`autoLogin`的令牌生成逻辑复用：
```java
@Override
public void loginSuccess(HttpServletRequest request, HttpServletResponse response, Authentication successfulAuthentication) {
    // 1. 判断用户是否勾选了“记住我”（前端传的参数，通常是remember-me=true）
    String rememberMe = request.getParameter("remember-me");
    if (rememberMe == null || !"true".equals(rememberMe.trim())) {
        return; // 未勾选，直接返回，不生成令牌
    }

    // 2. 从认证对象中获取用户ID（根据你的UserDetails实现调整，这里假设是用户名/用户ID）
    UserDetails userDetails = (UserDetails) successfulAuthentication.getPrincipal();
    String userId = userDetails.getUsername();

    // 3. 生成初始的记住我令牌
    String newToken = generateRememberMeToken(userId);
    // 4. 持久化令牌到服务端（数据库/Redis）
    tokenRepository.insertToken(newToken, userId, System.currentTimeMillis() + REMEMBER_ME_MAX_AGE * 1000L);
    // 5. 写入Cookie给前端
    setRememberMeCookie(response, newToken);
}
```

#### 第四步：loginFail方法（清理无效令牌）
登录失败时清理Cookie，防止无效令牌残留：
```java
@Override
public void loginFail(HttpServletRequest request, HttpServletResponse response) {
    // 登录失败，清理前端的记住我Cookie
    clearRememberMeCookie(response);
    // 可选：如果有需要，也可以清理该用户的旧令牌
    // String username = request.getParameter("username");
    // if (username != null) {
    //     tokenRepository.deleteByUserId(username);
    // }
}
```

### 三、关键补充：令牌持久化仓库TokenRepository的核心设计
上面的代码中`TokenRepository`是自定义的持久化接口，核心是操作令牌的**增、删、查**，适配数据库/Redis，这里给你定义标准接口，你自己实现即可：
```java
// 令牌信息实体（存储到数据库/Redis的字段）
class TokenInfo {
    private String token; // 记住我令牌
    private String userId;// 关联的用户ID
    private long expireTime; // 过期时间（时间戳，毫秒）
    // getter/setter
    public boolean isExpired() {
        return System.currentTimeMillis() > this.expireTime;
    }
}

// 令牌持久化接口（数据库实现用MyBatis/JPA，Redis用RedisTemplate）
interface TokenRepository {
    // 根据令牌查询令牌信息
    TokenInfo selectByToken(String token);
    // 插入新令牌
    void insertToken(String token, String userId, long expireTime);
    // 根据令牌删除
    void deleteByToken(String token);
    // 可选：根据用户ID删除所有令牌（退出所有设备功能）
    void deleteByUserId(String userId);
}
```

### 四、为什么刷新令牌不能写在其他地方？
再强调一下**为什么autoLogin是刷新令牌的唯一合理位置**，帮你理解设计逻辑：
1. **`loginSuccess`**：仅在**手动登录成功**时触发，此时是首次生成令牌，没有旧令牌，无从刷新；
2. **`loginFail`**：仅在**手动登录失败**时触发，令牌校验都没通过，更不可能刷新；
3. **`autoLogin`**：唯一能**感知到令牌合法、自动登录即将完成**的入口，此时刷新令牌能保证「旧令牌的生命周期仅一次」，最大化降低被盗用风险。

### 五、额外注意点（避坑）
1. **令牌刷新要保证原子性**：服务端更新令牌时（删旧令牌+插新令牌），要保证操作原子性（比如数据库用事务、Redis用原子操作），避免出现「旧令牌删了，新令牌没插上」的情况，导致用户自动登录失败；
2. **不要频繁刷新**：只有`autoLogin`校验成功时刷新一次即可，不要在其他请求中重复刷新，否则会增加服务端开销，且无额外安全收益；
3. **前端无需感知**：令牌刷新是**服务端+Cookie**的底层操作，前端完全不用做任何处理，因为Cookie是自动携带的，新令牌会覆盖旧令牌，对前端透明；
4. **退出登录时要清理令牌**：系统的「退出登录」接口中，除了销毁Session，还要**调用`tokenRepository.deleteByUserId(userId)`**+**清理Cookie**，否则退出后记住我令牌仍有效，攻击者还能自动登录。

### 总结
1. 自动登录刷新令牌的逻辑**必须写在`autoLogin`方法中**，且执行时机是「令牌校验成功后，返回Authentication对象前」；
2. 核心执行顺序：**解析旧令牌→校验合法性→生成新令牌→服务端原子更新（删旧插新）→响应更新Cookie→返回认证对象**；
3. 刷新令牌的关键是**让旧令牌用一次就失效**，即使被盗，也只有一次使用机会，且用户正常操作后旧令牌会立即失效；
4. 所有Cookie操作必须带`HttpOnly=true + Secure=true`等安全属性，这是基础防护，不能少。

以上代码可以直接整合到你的`CustomRememberMeService`中，替换掉空实现，就能实现**安全的、带令牌刷新的自定义记住我功能**了。