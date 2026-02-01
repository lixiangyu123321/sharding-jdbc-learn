你在Spring Security配置中看到的`formLogin(Customizer.withDefaults())`，是开启**表单登录认证**的核心配置，和之前讲的`httpBasic()`（Basic认证）是Spring Security中最基础的两种认证方式，二者可以单独用，也能同时开启（客户端可自选认证方式）。

我会从**作用、使用方式、与Basic认证的区别、混合配置**这几个方面讲清楚这个配置，同时结合你之前的Basic认证配置做对比，让你快速理解和使用。

### 一、`formLogin(Customizer.withDefaults())` 核心作用
`formLogin()`是Spring Security的表单登录配置入口，`Customizer.withDefaults()`表示**使用Spring Security的默认配置**开启表单登录，无需自定义任何参数。

开启后，Spring Security会自动完成这些工作，零自定义即可用：
1. 生成一个**默认的登录页面**（路径`/login`），包含用户名（默认字段`username`）、密码（默认字段`password`）输入框和提交按钮；
2. 处理登录请求：自动接收`/login`的POST请求，解析表单参数并验证用户名密码；
3. 处理认证成功/失败：成功后默认跳转到**请求的原路径**（如访问`/api/info`被拦截，登录成功后自动跳转到`/api/info`），失败后跳转到`/login?error`；
4. 处理注销：默认开启`/logout`注销接口，访问后销毁登录会话；
5. 拦截未认证请求：未登录时访问受保护接口，**自动重定向到`/login`登录页**（替代Basic认证的401质询）。

简单说：这行配置让Spring Security实现了**开箱即用的表单登录**，适合传统Web系统（前后端不分离），是后台管理系统最常用的认证方式。

### 二、单独开启表单登录的完整配置（基于之前的Basic认证代码）
只需在原有`SecurityFilterChain`中添加`formLogin(Customizer.withDefaults())`，即可替换Basic认证为表单登录（也可保留Basic认证，后面讲混合配置）。

```java
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.org.lix.mycatdemo.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.org.lix.mycatdemo.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.org.lix.mycatdemo.security.core.userdetails.User;
import org.springframework.org.lix.mycatdemo.security.core.userdetails.UserDetails;
import org.springframework.org.lix.mycatdemo.security.core.userdetails.UserDetailsService;
import org.springframework.org.lix.mycatdemo.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.org.lix.mycatdemo.security.crypto.password.PasswordEncoder;
import org.springframework.org.lix.mycatdemo.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.org.lix.mycatdemo.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .anyRequest().authenticated() // 所有请求需要认证
            )
            .formLogin(Customizer.withDefaults()) // 开启默认表单登录（核心）
            .csrf(csrf -> csrf.disable()); // 测试环境可关闭CSRF，生产环境需开启（后面说明）
        return http.build();
    }

    // 内存用户信息（和Basic认证通用，无需修改）
    @Bean
    public UserDetailsService userDetailsService() {
        UserDetails user = User.withUsername("admin")
            .password(passwordEncoder().encode("123456"))
            .roles("ADMIN")
            .build();
        return new InMemoryUserDetailsManager(user);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

#### 启动后直接使用（零前端开发）
1. 启动Spring Boot项目，访问任意受保护接口（如`http://localhost:8080/api/info`）；
2. 会**自动重定向到Spring Security默认登录页**（`http://localhost:8080/login`）：
   ![默认登录页](https://img-blog.csdnimg.cn/2024010515221229.png)（简单的用户名密码表单，Spring Security内置）
3. 输入`admin/123456`，登录成功后自动跳转到之前访问的`/api/info`；
4. 注销：直接访问`http://localhost:8080/logout`，即可销毁会话，再次访问受保护接口会重定向到登录页。

#### 关键小细节：关闭CSRF的原因
Spring Security默认开启**CSRF防护**（跨站请求伪造），对`POST/PUT/DELETE`等请求会验证CSRF令牌。
- 测试环境：默认登录页会自动生成CSRF令牌，关闭CSRF仅为了方便（如直接用Postman测试）；
- 生产环境：**绝对不能关闭CSRF**，自定义登录页时需在表单中添加CSRF令牌（`<input type="hidden" th:value="${_csrf.token}" name="${_csrf.parameterName}"/>`）。

### 三、表单登录（formLogin）与Basic认证（httpBasic）的核心区别
这是最容易混淆的点，二者都是Spring Security内置认证方式，但适用场景、交互方式完全不同，对比表一目了然：

| 对比维度         | 表单登录（formLogin）| HTTP Basic认证（httpBasic）|
|------------------|---------------------------------------------|---------------------------------------------|
| **交互方式**     | 重定向到登录页，用户手动填表单提交           | 浏览器弹出系统级认证框，自动编码请求头       |
| **未认证拦截**   | 返回302重定向，跳转到`/login`                | 返回401 Unauthorized，带`WWW-Authenticate`头|
| **注销机制**     | 内置`/logout`接口，支持主动注销              | 无内置注销，需关闭浏览器清空缓存             |
| **前端适配**     | 适合**传统Web系统**（前后端不分离）| 适合**接口调用**（微服务、小程序、第三方工具）|
| **用户体验**     | 自定义性强（可做精美登录页），体验好         | 浏览器默认弹窗，体验差，几乎无法自定义       |
| **安全性**       | 基于Session，需配合HTTPS，安全性中等         | 仅Base64编码，**必须**配合HTTPS，安全性低    |
| **状态性**       | 有状态（服务端保存Session）| 无状态（每次请求携带认证信息）|

### 四、同时开启formLogin和httpBasic（客户端自选认证方式）
Spring Security支持**同时开启两种认证方式**，此时客户端可以根据自身场景选择用哪种方式认证，服务端会自动适配，这是实际开发中很常用的配置（如后台管理系统用表单登录，接口调用用Basic认证）。

#### 混合配置代码（仅需同时添加两个配置）
```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .authorizeHttpRequests(auth -> auth
            .anyRequest().authenticated()
        )
        .formLogin(Customizer.withDefaults()) // 开启表单登录
        .httpBasic(); // 开启Basic认证（无需withDefaults，默认配置即可）
        // .csrf(csrf -> csrf.disable()); // 生产环境保留CSRF
    return http.build();
}
```

#### 混合模式的适配规则（核心）
服务端同时开启后，**客户端的请求方式决定了使用哪种认证**：
1. **浏览器访问**：未登录时访问受保护接口，**优先触发表单登录**（302重定向到`/login`），这是Spring Security的默认优先级；
2. **接口工具调用**（Postman/Curl/小程序）：不处理302重定向，服务端会降级为**Basic认证**（返回401，要求携带`Authorization`头），此时可在工具中添加Basic认证信息即可访问。

#### Postman测试混合模式的Basic认证
1. 打开Postman，请求`http://localhost:8080/api/info`；
2. 选择**Authorization**标签，认证类型选**Basic Auth**；
3. 输入用户名`admin`，密码`123456`，Postman会自动生成`Authorization`头；
4. 发送请求，即可成功获取数据（无需跳转到登录页）。

### 五、formLogin的自定义配置（突破默认限制）
`Customizer.withDefaults()`是快速使用，但实际开发中需要自定义**登录页、登录接口、成功/失败跳转**，只需对`formLogin()`做链式配置即可，核心自定义项如下：

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .authorizeHttpRequests(auth -> auth
            .anyRequest().authenticated()
        )
        .formLogin(form -> form
            .loginPage("/custom-login") // 自定义登录页路径（替代默认/login）
            .loginProcessingUrl("/do-login") // 自定义登录请求接口（POST方式，Spring Security自动处理）
            .usernameParameter("user") // 自定义表单用户名字段（替代默认username）
            .passwordParameter("pass") // 自定义表单密码字段（替代默认password）
            .successForwardUrl("/index") // 登录成功后转发到该路径（前后端不分离）
            .failureForwardUrl("/login-error") // 登录失败后转发到该路径
            // 前后端分离用这个：成功后返回JSON，不跳转
            // .successHandler((req, res, auth) -> {
            //     res.setContentType("application/json;charset=utf-8");
            //     res.getWriter().write("{\"code\":200,\"msg\":\"登录成功\"}");
            // })
            // 前后端分离用这个：失败后返回JSON，不跳转
            // .failureHandler((req, res, ex) -> {
            //     res.setContentType("application/json;charset=utf-8");
            //     res.getWriter().write("{\"code\":400,\"msg\":\"用户名或密码错误\"}");
            // })
        )
        .logout(logout -> logout
            .logoutUrl("/custom-logout") // 自定义注销接口
            .logoutSuccessUrl("/custom-login") // 注销成功后跳转到登录页
        )
        .csrf(csrf -> csrf.disable());
    return http.build();
}
```

#### 自定义配置说明
1. `loginPage("/custom-login")`：需要自己开发一个前端页面（如`custom-login.html`），放在`resources/templates/`下；
2. `loginProcessingUrl("/do-login")`：**无需自己写Controller**，Spring Security会自动拦截该POST请求，解析表单参数并验证；
3. 前后端分离项目：不用`successForwardUrl`/`failureForwardUrl`，而是用`successHandler`/`failureHandler`自定义响应，直接返回JSON数据，避免页面跳转。

### 六、放行静态资源（登录页的CSS/JS/图片）
开启表单登录后，Spring Security会拦截**所有请求**，包括登录页的CSS、JS、图片等静态资源，导致登录页样式失效，需要手动**放行静态资源路径**，在`authorizeHttpRequests`中添加`permitAll()`：

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/css/**", "/js/**", "/images/**").permitAll() // 放行静态资源
    .requestMatchers("/custom-login").permitAll() // 放行自定义登录页（无需认证即可访问）
    .anyRequest().authenticated() // 其他请求需要认证
)
```

### 总结
1. `formLogin(Customizer.withDefaults())`是Spring Security**开启默认表单登录**的核心配置，零自定义即可生成登录页、处理登录/注销，适合传统Web系统；
2. 表单登录与Basic认证是互补的内置认证方式，**可同时开启**：浏览器访问优先表单登录，接口工具调用降级为Basic认证；
3. 核心区别：表单登录是**302重定向+表单提交+有状态Session**，体验好可自定义；Basic认证是**401质询+请求头编码+无状态**，实现简单适合接口调用；
4. 实际开发中可通过链式配置自定义表单登录的**登录页、请求接口、字段名、成功/失败处理**，前后端分离场景用`successHandler/failureHandler`返回JSON；
5. 开启表单登录后需**放行静态资源和登录页**，否则会被Spring Security拦截导致样式失效/无法访问。