# 同步令牌（Synchronizer Token）模式
同步令牌模式是**Web 开发中防御跨站请求伪造（CSRF/XSRF）攻击**的核心解决方案之一，核心思想是**为每个用户会话生成唯一的随机令牌，将令牌嵌入请求中，服务端验证请求中的令牌与会话中存储的令牌一致性**，只有验证通过才处理请求，从根本上阻止攻击者伪造的跨域请求。

### 核心原理
CSRF 攻击的本质是：攻击者利用用户已登录的**会话凭证（如 Cookie）** ，在用户不知情的情况下，通过第三方网站向目标服务器发送伪造请求（浏览器会自动携带目标域名的 Cookie）。
而同步令牌模式的关键是**CSRF 攻击者无法获取到目标服务器为用户生成的唯一令牌**，因此伪造的请求中必然缺少有效令牌，会被服务端拒绝。

### 同步令牌模式的核心步骤
整个流程围绕**令牌生成-令牌传递-令牌验证-令牌销毁**展开，且令牌与用户**会话（Session）** 强绑定，每个用户的令牌唯一，同一用户的令牌也可按需刷新。
1. **令牌生成**：用户登录成功/创建新会话时，服务端生成一个**高强度随机字符串（令牌）**，例如通过`UUID+随机数+时间戳`生成，将令牌**存储在服务端的 Session 中**（核心：服务端持久化令牌）。
2. **令牌传递**：服务端将生成的令牌以**隐藏表单域、请求头、URL 参数（不推荐）** 等方式，嵌入到需要防御 CSRF 的页面/请求中（如表单提交、AJAX 请求）。
    - 最常用：**HTML 表单的隐藏 input 域**（如`<input type="hidden" name="csrfToken" value="xxx">`），表单提交时自动携带；
    - 前后端分离场景：**自定义请求头**（如`X-Csrf-Token: xxx`），AJAX/Fetch 请求手动设置。
3. **令牌验证**：用户发起请求时，请求会携带令牌（表单/请求头/参数），服务端接收到请求后：
    - 从 Session 中获取当前用户的有效令牌；
    - 从请求中提取携带的令牌；
    - 对比两个令牌的**一致性**（包括是否存在、是否相等）。
4. **请求处理/拒绝**：
    - 验证通过：正常处理业务请求，可按需**刷新令牌**（提升安全性）；
    - 验证失败：直接拒绝请求（返回 403 Forbidden），不处理任何业务逻辑。
5. **令牌销毁**：用户**登出会话**/**会话过期**时，服务端销毁 Session 中存储的令牌，确保令牌随会话失效。

### 关键设计要求
为保证令牌的安全性，避免被攻击者窃取或破解，设计时需满足以下要求：
1. **令牌唯一性**：每个用户会话的令牌必须唯一，禁止多个用户共享同一令牌；
2. **高强度随机性**：令牌需通过密码学安全的随机算法生成（如 Java 的`SecureRandom`、Python 的`secrets`模块），避免使用简单的自增数、时间戳等可预测的字符串；
3. **令牌非持久化在客户端**：令牌仅临时嵌入请求/页面，**禁止将令牌存储在客户端 Cookie 中**（否则会被 CSRF 攻击利用，浏览器自动携带）；
4. **一次有效/定时刷新**（可选）：可设置令牌**一次请求有效**（使用后立即生成新令牌）或**定时刷新**，降低令牌被窃取后的滥用风险；
5. **仅在敏感请求中校验**：对**增删改**等敏感业务请求强制校验令牌，查询类请求可按需忽略（减少服务端性能开销）。

### 典型实现示例
#### 1. 传统服务端渲染（如 Spring Boot + Thymeleaf）
- 服务端生成令牌并存入 Session：
  ```java
  @GetMapping("/edit")
  public String editPage(HttpSession session) {
      // 生成CSRF令牌
      String csrfToken = UUID.randomUUID().toString().replace("-", "");
      session.setAttribute("CSRF_TOKEN", csrfToken);
      // 将令牌传入页面
      model.addAttribute("csrfToken", csrfToken);
      return "edit";
  }
  ```
- 页面通过隐藏表单域携带令牌：
  ```html
  <form action="/submit" method="post">
      <input type="text" name="username" />
      <!-- 隐藏CSRF令牌 -->
      <input type="hidden" name="csrfToken" th:value="${csrfToken}" />
      <button type="submit">提交</button>
  </form>
  ```
- 服务端验证令牌：
  ```java
  @PostMapping("/submit")
  public String submit(HttpSession session, @RequestParam String csrfToken) {
      // 从Session获取令牌
      String serverToken = (String) session.getAttribute("CSRF_TOKEN");
      // 验证令牌
      if (serverToken == null || !serverToken.equals(csrfToken)) {
          throw new AccessDeniedException("CSRF验证失败");
      }
      // 处理业务
      return "success";
  }
  ```

#### 2. 前后端分离（如 Vue + Spring Boot）
- 服务端生成令牌，通过**响应头/接口返回**给前端，并存入 Session：
  ```java
  @GetMapping("/getCsrfToken")
  public void getCsrfToken(HttpSession session, HttpServletResponse response) {
      String csrfToken = UUID.randomUUID().toString().replace("-", "");
      session.setAttribute("CSRF_TOKEN", csrfToken);
      // 自定义响应头返回令牌
      response.setHeader("X-Csrf-Token", csrfToken);
  }
  ```
- 前端获取令牌后，存入**本地存储（localStorage/sessionStorage）**，AJAX 请求时通过**自定义请求头**携带：
  ```javascript
  // 获取令牌
  fetch('/getCsrfToken')
      .then(res => {
          const csrfToken = res.headers.get('X-Csrf-Token');
          sessionStorage.setItem('csrfToken', csrfToken);
      });
  // 发起请求时携带令牌
  fetch('/api/submit', {
      method: 'POST',
      headers: {
          'Content-Type': 'application/json',
          'X-Csrf-Token': sessionStorage.getItem('csrfToken') // 自定义请求头
      },
      body: JSON.stringify({ username: 'test' })
  });
  ```
- 服务端从请求头提取令牌并验证：
  ```java
  @PostMapping("/api/submit")
  public Result submit(HttpSession session, @RequestHeader("X-Csrf-Token") String csrfToken) {
      String serverToken = (String) session.getAttribute("CSRF_TOKEN");
      if (serverToken == null || !serverToken.equals(csrfToken)) {
          return Result.fail(403, "CSRF验证失败");
      }
      return Result.success("处理成功");
  }
  ```

### 优缺点分析
#### 优点
1. **防御效果彻底**：是 CSRF 防御的**标准方案**，能防御绝大多数 CSRF 攻击，包括常规的表单伪造、AJAX 伪造请求；
2. **兼容性好**：支持所有 Web 开发场景，无论是传统服务端渲染还是前后端分离，均可灵活实现；
3. **可控性高**：令牌的生成、刷新、销毁规则可由服务端自定义，能根据业务安全需求调整；
4. **无跨域问题**：令牌通过请求体/请求头传递，不受浏览器跨域 Cookie 策略的影响。

#### 缺点
1. **开发成本略高**：需要对所有敏感请求做**令牌生成、传递、验证**的改造，尤其是老项目迁移；
2. **对无状态架构不友好**：传统实现依赖服务端**Session**，如果项目是分布式无状态架构（如微服务），需要实现**Session 共享**（如 Redis 存储令牌），增加架构复杂度；
3. **无法防御XSS攻击**：如果项目存在 XSS 漏洞，攻击者可通过 XSS 脚本窃取页面中的令牌，进而伪造有效请求（因此**同步令牌模式需与XSS防御结合使用**）。

### 适用场景
同步令牌模式是**通用性最强**的 CSRF 防御方案，适用于几乎所有需要防御 CSRF 的 Web 场景，尤其是：
1. 有**用户登录态**的系统（如电商、后台管理系统、金融平台）；
2. 包含**增删改**等敏感操作的请求；
3. 前后端分离/微服务架构（需配合分布式缓存存储令牌）。

### 与其他CSRF防御方案的对比
| 防御方案         | 核心思想                     | 优点                     | 缺点                     | 适用场景                 |
|------------------|------------------------------|--------------------------|--------------------------|--------------------------|
| 同步令牌模式     | 会话绑定唯一令牌，服务端校验 | 防御彻底，兼容性好       | 依赖Session，开发成本高  | 绝大多数Web系统（首选）|
| Referer校验      | 验证请求的来源域名           | 实现简单，无侵入         | Referer可被伪造/屏蔽，兼容性差 | 低安全要求的系统         |
| SameSite Cookie  | 限制Cookie的跨域携带         | 无业务改造，实现简单     | 浏览器兼容性有限，防御不彻底 | 辅助防御，配合令牌模式   |

### 最佳实践
1. **令牌+SameSite Cookie 结合**：同步令牌模式作为核心防御，SameSite Cookie 作为辅助，双重保障；
2. **分布式架构用Redis存储令牌**：替代传统Session，将令牌与用户ID绑定存入Redis，设置过期时间，实现分布式令牌验证；
3. **令牌一次有效**：敏感操作（如支付、修改密码）的令牌使用后立即销毁并生成新令牌，降低窃取风险；
4. **配合XSS防御**：开启CSP策略、对用户输入做转义，避免XSS漏洞导致令牌被窃取；
5. **统一拦截器校验**：在服务端编写**全局CSRF拦截器**，对所有敏感请求统一做令牌验证，避免重复代码；
6. **避免令牌出现在URL中**：URL参数会被记录在浏览器历史、服务器日志中，容易泄露，优先使用隐藏表单域/请求头。

我可以帮你编写**分布式架构下（Redis+Spring Boot）的通用CSRF令牌拦截器代码**，直接集成到项目中实现全局校验，需要吗？