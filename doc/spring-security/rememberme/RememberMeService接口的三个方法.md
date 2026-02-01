你实现的是Spring Security中的`RememberMeServices`接口，这三个方法是**记住我（Remember-Me）功能**的核心回调方法，分别对应记住我流程中**自动登录、登录失败、登录成功**三个关键场景，Spring Security会在对应时机自动调用这些方法，你可以在方法内实现自定义的记住我逻辑（比如生成令牌、校验令牌、清理令牌等）。

下面逐个讲清每个方法的**触发时机**和**核心功能职责**，结合记住我的实际使用场景理解会更直观：

### 1. `autoLogin(HttpServletRequest request, HttpServletResponse response)`
#### 核心功能：**自动登录的核心校验与认证**
#### 触发时机：
用户**未携带有效登录凭证**（如没有Session、没有JWT），但访问了需要认证的接口时，Spring Security会调用这个方法，尝试从请求中提取**记住我的令牌**（比如Cookie、请求参数、Header中的令牌）。
#### 方法职责：
1. 从`request`中解析出记住我的令牌（比如读取前端传来的`remember-me` Cookie）；
2. 校验令牌的合法性（比如令牌是否过期、签名是否正确、是否与数据库/缓存中的令牌匹配）；
3. 如果令牌合法，根据令牌关联的用户信息，构建并返回**有效的`Authentication`认证对象**，Spring Security会基于这个对象完成自动登录；
4. 如果令牌非法/不存在/过期，直接返回`null`，Spring Security会继续走正常的认证流程（比如跳转到登录页、返回未认证）。
#### 关键说明：
返回值`Authentication`是Spring Security的核心认证对象，包含用户信息、权限等，返回非`null`即代表自动登录成功。

### 2. `loginFail(HttpServletRequest request, HttpServletResponse response)`
#### 核心功能：**登录失败时，清理/作废记住我的相关凭证**
#### 触发时机：
用户在**手动登录**时（输入账号密码提交登录），认证失败的瞬间（比如密码错误、账号不存在），Spring Security会调用这个方法。
#### 方法职责：
1. 清理请求/响应中与记住我相关的无效凭证（比如删除前端的`remember-me` Cookie、作废缓存/数据库中已存在的旧令牌）；
2. 防止无效的记住我令牌残留，避免后续自动登录时出现异常；
3. 该方法无返回值，仅做**清理/失效**操作即可。

### 3. `loginSuccess(HttpServletRequest request, HttpServletResponse response, Authentication successfulAuthentication)`
#### 核心功能：**登录成功时，生成并下发记住我的凭证**
#### 触发时机：
用户**手动登录成功**（账号密码验证通过），且前端提交了**记住我标识**（比如请求参数`remember-me=true`、复选框勾选）时，Spring Security会调用这个方法。
#### 方法职责：
1. 先判断用户是否勾选了“记住我”（从`request`中提取标识，比如`request.getParameter("remember-me")`）；
2. 如果勾选，**生成唯一的记住我令牌**（建议随机字符串+签名，防止伪造）；
3. 将令牌**持久化存储**（比如存数据库、Redis，关联用户ID、过期时间）；
4. 将令牌**下发给前端**（通常通过`Cookie`写入响应，比如设置`remember-me` Cookie，指定过期时间、路径）；
5. 该方法无返回值，核心是**生成令牌+持久化+前端下发**。

---

### 补充：三个方法的执行流程（完整的记住我业务链路）
结合实际使用场景，这三个方法的调用顺序和触发条件如下，帮你建立整体认知：
1. **首次手动登录**：用户输账号密码+勾选“记住我”→登录成功→触发`loginSuccess`→生成令牌+存库+写Cookie给前端；
2. **后续访问系统**：用户关闭浏览器后再次访问→无有效Session→Spring Security调用`autoLogin`→解析Cookie中的令牌→校验合法→返回Authentication→自动登录成功；
3. **手动登录失败**：用户输错密码→触发`loginFail`→清理前端无效Cookie/作废旧令牌→避免残留凭证干扰；
4. **自动登录失败**：令牌过期/伪造/用户注销→`autoLogin`返回`null`→跳转到登录页，走正常手动登录流程。

### 总结
`RememberMeServices`的三个方法是Spring Security记住我功能的**定制化入口**，核心职责可一句话概括：
- `autoLogin`：**验令牌、做自动登录**（核心方法，决定自动登录是否生效）；
- `loginSuccess`：**生令牌、下发并持久化**（登录成功时的凭证发放）；
- `loginFail`：**清令牌、做失效处理**（登录失败时的凭证清理）。

你可以在这三个方法内实现自定义的令牌生成规则、存储方式、校验逻辑（比如替换Spring Security默认的简单令牌，实现更安全的持久化记住我）。