你想了解`JdbcUserDetailsManager`的使用方法，它是Spring Security提供的**基于JDBC的用户信息管理实现类**，是`InMemoryUserDetailsManager`的数据库替代方案——既实现了`UserDetailsService`（满足认证时的用户查询），还扩展了**用户增删改查、角色管理**的功能，无需自己手写数据库CRUD代码，开箱即用支持关系型数据库（MySQL/PostgreSQL/Oracle等），是Spring Security中**快速实现数据库式用户认证+用户管理**的首选方案，适合开发/生产环境使用。

### 核心定位：和之前组件的关系
先理清`JdbcUserDetailsManager`的核心角色，避免混淆：
- 实现`UserDetailsService`：认证时Spring Security会自动调用它的`loadUserByUsername`方法从数据库查用户，替代内存用户；
- 扩展`UserDetailsManager`接口：提供**用户新增、删除、修改、密码重置**等操作，无需自己写Mapper/DAO；
- 依赖`DataSource`和`PasswordEncoder`：前者用于数据库连接，后者用于密码加密/校验（就是我们之前配置的全局`BCryptPasswordEncoder`）；
- 内置标准SQL：默认使用Spring Security预定义的**用户表、权限表SQL语句**，无需自己建表（也支持自定义表结构/SQL）。

### 一、使用前提：环境准备
#### 1. 引入核心依赖
需要3个依赖：Spring Security、JDBC、数据库驱动（以MySQL为例），如果是Spring Boot项目，直接引入starter即可：
```xml
<!-- Spring Security核心（必选） -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-org.lix.mycatdemo.security</artifactId>
</dependency>
<!-- Spring JDBC（JdbcUserDetailsManager依赖，必选） -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
<!-- MySQL驱动（根据数据库替换，必选） -->
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>
```

#### 2. 配置数据库连接（application.yml/application.properties）
在配置文件中配置`DataSource`（Spring Boot会自动创建`DataSource` Bean，`JdbcUserDetailsManager`会自动注入），以MySQL8.x为例：
```yaml
spring:
  # 数据库连接配置
  datasource:
    url: jdbc:mysql://localhost:3306/security_db?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false
    username: root # 你的数据库用户名
    password: 123456 # 你的数据库密码
    driver-class-name: com.mysql.cj.jdbc.Driver
  # 可选：开启JDBC日志，查看自动执行的SQL
  jdbc:
    template:
      log-statements: true
```
**注意**：提前创建数据库`security_db`（无需手动建表，`JdbcUserDetailsManager`会自动创建）。

### 二、基础使用：默认表结构（开箱即用）
这是最常用的方式，使用Spring Security**预定义的2张标准表**，无需手写建表SQL、无需自定义SQL，直接配置即可实现「数据库认证+用户管理」。

#### 1. 完整配置类（核心）
只需配置3个Bean：`PasswordEncoder`（全局密码加密）、`JdbcUserDetailsManager`（数据库用户管理）、`SecurityFilterChain`（安全规则），代码直接可用：
```java
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.org.lix.mycatdemo.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.org.lix.mycatdemo.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.org.lix.mycatdemo.security.core.userdetails.User;
import org.springframework.org.lix.mycatdemo.security.core.userdetails.UserDetails;
import org.springframework.org.lix.mycatdemo.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.org.lix.mycatdemo.security.crypto.password.PasswordEncoder;
import org.springframework.org.lix.mycatdemo.security.provisioning.JdbcUserDetailsManager;
import org.springframework.org.lix.mycatdemo.security.web.SecurityFilterChain;
import javax.sql.DataSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // 1. 全局密码编码器（必选，解耦，所有密码加密/校验复用）
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 2. 配置JdbcUserDetailsManager（核心，替代InMemoryUserDetailsManager）
    // 注入Spring Boot自动配置的DataSource，关联数据库
    @Bean
    public JdbcUserDetailsManager jdbcUserDetailsManager(DataSource dataSource, PasswordEncoder passwordEncoder) {
        // 创建JdbcUserDetailsManager实例，传入数据库连接
        JdbcUserDetailsManager manager = new JdbcUserDetailsManager(dataSource);
        // 绑定密码编码器（必须设置，否则密码校验失败）
        manager.setPasswordEncoder(passwordEncoder);
        
        // 可选：初始化测试用户（项目启动时执行，仅第一次启动需要，后续注释掉）
        // 避免重复创建用户，先判断是否存在
        if (!manager.userExists("user")) {
            UserDetails normalUser = User.builder()
                    .username("user")
                    .password(passwordEncoder.encode("123456")) // 手动加密原始密码
                    .roles("USER") // 普通用户角色
                    .build();
            manager.createUser(normalUser); // 新增用户
        }
        if (!manager.userExists("admin")) {
            UserDetails adminUser = User.builder()
                    .username("admin")
                    .password(passwordEncoder.encode("admin123"))
                    .roles("ADMIN", "USER") // 管理员多角色
                    .build();
            manager.createUser(adminUser); // 新增管理员
        }
        
        return manager;
    }

    // 3. 安全过滤链配置（表单登录/Basic认证，按需配置）
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().authenticated() // 所有请求需要认证
                )
                .formLogin(); // 开启表单登录（可配合httpBasic()）
                // .csrf(csrf -> csrf.disable()); // 测试环境可选关闭，生产环境开启
        return http.build();
    }
}
```

#### 2. 自动创建的2张标准表（关键）
启动项目后，`JdbcUserDetailsManager`会自动在`security_db`中创建**2张预定义表**，表结构和作用如下（MySQL为例）：
##### （1）`users`表：存储用户核心信息
| 字段名         | 类型         | 作用                     |
|----------------|--------------|--------------------------|
| `username`     | VARCHAR(50)  | 用户名（主键，唯一）|
| `password`     | VARCHAR(512) | 加密后的密码（BCrypt密文）|
| `enabled`      | TINYINT(1)   | 用户是否可用（1=可用，0=禁用） |

##### （2）`authorities`表：存储用户角色/权限
| 字段名         | 类型         | 作用                     |
|----------------|--------------|--------------------------|
| `username`     | VARCHAR(50)  | 用户名（外键，关联users表） |
| `authority`    | VARCHAR(50)  | 角色/权限（Spring Security中角色以`ROLE_`开头，如`ROLE_USER`） |

**注意**：配置中用`.roles("USER")`时，框架会自动拼接为`ROLE_USER`并存入`authorities`表，认证时会自动识别角色权限。

#### 3. 测试效果
1. 启动项目，查看数据库：`users`和`authorities`表已创建，且包含`user/123456`、`admin/admin123`两个用户；
2. 访问`http://localhost:8080`，会跳转到Spring Security默认登录页；
3. 输入`user/123456`或`admin/admin123`，均可成功登录，认证逻辑完全由`JdbcUserDetailsManager`处理。

### 三、核心功能：用户增删改查（内置方法，无需手写SQL）
`JdbcUserDetailsManager`实现了`UserDetailsManager`接口，提供了**全套用户管理方法**，直接注入即可使用，适用于「用户注册、后台用户管理」等场景，无需自己写Mapper/DAO。

#### 1. 常用核心方法（直接调用）
| 方法名                  | 功能说明                     | 入参/返回值                     |
|-------------------------|------------------------------|--------------------------------|
| `createUser(UserDetails)` | 新增用户                     | 入参：UserDetails对象          |
| `updateUser(UserDetails)` | 修改用户信息（密码、状态等） | 入参：UserDetails对象          |
| `deleteUser(String)`     | 删除用户                     | 入参：用户名                   |
| `changePassword(String, String)` | 重置密码 | 入参：原密码、新密码           |
| `userExists(String)`     | 判断用户是否存在             | 入参：用户名，返回：boolean    |
| `loadUserByUsername(String)` | 根据用户名查用户（认证核心） | 入参：用户名，返回：UserDetails |

#### 2. 实战：创建用户管理Controller（用户增删改查示例）
注入`JdbcUserDetailsManager`和`PasswordEncoder`，直接调用内置方法实现用户管理，无需手写数据库操作：
```java
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.org.lix.mycatdemo.security.core.userdetails.User;
import org.springframework.org.lix.mycatdemo.security.core.userdetails.UserDetails;
import org.springframework.org.lix.mycatdemo.security.crypto.password.PasswordEncoder;
import org.springframework.org.lix.mycatdemo.security.provisioning.JdbcUserDetailsManager;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user")
public class UserManagerController {

    // 注入JdbcUserDetailsManager
    @Autowired
    private JdbcUserDetailsManager userManager;

    // 注入全局密码编码器
    @Autowired
    private PasswordEncoder passwordEncoder;

    // 1. 新增用户
    @PostMapping("/create")
    public String createUser(@RequestParam String username, 
                             @RequestParam String password, 
                             @RequestParam String role) {
        if (userManager.userExists(username)) {
            return "用户" + username + "已存在";
        }
        // 构建用户对象，密码加密，设置角色
        UserDetails user = User.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .roles(role) // 如"USER"、"ADMIN"
                .enabled(true) // 开启用户
                .accountNonLocked(true) // 账号不锁定
                .build();
        userManager.createUser(user);
        return "用户" + username + "创建成功";
    }

    // 2. 删除用户
    @DeleteMapping("/delete/{username}")
    public String deleteUser(@PathVariable String username) {
        if (!userManager.userExists(username)) {
            return "用户" + username + "不存在";
        }
        userManager.deleteUser(username);
        return "用户" + username + "删除成功";
    }

    // 3. 重置用户密码（无需原密码，后台管理用）
    @PutMapping("/reset-pwd")
    public String resetPassword(@RequestParam String username, @RequestParam String newPwd) {
        if (!userManager.userExists(username)) {
            return "用户" + username + "不存在";
        }
        // 先查询原用户信息（保留角色、状态等）
        UserDetails oldUser = userManager.loadUserByUsername(username);
        // 构建新用户对象，替换密码，其他信息不变
        UserDetails newUser = User.builder()
                .username(oldUser.getUsername())
                .password(passwordEncoder.encode(newPwd))
                .authorities(oldUser.getAuthorities()) // 保留原角色/权限
                .enabled(oldUser.isEnabled())
                .accountNonLocked(oldUser.isAccountNonLocked())
                .build();
        userManager.updateUser(newUser);
        return "用户" + username + "密码重置成功";
    }

    // 4. 检查用户是否存在
    @GetMapping("/exists/{username}")
    public String checkUserExists(@PathVariable String username) {
        return userManager.userExists(username) 
                ? "用户" + username + "存在" 
                : "用户" + username + "不存在";
    }
}
```
**测试方式**：用Postman调用接口，例如：
- 新增用户：`POST http://localhost:8080/api/user/create?username=test&password=test123&role=USER`
- 删除用户：`DELETE http://localhost:8080/api/user/delete/test`

### 四、高级使用：自定义表结构/SQL（适配现有数据库）
如果你的项目已有**自定义的用户表/权限表**（非Spring Security标准表），无需修改表结构，只需**自定义SQL语句**并注入`JdbcUserDetailsManager`即可，核心是重写4个关键SQL（用户查询、权限查询、用户存在判断、权限新增）。

#### 1. 假设自定义表结构（示例）
现有项目的2张表（代替标准的`users`和`authorities`）：
##### （1）`sys_user`表（用户表）
| 字段名         | 类型         | 对应标准字段               |
|----------------|--------------|----------------------------|
| `user_name`    | VARCHAR(50)  | username（用户名）|
| `user_pwd`     | VARCHAR(512) | password（加密密码）|
| `status`       | TINYINT(1)   | enabled（1=可用，0=禁用）|

##### （2）`sys_user_role`表（用户-角色表）
| 字段名         | 类型         | 对应标准字段               |
|----------------|--------------|----------------------------|
| `user_name`    | VARCHAR(50)  | username（外键）|
| `role_code`    | VARCHAR(50)  | authority（角色，如`ROLE_USER`） |

#### 2. 配置自定义SQL的JdbcUserDetailsManager
在配置类中重写4个核心SQL，替换`JdbcUserDetailsManager`的默认SQL：
```java
@Bean
public JdbcUserDetailsManager jdbcUserDetailsManager(DataSource dataSource, PasswordEncoder passwordEncoder) {
    JdbcUserDetailsManager manager = new JdbcUserDetailsManager(dataSource);
    manager.setPasswordEncoder(passwordEncoder);

    // 自定义SQL1：根据用户名查询用户信息（核心，认证时调用）
    String findUserSql = "select user_name, user_pwd, status from sys_user where user_name = ?";
    manager.setUsersByUsernameQuery(findUserSql);

    // 自定义SQL2：根据用户名查询角色/权限
    String findAuthoritiesSql = "select user_name, role_code from sys_user_role where user_name = ?";
    manager.setAuthoritiesByUsernameQuery(findAuthoritiesSql);

    // 自定义SQL3：判断用户是否存在
    String userExistsSql = "select count(1) from sys_user where user_name = ?";
    manager.setUserExistsSql(userExistsSql);

    // 自定义SQL4：新增用户时插入权限（createUser方法会调用）
    String insertAuthoritySql = "insert into sys_user_role (user_name, role_code) values (?, ?)";
    manager.setInsertAuthoritySql(insertAuthoritySql);

    // 可选：初始化测试用户（适配自定义表）
    if (!manager.userExists("test")) {
        UserDetails testUser = User.builder()
                .username("test")
                .password(passwordEncoder.encode("test123"))
                .roles("USER")
                .build();
        manager.createUser(testUser);
    }

    return manager;
}
```
**关键说明**：
- 自定义SQL的**查询参数必须是`?`，且顺序固定**（如用户查询SQL的参数只能是用户名）；
- 用户表的查询结果**必须按「用户名、密码、是否可用」的顺序返回**；
- 权限表的查询结果**必须按「用户名、角色/权限」的顺序返回**；
- 角色/权限值**必须以`ROLE_`开头**（如`ROLE_USER`），否则`@PreAuthorize("hasRole('USER')")`无法识别。

### 五、关键注意事项（避坑指南）
1. **密码必须加密存储**：无论新增/修改用户，密码都要通过`PasswordEncoder.encode()`加密，`JdbcUserDetailsManager`只负责存储，不自动加密；
2. **避免重复初始化用户**：配置中的`if (!manager.userExists("xxx"))`必须加，否则项目每次重启都会执行`createUser`，导致数据库主键冲突；
3. **角色命名规范**：用`.roles("USER")`时框架自动拼接`ROLE_`，直接用`.authorities("ROLE_USER")`效果一致，自定义表时必须存`ROLE_`开头的值；
4. **CSRF防护**：生产环境不要关闭CSRF，否则POST/PUT/DELETE请求会被拦截，自定义登录页/接口时需添加CSRF令牌；
5. **表结构兼容**：默认表的字段长度足够（如密码字段`VARCHAR(512)`），BCrypt加密后的密码长度约60位，避免字段长度不足导致插入失败；
6. **多数据源适配**：如果项目有多个数据源，需手动指定`JdbcUserDetailsManager`使用的`DataSource`（而非Spring Boot自动配置的默认数据源）。

### 六、和手动实现`UserDetailsService`的对比
很多人会纠结用`JdbcUserDetailsManager`还是手动实现`UserDetailsService`，二者的核心区别和适用场景如下：

| 对比维度         | JdbcUserDetailsManager                | 手动实现UserDetailsService              |
|------------------|---------------------------------------|-----------------------------------------|
| **开发成本**     | 极低，开箱即用，无需手写CRUD/SQL      | 较高，需手写Mapper/DAO/SQL              |
| **功能支持**     | 内置用户增删改查，无需额外开发        | 需自己实现用户管理的CRUD方法            |
| **灵活性**       | 中等，支持自定义SQL，适配现有表结构   | 极高，完全自定义查询逻辑、表关联、业务规则 |
| **适用场景**     | 快速开发、标准用户认证、简单用户管理  | 复杂业务场景（如多表关联、自定义权限规则） |
| **学习成本**     | 低，无需深入了解Spring Security底层   | 高，需理解`UserDetails`、`Authentication`等核心组件 |

**推荐原则**：
- 快速开发、简单的后台管理系统、标准用户名+密码认证：**优先用JdbcUserDetailsManager**；
- 复杂业务（如用户表关联部门表、自定义权限规则、多条件查询）：**手动实现UserDetailsService**。

### 总结
1. `JdbcUserDetailsManager`是Spring Security提供的**JDBC版用户管理实现**，替代内存用户，实现数据库式认证+用户增删改查，开箱即用；
2. **基础使用**：依赖`DataSource`和`PasswordEncoder`，使用Spring Security预定义的`users/authorities`表，无需手写SQL和建表语句；
3. **核心功能**：内置`createUser/deleteUser/updateUser/changePassword`等方法，直接实现用户管理，无需手写数据库CRUD；
4. **高级使用**：支持自定义表结构/SQL，通过`setUsersByUsernameQuery`等方法重写默认SQL，适配现有项目的数据库；
5. **适用场景**：快速开发、标准用户名+密码认证的项目，开发成本极低；复杂业务场景建议手动实现`UserDetailsService`；
6. **关键避坑**：密码必须手动加密、避免重复初始化用户、角色以`ROLE_`开头、生产环境开启CSRF防护。