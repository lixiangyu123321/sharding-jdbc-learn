你这段代码是 Spring Security 中配置「表单登录」的核心片段，重点是指定登录页地址，并显式配置登录成功后的处理器——`SavedRequestAwareAuthenticationSuccessHandler` 正是实现“自动重定向到用户原本想访问的页面”的关键，下面我逐行拆解含义、作用和背后的逻辑：

### 一、逐行解析代码含义
```java
.formLogin(form -> form
    // 1. 指定自定义的登录页地址（前端访问的 URL）
    .loginPage("/login")
    // 2. 指定登录成功后的处理器：默认从 RequestCache 读取缓存的请求并自动重定向
    .successHandler(new SavedRequestAwareAuthenticationSuccessHandler())
);
```

#### 1. `.loginPage("/login")`：指定登录页的访问路径
- **核心作用**：
  告诉 Spring Security：当用户需要认证时（比如访问 `/admin` 未登录），`AuthenticationEntryPoint` 会重定向到 `/login` 这个 URL（也就是你的登录页面）；
  同时，Spring Security 会自动处理 `/login` 这个地址的**登录提交请求**（默认是 POST 方法的 `/login`），接收用户名/密码参数完成认证。
- **如果不配置**：Spring Security 会使用默认的登录页（框架内置的简单登录表单），路径也是 `/login`，但样式和功能固定，实际项目中都会自定义。
- **通俗例子**：
  就像你去商场 VIP 区被保安拦住，保安告诉你“去 1 楼服务台（`/login`）办会员卡”——`loginPage("/login")` 就是指定“服务台的地址”。

#### 2. `.successHandler(new SavedRequestAwareAuthenticationSuccessHandler())`：配置登录成功后的处理器
- **核心作用**：
  这是实现“自动重定向到原请求页面”的核心——`SavedRequestAwareAuthenticationSuccessHandler` 是 Spring Security 提供的默认登录成功处理器，它的逻辑是：
  ① 先从 `RequestCache` 中查找是否有“用户未认证时想访问的请求”（比如 `/admin`）；
  ② 如果有，就重定向到这个缓存的 URL；
  ③ 如果没有（比如用户直接访问 `/login` 登录），就重定向到默认页面（默认是 `/` 根路径）。
- **为什么要显式配置**：
  其实 Spring Security 配置 `.formLogin()` 时，默认就会使用这个处理器，显式写出来是为了清晰（也方便后续自定义）；如果想修改重定向逻辑，也可以基于这个类扩展。

### 二、完整的登录流程（结合 `RequestCache`）
用一个实际场景串联代码的作用：
1. 用户未登录，直接访问 `/admin` → 触发 `AuthenticationException`；
2. `ExceptionTranslationFilter` 捕获异常，通过 `RequestCache` 缓存 `/admin` 这个请求，然后调用 `AuthenticationEntryPoint` 重定向到 `/login`（由 `loginPage("/login")` 指定）；
3. 用户在 `/login` 页面输入账号密码，提交登录请求（POST `/login`）；
4. Spring Security 验证账号密码成功后，调用 `SavedRequestAwareAuthenticationSuccessHandler`；
5. 该处理器从 `RequestCache` 中读取到缓存的 `/admin` 请求，自动重定向到 `/admin`；
6. 如果用户直接访问 `/login` 登录（没有缓存的请求），则重定向到根路径 `/`。

### 三、关键扩展（自定义重定向逻辑）
如果默认的重定向逻辑不满足需求（比如想固定重定向到 `/home`，或根据用户角色重定向），可以自定义 `SavedRequestAwareAuthenticationSuccessHandler`：
```java
.formLogin(form -> form
    .loginPage("/login")
    .successHandler(new SavedRequestAwareAuthenticationSuccessHandler() {
        @Override
        public void onAuthenticationSuccess(HttpServletRequest request, 
                                            HttpServletResponse response, 
                                            Authentication authentication) throws IOException, ServletException {
            // 1. 先获取缓存的请求（保留默认逻辑）
            SavedRequest savedRequest = requestCache.getRequest(request, response);
            if (savedRequest != null) {
                // 有缓存请求，按默认逻辑重定向
                super.onAuthenticationSuccess(request, response, authentication);
                return;
            }
            // 2. 无缓存请求时，自定义重定向（比如跳转到 /home）
            response.sendRedirect("/home");
        }
    })
);
```

### 四、对比：关闭自动重定向的写法
如果是前后端分离的 API 接口，不需要页面重定向，而是返回 JSON 响应，可以替换成自定义的成功处理器：
```java
.formLogin(form -> form
    .loginPage("/login") // 对 API 来说，这里可改为返回 401 的接口
    .successHandler((request, response, auth) -> {
        // 登录成功后返回 JSON 响应
        response.setContentType("application/json;charset=utf-8");
        response.getWriter().write("{\"code\":200,\"msg\":\"登录成功\"}");
    })
);
```

### 总结
1. **核心作用**：
    - `.loginPage("/login")`：指定登录页的访问路径，是用户未认证时的重定向目标；
    - `.successHandler(new SavedRequestAwareAuthenticationSuccessHandler())`：配置登录成功处理器，核心是从 `RequestCache` 读取缓存请求，实现“自动跳回原页面”；
2. **关键逻辑**：
   这两行配置配合 `ExceptionTranslationFilter` 和 `RequestCache`，完成了“未认证→引导登录→登录成功跳回原页面”的完整用户体验流程；
3. **灵活性**：
   可通过自定义 `SavedRequestAwareAuthenticationSuccessHandler` 修改重定向逻辑，适配不同场景（页面应用/API 接口）。

这段代码是 Spring Security 表单登录的基础配置，也是“用户体验优化”的核心体现——既保证了安全认证，又让用户不用手动重新输入想访问的页面。