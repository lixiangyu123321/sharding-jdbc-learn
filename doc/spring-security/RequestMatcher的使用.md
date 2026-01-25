你这个问题问到了核心点——**Servlet 原生 Filter 能获取 `HttpServletRequest`，但“匹配是否执行 Filter”和“Filter 内部判断请求”是两个完全不同的阶段**，这也是 `FilterChainProxy` 灵活性的关键所在。下面我会拆解这个核心区别，让你清楚“为什么原生 Filter 做不到”。

### 核心区别解析：Servlet 原生 Filter vs FilterChainProxy
**将匹配阶段与过滤阶段拆分**

### 一、先明确两个核心阶段的差异
无论是 Servlet 原生 Filter 还是 `FilterChainProxy`，处理请求都分两个阶段：

| 阶段                | 作用                                                                 | 执行主体                |
|---------------------|----------------------------------------------------------------------|-------------------------|
| **匹配阶段**        | 判断“是否要让这个 Filter/过滤器链 参与本次请求的处理”                 | Servlet 容器 / FilterChainProxy |
| **执行阶段**        | 一旦确定要参与，就处理请求（如校验、授权），可读取 `HttpServletRequest` | 单个 Filter / 过滤器链  |

`FilterChainProxy` 的灵活度，体现在**匹配阶段**；而 Servlet 原生 Filter 的局限，也恰恰在**匹配阶段**——这是关键。

### 二、为什么 Servlet 原生 Filter “做不到”？
#### 1. 原生 Filter 的“匹配阶段”被 Servlet 容器锁死，只能基于 URL
Servlet 容器（Tomcat/Jetty）在启动时，会根据你注册 Filter 时指定的 `url-pattern`（如 `/*`、`/api/**`），把 Filter 绑定到固定的 URL 规则上。

**匹配阶段逻辑**：
当请求到来时，Servlet 容器只做一件事——判断请求 URL 是否匹配 Filter 的 `url-pattern`：
- 如果匹配 → 强制执行这个 Filter 的 `doFilter` 方法（进入执行阶段）；
- 如果不匹配 → 完全跳过这个 Filter，连 `doFilter` 都不会调用。

也就是说：**原生 Filter 没有“自定义匹配是否执行”的机会——匹配规则由 Servlet 容器控制，且仅支持 URL 路径**。

#### 2. 原生 Filter 能读取 `HttpServletRequest`，但只能在“执行阶段”读
你说的“原生 Filter 能获取 `HttpServletRequest`”是对的，但这个操作只能在 `doFilter` 方法内（执行阶段）做，而非匹配阶段。

举个反面例子：你想让原生 Filter 只处理“POST + /api/ + X-API-Key 头”的请求，只能这么写：
```java
// Servlet 原生 Filter（匹配阶段只能设 url-pattern="/api/*"）
public class MyNativeFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        // 执行阶段才做判断：检查请求方法和请求头
        boolean isPost = "POST".equals(req.getMethod());
        boolean hasApiKey = req.getHeader("X-API-Key") != null;
        
        // 问题核心：即使不满足条件，Filter 也已经被调用了，只能手动放行
        if (isPost && hasApiKey) {
            // 处理逻辑
            chain.doFilter(request, response);
        } else {
            // 不满足条件，直接放行（但 Filter 已经执行了一次）
            chain.doFilter(request, response);
        }
    }
}
```
**核心问题**：
- 哪怕请求是 GET、没有 X-API-Key 头，这个 Filter 也会被 Servlet 容器调用（因为 URL 匹配 `/api/*`）；
- 你只能在 `doFilter` 里“手动跳过”，但 Filter 本身已经参与了请求流程（执行了 `doFilter` 方法）——这不是“匹配阶段跳过”，而是“执行阶段放弃处理”，本质是“无效执行”。

#### 3. 对比 `FilterChainProxy` 的匹配逻辑（核心优势）
`FilterChainProxy` 把“匹配阶段”从 Servlet 容器接管到了 Spring 内部：
- **匹配阶段**：`FilterChainProxy` 先拿到完整的 `HttpServletRequest`，通过 `RequestMatcher` 做任意维度的判断（方法、请求头、IP 等），只有匹配成功，才会执行对应的 `SecurityFilterChain`；
- **执行阶段**：匹配成功后，才会调用链中的 Filter 处理请求；
- **未匹配**：直接跳过整个过滤器链，链中的 Filter 完全不会被调用（真正的“不执行”，而非“执行后放行”）。

用同一个需求对比 `FilterChainProxy` 的逻辑：
```java
// FilterChainProxy 管理的过滤器链（匹配阶段直接过滤）
@Bean
public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
    http
        // 匹配阶段：直接基于 RequestMatcher 判断，不满足则整个链都不执行
        .securityMatcher(request -> {
            boolean pathMatch = request.getRequestURI().startsWith("/api/");
            boolean methodMatch = "POST".equals(request.getMethod());
            boolean headerMatch = request.getHeader("X-API-Key") != null;
            return pathMatch && methodMatch && headerMatch;
        })
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
    return http.build();
}
```
**核心优势**：
- 如果请求是 GET、没有 X-API-Key 头，`FilterChainProxy` 在匹配阶段就判定“不匹配”，这套过滤器链里的所有 Filter 都不会被调用（真正的跳过）；
- 没有“无效执行”的开销，也不需要在每个 Filter 里写重复的判断逻辑。

### 三、总结：核心区别（一句话说透）
- **Servlet 原生 Filter**：匹配阶段由 Servlet 容器控制，仅支持 URL 规则，只要 URL 匹配，Filter 就必须执行（哪怕你在内部跳过）；
- **FilterChainProxy**：接管了匹配阶段，基于 `RequestMatcher` 可对 `HttpServletRequest` 做任意维度判断，不满足条件则整个过滤器链“完全不执行”，而非“执行后放行”。

### 关键点回顾
1. 原生 Filter 能读取 `HttpServletRequest`，但仅限**执行阶段**，无法在**匹配阶段**用这些信息决定是否执行 Filter；
2. `FilterChainProxy` 的核心灵活度，是把“是否执行过滤器链”的判断从 Servlet 容器的 URL 匹配，升级为 Spring 内部基于 `HttpServletRequest` 全维度的匹配；
3. 原生 Filter 的“手动判断跳过”是“治标不治本”——Filter 已经被调用，有性能开销；而 `FilterChainProxy` 的匹配是“治本”——不满足条件则过滤器链完全不执行。

简单类比：
- 原生 Filter 像“小区大门只看地址（URL）就让进，进门后保安再查证件（请求头/方法），没证再让你走”；
- `FilterChainProxy` 像“小区大门直接查地址+证件+行程码（全维度匹配），不满足直接不让进”——后者更高效、更灵活。