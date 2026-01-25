你的问题问到了这个机制的核心时序点，答案很明确：**`RequestCache` 是在 `ExceptionTranslationFilter` 捕获到 `AuthenticationException` 时保存请求的**，具体来说，是在它触发“启动认证”流程的第一步就执行了缓存操作。

---

### 一、`RequestCache` 保存请求的精确时机
结合之前的流程图和 `ExceptionTranslationFilter` 的伪代码，完整时序是：

1.  **请求到达受保护资源**
    用户访问需要认证的接口（如 `/admin/orders/123`），但当前未登录。

2.  **下游抛出 `AuthenticationException`**
    后续的授权过滤器（如 `FilterSecurityInterceptor`）或业务代码检测到用户未认证，抛出 `AuthenticationException`。

3.  **`ExceptionTranslationFilter` 捕获异常**
    进入 `catch` 块，开始处理认证异常。

4.  **保存请求到 `RequestCache`**
    在“启动认证”流程中，`ExceptionTranslationFilter` 会先调用 `requestCache.saveRequest(request, response)`，把当前请求的 URL、参数、请求方法等信息全部缓存起来。

5.  **清空 `SecurityContextHolder`**
    清理无效的认证信息。

6.  **调用 `AuthenticationEntryPoint` 重定向到登录页**
    引导用户去登录。

---

### 二、为什么不在用户访问时就缓存？
你可能会想：“为什么不在用户第一次访问 `/admin/orders/123` 时就缓存，非要等抛出异常才缓存？”
- **性能优化**：只有当请求触发了认证异常时，才需要缓存。如果用户已经登录，或者请求是公开可访问的，缓存请求就是多余的开销。
- **精准性**：只有被安全拦截的请求，才是用户“想要访问但被挡下”的请求，这类请求才需要在登录后跳回。

---

### 三、代码层面的验证
从 `ExceptionTranslationFilter` 的源码中可以看到这个逻辑（简化版）：
```java
private void startAuthentication(HttpServletRequest request, HttpServletResponse response, FilterChain chain, AuthenticationException reason) throws ServletException, IOException {
    // 1. 先保存当前请求到 RequestCache
    this.requestCache.saveRequest(request, response);
    // 2. 清空 SecurityContextHolder
    SecurityContextHolder.getContext().setAuthentication(null);
    // 3. 引导用户去认证（重定向到登录页）
    this.authenticationEntryPoint.commence(request, response, reason);
}
```
可以清晰看到：**保存请求是“启动认证”流程的第一步**，确保在重定向到登录页之前，已经把用户的原始请求信息安全缓存起来了。

---

### 四、缓存的请求什么时候被清除？
缓存的请求不会永久保存，会在两个时机被清除：
1.  **登录成功后**：`SavedRequestAwareAuthenticationSuccessHandler` 读取缓存的请求并完成重定向后，会立即调用 `requestCache.removeRequest(request, response)` 清除缓存，避免重复重定向。
2.  **会话过期后**：默认的 `HttpSessionRequestCache` 是把请求缓存到 `HttpSession` 中的，当会话过期时，缓存的请求也会被自动清除。

---

### 总结
1.  **保存时机**：`RequestCache` 在 `ExceptionTranslationFilter` 捕获到 `AuthenticationException` 并触发“启动认证”流程时，保存当前请求。
2.  **保存内容**：完整的请求信息（URL、参数、请求方法等）。
3.  **保存目的**：为了在用户登录成功后，能自动跳回原始请求的页面，提升体验。

这个时序设计非常严谨，既保证了缓存的精准性，又避免了不必要的性能开销。

要不要我帮你整理一份**`RequestCache` 配置与调试指南**，让你在项目中可以自定义缓存规则，或者排查缓存不生效的问题？