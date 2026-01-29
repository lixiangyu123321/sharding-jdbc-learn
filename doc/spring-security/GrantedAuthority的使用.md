你问的 `GrantedAuthority` 是 Spring Security 中「权限控制的最小单元」，核心是定义“用户能做什么”（如角色、接口权限、OAuth2 作用域），我用**实战场景+完整代码示例**，从「定义→配置→使用」全流程讲清楚它的用法，包括最常见的“角色授权”和“细粒度权限授权”，同时解释为什么它适合“应用级权限”而非“域对象级权限”。

### 一、先明确核心概念：`GrantedAuthority` 是什么？
- **本质**：一个接口，只有一个核心方法 `String getAuthority()`，返回「权限字符串」（如 `ROLE_ADMIN`、`READ_ORDER`、`SCOPE_user:email`）；
- **作用**：标识用户被授予的「应用级权限」（全局生效，而非针对某个具体数据）；
- **常见实现**：`SimpleGrantedAuthority`（最常用，直接封装权限字符串）；
- **核心关联**：`Authentication.getAuthorities()` 返回用户所有 `GrantedAuthority`，是后续授权校验的唯一依据。

### 二、场景1：最基础——基于「角色」的授权（ROLE_ 前缀）
这是最常用的场景，角色本质是特殊的 `GrantedAuthority`（约定以 `ROLE_` 开头），比如 `ROLE_ADMIN`（管理员）、`ROLE_USER`（普通用户）。

#### 步骤1：定义用户的角色（GrantedAuthority）
通过 `UserDetailsService` 给用户分配角色（框架会自动封装为 `SimpleGrantedAuthority`）：
```java
import org.springframework.context.annotation.Bean;
import org.springframework.org.lix.mycatdemo.security.core.authority.SimpleGrantedAuthority;
import org.springframework.org.lix.mycatdemo.security.core.userdetails.User;
import org.springframework.org.lix.mycatdemo.security.core.userdetails.UserDetails;
import org.springframework.org.lix.mycatdemo.security.core.userdetails.UserDetailsService;
import org.springframework.org.lix.mycatdemo.security.core.userdetails.UsernameNotFoundException;
import org.springframework.org.lix.mycatdemo.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.org.lix.mycatdemo.security.crypto.password.PasswordEncoder;

@Configuration
public class SecurityConfig {

    // 1. 配置密码编码器
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 2. 配置UserDetailsService：给不同用户分配角色（GrantedAuthority）
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            // 模拟数据库查询用户（实际项目中替换为DAO查询）
            if ("admin".equals(username)) {
                // 给admin分配：ROLE_ADMIN（管理员角色） + READ_ORDER（读订单权限）
                return User.withUsername("admin")
                        .password(passwordEncoder().encode("123456"))
                        // 方式1：直接传权限字符串，框架自动封装为SimpleGrantedAuthority
                        .authorities("ROLE_ADMIN", "READ_ORDER", "WRITE_ORDER")
                        .build();
            } else if ("user".equals(username)) {
                // 给user分配：ROLE_USER（普通用户角色） + READ_ORDER（仅读订单权限）
                return User.withUsername("user")
                        .password(passwordEncoder().encode("123456"))
                        // 方式2：手动创建SimpleGrantedAuthority（更直观）
                        .authorities(
                                new SimpleGrantedAuthority("ROLE_USER"),
                                new SimpleGrantedAuthority("READ_ORDER")
                        )
                        .build();
            } else {
                throw new UsernameNotFoundException("用户不存在");
            }
        };
    }
}
```

#### 步骤2：配置接口级授权（基于角色/权限）
在 `SecurityFilterChain` 中，通过角色/权限控制接口访问：
```java
import org.springframework.context.annotation.Bean;
import org.springframework.org.lix.mycatdemo.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.org.lix.mycatdemo.security.web.SecurityFilterChain;

@Configuration
public class WebSecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // 1. 基于角色授权（hasRole会自动拼接ROLE_前缀）
                        .requestMatchers("/admin/**").hasRole("ADMIN") // 等价于 hasAuthority("ROLE_ADMIN")
                        .requestMatchers("/user/**").hasRole("USER")   // 等价于 hasAuthority("ROLE_USER")
                        // 2. 基于细粒度权限授权（hasAuthority直接匹配权限字符串）
                        .requestMatchers("/order/write").hasAuthority("WRITE_ORDER")
                        .requestMatchers("/order/read").hasAuthority("READ_ORDER")
                        // 3. 多条件组合：需要ADMIN角色 或 WRITE_ORDER权限
                        .requestMatchers("/order/delete").access("hasRole('ADMIN') or hasAuthority('WRITE_ORDER')")
                        // 4. 所有其他请求需要认证
                        .anyRequest().authenticated()
                )
                // 启用表单登录（测试用）
                .formLogin();
        return http.build();
    }
}
```

#### 步骤3：测试效果（验证授权规则）
| 访问接口       | 登录用户 | 权限校验结果 | 原因                     |
|----------------|----------|--------------|--------------------------|
| `/admin/index` | admin    | 允许访问     | 有 ROLE_ADMIN 角色       |
| `/admin/index` | user     | 拒绝访问（403） | 只有 ROLE_USER 角色      |
| `/order/write` | admin    | 允许访问     | 有 WRITE_ORDER 权限      |
| `/order/write` | user     | 拒绝访问（403） | 只有 READ_ORDER 权限     |
| `/order/read`  | admin/user | 允许访问   | 两者都有 READ_ORDER 权限 |

### 三、场景2：方法级授权（更灵活，Controller/Service 中使用）
除了接口级授权，还能在方法上通过注解控制权限（需开启 `@EnableMethodSecurity`）。

#### 步骤1：开启方法级安全注解
```java
import org.springframework.context.annotation.Configuration;
import org.springframework.org.lix.mycatdemo.security.config.annotation.method.configuration.EnableMethodSecurity;

// 开启方法级授权注解（prePostEnabled=true 启用@PreAuthorize/@PostAuthorize）
@Configuration
@EnableMethodSecurity(prePostEnabled = true)
public class MethodSecurityConfig {
}
```

#### 步骤2：在 Controller/Service 中使用注解授权
```java
import org.springframework.org.lix.mycatdemo.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/order")
public class OrderController {

    // 1. 基于角色：只有ADMIN角色可访问
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/admin/delete")
    public String deleteOrder() {
        return "管理员删除订单成功";
    }

    // 2. 基于细粒度权限：只有WRITE_ORDER权限可访问
    @PreAuthorize("hasAuthority('WRITE_ORDER')")
    @GetMapping("/write")
    public String writeOrder() {
        return "创建/修改订单成功";
    }

    // 3. 组合条件 + 获取当前用户权限
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('READ_ORDER')")
    @GetMapping("/read")
    public String readOrder() {
        // 业务中获取当前用户的GrantedAuthority
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // 遍历用户所有权限
        String authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(","));
        return "当前用户权限：" + authorities + "，查询订单成功";
    }
}
```

### 四、场景3：OAuth2 中的「作用域（Scope）」（特殊的 GrantedAuthority）
OAuth2 登录中的「作用域」（如 `SCOPE_user:email`）本质也是 `GrantedAuthority`（约定以 `SCOPE_` 开头），用法和角色/权限完全一致：

#### 配置示例（GitHub OAuth2 登录，基于 Scope 授权）
```java
@Configuration
public class OAuth2SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // 要求用户有 SCOPE_user:email 权限（GitHub授权的作用域）
                        .requestMatchers("/user/email").hasAuthority("SCOPE_user:email")
                        .anyRequest().authenticated()
                )
                .oauth2Login(); // 启用OAuth2登录
        return http.build();
    }
}
```

### 五、关键补充：为什么 `GrantedAuthority` 不适合「域对象级权限」？
你提到的“针对 Employee 54 号的权限”属于「域对象级权限」（数据级权限），比如“用户A只能查看自己的订单”“管理员只能修改部门1的员工”，这类权限不适合用 `GrantedAuthority`，原因：
1. **内存开销大**：如果有1000个员工，就要创建1000个 `GrantedAuthority`（如 `EMPLOYEE_54`），用户登录时要加载大量权限，性能极差；
2. **不灵活**：域对象权限通常是动态的（比如员工调部门），而 `GrantedAuthority` 是登录时加载的，无法实时更新。

#### 解决方案：用 Spring Security 的「方法级授权 + 业务逻辑」实现域对象权限
比如“用户只能查看自己的订单”：
```java
@RestController
@RequestMapping("/my/order")
public class MyOrderController {

    // 1. 先校验基础权限（READ_ORDER），再校验数据权限（订单所属人=当前用户）
    @PreAuthorize("hasAuthority('READ_ORDER') and @orderService.isOwner(#orderId, authentication.name)")
    @GetMapping("/{orderId}")
    public String getMyOrder(@PathVariable Long orderId) {
        return "查看订单 " + orderId + " 成功";
    }
}

// 业务层：校验数据权限
@Service
public class OrderService {
    // 模拟数据库查询：判断订单是否属于当前用户
    public boolean isOwner(Long orderId, String username) {
        // 实际项目中替换为DAO查询
        Map<Long, String> orderOwnerMap = new HashMap<>();
        orderOwnerMap.put(1L, "admin");
        orderOwnerMap.put(2L, "user");
        return username.equals(orderOwnerMap.get(orderId));
    }
}
```

### 六、核心总结（关键点回顾）
1. **`GrantedAuthority` 的核心用法**：
    - 「角色授权」：以 `ROLE_` 为前缀（如 `ROLE_ADMIN`），用 `hasRole()` 校验（自动拼接前缀）；
    - 「细粒度权限授权」：自定义字符串（如 `READ_ORDER`），用 `hasAuthority()` 精准匹配；
    - 「OAuth2 作用域」：以 `SCOPE_` 为前缀（如 `SCOPE_user:email`），用法和普通权限一致；
2. **授权层级**：
    - 接口级：在 `SecurityFilterChain` 中配置（粗粒度，控制URL访问）；
    - 方法级：用 `@PreAuthorize` 注解（细粒度，可结合业务逻辑）；
3. **适用场景**：
    - `GrantedAuthority` 适合「应用级权限」（全局生效，如角色、通用接口权限）；
    - 「域对象级权限」（数据级，如“只能看自己的订单”）：用「基础权限 + 业务逻辑」实现，而非创建大量 `GrantedAuthority`；
4. **核心优势**：统一了所有认证方式的权限格式（表单/OAuth2/记住我），授权校验无需区分认证方式，标准化程度高。

简单说：`GrantedAuthority` 是 Spring Security 权限体系的“基础积木”——先给用户分配积木（角色/权限），再通过“积木组合”控制接口/方法的访问，而数据级权限则在基础积木之上，结合业务逻辑实现。