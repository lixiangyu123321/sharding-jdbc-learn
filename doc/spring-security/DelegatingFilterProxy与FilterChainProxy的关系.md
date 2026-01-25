### 核心结论总结（`DelegatingFilterProxy` + `FilterChainProxy`）
#### 1. 核心定位与层级关系
- `DelegatingFilterProxy`：Spring Web 原生组件，是 Servlet 容器与 Spring 容器的**桥接器**，仅实现「1个实例」注册到 Servlet 容器，核心作用是将请求委托给 Spring 容器中名为 `springSecurityFilterChain` 的 Bean（即 `FilterChainProxy`），不直接处理过滤逻辑。
- `FilterChainProxy`：Spring Security 专属核心组件，**必须实现 `javax.servlet.Filter` 接口**（才能被 `DelegatingFilterProxy` 委托），是 Spring Security 所有过滤器的「总入口」，内部管理多组 `SecurityFilterChain`（Spring Security 自定义接口，非 Servlet Filter），负责按请求 URL 动态匹配并执行对应过滤器链。
- 整体层级：`Servlet 容器 → 1个 DelegatingFilterProxy → 1个 FilterChainProxy → N个 SecurityFilterChain → M个单个 Security Filter`。

#### 2. 关键澄清（纠正核心误解）
- 并非“每个 Security Filter 被 `DelegatingFilterProxy` 代理”：`DelegatingFilterProxy` 仅代理 `FilterChainProxy`，单个 Security Filter 由 `FilterChainProxy` 直接调用，与 `DelegatingFilterProxy` 无直接关联；
- 并非“一组过滤器链对应一个 `DelegatingFilterProxy`”：无论配置多少组 `SecurityFilterChain`，始终只有 1 个 `DelegatingFilterProxy` 和 1 个 `FilterChainProxy`，多链的匹配/执行逻辑全部由 `FilterChainProxy` 内部完成；
- `DelegatingFilterProxy` 仅做“委托”，不实现过滤链逻辑：它仅从 Spring 容器获取 `FilterChainProxy` 并转发 `doFilter` 调用，真正的“动态匹配过滤器链、按序执行过滤器”逻辑都在 `FilterChainProxy` 中。

#### 3. 核心设计价值
- `DelegatingFilterProxy`：解决 Servlet 容器与 Spring 容器的隔离问题，让 Spring 管理的 Filter（如 `FilterChainProxy`）能融入 Servlet 过滤流程，享受 Spring 依赖注入、配置管理等特性；
- `FilterChainProxy`：解决 Spring Security 多过滤器链的有序管理、动态路径匹配问题，避免将数十个 Security Filter 逐个注册到 Servlet 容器，简化配置且保证过滤器执行顺序。