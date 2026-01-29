你这个问题问到了Spring Security CSRF防护的**初始化核心环节**——服务端**初次生成CSRF令牌**后，传递给前端的方式和「开发场景（服务端渲染/前后端分离）」强绑定，且**所有传递动作都是框架自动触发的**（无需手动调用接口/写代码），核心触发时机是**用户首次发起「需认证/需渲染页面」的请求时**（比如访问登录页、首次调用接口）。

简单说：**Spring Security会在令牌初次生成后，根据你配置的「令牌仓库（CsrfTokenRepository）」，自动把令牌传递给前端**——不同的令牌仓库对应不同的传递方式，这也是框架封装的核心，我们只需通过配置选择仓库，无需关心底层传递细节。

下面先讲**Spring Security CSRF令牌的「初次生成时机」**（先明确什么时候生成，再讲怎么传递），再分**服务端渲染、前后端分离**两大核心场景，讲清框架默认/推荐的传递方式，结合配置代码，把底层逻辑讲透。

### 前置核心：Spring Security CSRF令牌的「初次生成时机」
CSRF令牌是**「用户会话级」**的令牌，**每个用户的每个会话（Session）对应唯一的一个初始令牌**，框架会在**「用户会话创建后，首次需要使用CSRF令牌时」** 自动生成，触发时机主要有2种（无需手动干预）：
1. **服务端渲染场景**：用户**首次访问任意服务端渲染页面**（如Thymeleaf/JSP的登录页、转账页）时，框架检测到Session已创建且无CSRF令牌，自动生成令牌并完成传递；
2. **前后端分离场景**：用户**首次发起任意需校验的请求**（如登录请求、匿名的令牌获取接口）时，框架检测到Session已创建且无CSRF令牌，自动生成令牌并按配置的仓库规则传递（如写入Cookie）。

**关键细节**：令牌生成后会立即存入「令牌仓库」（默认是HttpSession），后续所有请求的校验，都是从仓库中读取「预期令牌」，传递给前端的只是「令牌副本」，核心控制权始终在服务端。

---

## 核心基础：Spring Security的「CsrfTokenRepository」—— 令牌存储+传递的核心
Spring Security中，**令牌的「存储位置」和「传递方式」，由`CsrfTokenRepository`（CSRF令牌仓库）统一决定**——这是一个接口，框架提供了3个默认实现，分别对应不同的传递方式，**初次生成令牌后，框架会通过该接口的方法，自动完成令牌传递**。

3个默认实现（覆盖99%的开发场景）：
| 仓库实现类                | 存储位置       | 传递方式                     | 适用场景               |
|---------------------------|----------------|------------------------------|------------------------|
| `HttpSessionCsrfTokenRepository`（默认） | 本地HttpSession | 模板引擎自动注入（Thymeleaf） | 服务端渲染项目         |
| `CookieCsrfTokenRepository`           | 本地HttpSession + 非HttpOnly Cookie | 写入浏览器Cookie | 前后端分离项目（推荐） |
| `LazyCsrfTokenRepository`             | 包装其他仓库   | 复用包装仓库的传递方式       | 延迟生成令牌（性能优化） |

**核心逻辑**：初次生成令牌后，框架会调用仓库的`saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response)`方法，**该方法内部既完成令牌存储，也完成令牌传递**（如写入Session、写入Cookie、注入模板引擎）。

---

## 场景1：服务端渲染项目（默认配置）—— 令牌通过「模板引擎自动注入」传递
### 核心配置（默认无需手动配置）
框架默认使用`HttpSessionCsrfTokenRepository`，令牌**存储在HttpSession**，传递方式是**Thymeleaf/JSP等模板引擎自动注入**，无需任何额外配置：
```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .csrf(c -> c.enable()) // 默认使用HttpSessionCsrfTokenRepository
        .formLogin(form -> form.permitAll())
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
    return http.build();
}
```

### 初次生成+传递的完整流程（自动触发，零开发成本）
1. **用户首次访问服务端渲染页面**：比如访问`http://localhost:8080/transfer`（Thymeleaf页面），发起GET请求；
2. **框架检测会话+令牌**：Spring Security拦截请求，检测到用户已创建Session（即使未登录，框架也会为匿名用户创建临时Session），且Session中无CSRF令牌；
3. **自动生成令牌**：框架调用`HttpSessionCsrfTokenRepository`生成UUID格式的CSRF令牌；
4. **存储+传递令牌**：
    - 存储：调用`saveToken`方法，将令牌存入当前用户的HttpSession（`session.setAttribute("_csrf", token)`）；
    - 传递：框架将令牌**存入请求属性（request.setAttribute）**，Thymeleaf模板引擎会自动检测该请求属性；
5. **模板引擎自动注入**：Thymeleaf渲染页面时，自动从请求属性中读取令牌，为页面中所有`<form>`表单**插入隐藏的`_csrf` input**，完成令牌传递；
6. **前端获取令牌**：页面渲染完成后，前端（浏览器）从HTML的隐藏input中获取令牌，表单提交时自动携带。

### 关键细节
- 传递的「载体」是**请求属性+模板引擎的自动解析**，无需Cookie/接口，零开发成本；
- 即使用户未登录，框架也会为匿名用户生成临时令牌（存入临时Session），登录后令牌会自动与用户认证信息绑定，无需重新生成；
- 若页面中有AJAX请求，可通过Thymeleaf表达式`[[${_csrf.token}]]`手动从请求属性中读取令牌，本质还是框架通过请求属性传递的。

---

## 场景2：前后端分离项目（推荐配置）—— 令牌通过「非HttpOnly Cookie」传递
### 核心配置（手动指定仓库为CookieCsrfTokenRepository）
前后端分离项目中，推荐使用`CookieCsrfTokenRepository`，令牌**主存储在HttpSession**，同时**自动写入浏览器的非HttpOnly Cookie**（作为传递载体），前端通过`document.cookie`读取即可：
```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .csrf(c -> c
            // 核心：指定令牌仓库为CookieCsrfTokenRepository，非HttpOnly
            .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
        )
        .cors(c -> c.disable()) // 配合自定义CORS配置
        .formLogin(form -> form.permitAll())
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
    return http.build();
}

// 自定义CORS配置（必配，允许跨域携带Cookie）
@Bean
public WebMvcConfigurer corsConfig() {
    return new WebMvcConfigurer() {
        @Override
        public void addCorsMappings(CorsRegistry registry) {
            registry.addMapping("/**")
                    .allowedOrigins("http://localhost:8080") // 前端域名
                    .allowedMethods("*")
                    .allowedHeaders("*")
                    .allowCredentials(true) // 允许携带Cookie（核心）
                    .maxAge(3600);
        }
    };
}
```

### 初次生成+传递的完整流程（自动触发，无额外接口）
1. **用户首次发起任意请求**：比如发起匿名的`/api/hello`请求，或直接发起登录请求`/login`，跨域访问后端；
2. **框架检测会话+令牌**：Spring Security拦截请求，检测到用户已创建跨域Session（因前端开启`withCredentials: true`），且Session中无CSRF令牌；
3. **自动生成令牌**：框架调用`CookieCsrfTokenRepository`生成UUID格式的CSRF令牌；
4. **存储+传递令牌**：
    - 主存储：将令牌存入当前用户的HttpSession（核心，用于后续校验）；
    - 传递：调用`saveToken`方法，**自动向HttpServletResponse中添加`Set-Cookie`响应头**，将令牌写入浏览器的Cookie（默认Cookie名`XSRF-TOKEN`，非HttpOnly、Secure、SameSite=Lax）；
5. **前端获取令牌**：浏览器接收到响应后，自动保存该Cookie，前端通过`document.cookie`即可读取`XSRF-TOKEN`的值，完成令牌获取；
6. **后续请求携带**：前端通过请求拦截器，将Cookie中的令牌写入`X-XSRF-TOKEN`请求头，框架自动校验。

### 关键细节
1. **Cookie仅为「传递载体」**：框架**不会从Cookie中读取令牌做校验**，校验时始终从HttpSession中读取「预期令牌」，避免浏览器自动携带Cookie导致的防护失效；
2. **非HttpOnly是核心**：必须用`withHttpOnlyFalse()`，否则前端无法通过`document.cookie`读取Cookie，传递失去意义；
3. **跨域的前提**：前端必须开启`withCredentials: true`，后端CORS必须开启`allowCredentials(true)`且`allowedOrigins`不能用`*`，否则浏览器会拒绝保存跨域Cookie；
4. **Cookie的默认属性**：默认Cookie名`XSRF-TOKEN`，有效期为「会话级」（浏览器关闭则失效），可手动配置有效期、域名、SameSite：
   ```java
   // 手动配置Cookie属性
   CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
   repository.setCookieName("MY_CSRF_TOKEN"); // 自定义Cookie名
   repository.setCookieMaxAge(7200); // 有效期2小时
   repository.setCookieDomain("xxx.com"); // 主域名，适配子域跨域
   repository.setCookieSameSite(SameSite.LAX.name());
   ```

---

## 拓展场景：前后端分离（Cookie受限）—— 令牌通过「自定义接口」主动传递
若项目对Cookie有严格限制（如禁止存储非必要Cookie），可使用**默认仓库（HttpSessionCsrfTokenRepository）+ 自定义匿名接口**的方式，**初次生成的令牌存储在HttpSession**，前端通过**主动请求自定义接口**获取令牌（框架将令牌存入请求属性，接口读取后返回给前端）。

### 核心配置+代码
1. **后端配置**：默认开启CSRF，放行自定义的令牌获取接口；
2. **自定义接口**：从请求属性中读取框架自动生成的令牌，返回给前端。

```java
// 1. Security配置（默认HttpSessionCsrfTokenRepository，放行令牌接口）
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .csrf(c -> c.enable())
        .cors(c -> c.disable())
        .formLogin(form -> form.permitAll())
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/get-csrf").permitAll() // 放行令牌接口
            .anyRequest().authenticated()
        );
    return http.build();
}

// 2. 自定义令牌获取接口（主动传递令牌）
@RestController
@RequestMapping("/api")
public class CsrfController {
    @GetMapping("/get-csrf")
    public Map<String, Object> getCsrf(HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        // 从请求属性中读取框架自动生成的CSRF令牌（核心）
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            result.put("code", 200);
            result.put("csrfToken", csrfToken.getToken()); // 令牌值
            result.put("headerName", csrfToken.getHeaderName()); // 默认X-CSRF-TOKEN
        } else {
            result.put("code", 400);
            result.put("msg", "令牌未生成");
        }
        return result;
    }
}
```

### 初次生成+传递的完整流程（前端主动触发）
1. **前端首次请求自定义令牌接口**：发起`GET http://localhost:8080/api/get-csrf`，开启`withCredentials: true`；
2. **框架检测+生成令牌**：Spring Security拦截请求，检测到Session无令牌，自动生成令牌并存入HttpSession，同时将令牌存入**请求属性**；
3. **接口读取+返回令牌**：自定义接口从请求属性中读取令牌，通过JSON响应体返回给前端，完成令牌传递；
4. **前端存储+携带**：前端将令牌存入localStorage/sessionStorage，后续请求通过请求头携带即可。

### 关键细节
- 令牌的**初次生成依然是框架自动触发**的，自定义接口只是「主动读取并返回」，无需手动生成；
- 该方式的核心传递载体是**接口的JSON响应体**，完全不依赖Cookie，适合Cookie受限的场景；
- 令牌接口必须放行（`permitAll()`），允许匿名访问，否则未登录用户无法获取令牌，导致登录请求被CSRF拦截。

---

## 核心总结：Spring Security初次生成CSRF令牌的传递方式
### 核心原则
1. **传递方式由「CsrfTokenRepository」决定**：框架封装了所有传递逻辑，只需配置仓库，无需手动处理；
2. **传递是「自动触发」的**：令牌初次生成后，框架会通过`saveToken`方法自动完成传递，无需手动调用接口/写代码；
3. **传递载体≠校验载体**：传递给前端的令牌只是「副本」，服务端**始终从核心存储（HttpSession）** 读取「预期令牌」做校验，避免浏览器自动携带导致的防护失效。

### 不同场景的传递方式速查
| 开发场景         | 推荐仓库                          | 核心存储位置 | 令牌传递方式               | 前端获取方式                     |
|------------------|-----------------------------------|--------------|----------------------------|----------------------------------|
| 服务端渲染（Thymeleaf） | HttpSessionCsrfTokenRepository（默认） | HttpSession  | 模板引擎自动注入form隐藏input | 从HTML隐藏input中读取，或Thymeleaf表达式手动读取 |
| 前后端分离（推荐）| CookieCsrfTokenRepository         | HttpSession  | 写入非HttpOnly Cookie       | 前端通过document.cookie读取Cookie |
| 前后端分离（Cookie受限） | HttpSessionCsrfTokenRepository     | HttpSession  | 自定义接口JSON响应体返回    | 前端主动请求接口，从响应体中读取 |

### 与手动实现的对比
Spring Security的令牌传递，和我们之前**手动实现的同步令牌模式**本质完全一致：
- 手动实现：登录成功后，通过**接口响应体**将令牌传递给前端；
- Spring Security：框架自动生成令牌后，通过**模板引擎/Cookie/接口**传递给前端；
  **核心不变**：**令牌的初次生成和传递，始终由服务端主导**，前端只是被动接收，攻击者因同源策略无法跨域获取令牌，这也是CSRF防护的核心。