你问的`PersistentTokenRepository`是**Spring Security 为记住我功能提供的官方令牌持久化接口**，专门用于管理「持久化型记住我令牌」的增、删、改、查，是和`PersistentTokenBasedRememberMeServices`（官方持久化记住我服务）配套的核心接口，用来解决**默认简单令牌（TokenBasedRememberMeServices）无持久化、无法令牌刷新、安全性低**的问题。

简单说：这个接口的核心作用是**标准化记住我令牌的持久化操作**，Spring Security 帮我们定义好了令牌该怎么存、怎么取、怎么更、怎么删，我们只需要实现这个接口（或用官方现成实现），就能快速实现**支持令牌刷新、失效、多设备管理**的安全记住我功能，不用像自定义`TokenRepository`那样从零写接口，完全贴合Spring Security的原生设计。

### 一、先搞懂：为什么需要这个接口？
之前讲的`TokenBasedRememberMeServices`是Spring Security记住我的**简单实现**，它的令牌是**加密的一次性字符串**，存在客户端Cookie中，服务端不做持久化存储，缺点很明显：
- 令牌无法刷新，一旦生成有效期内一直有效；
- 服务端无法主动失效令牌（比如用户想退出所有设备，做不到）；
- 令牌被盗后，只能等过期才能失效，安全性低。

而`PersistentTokenRepository`就是为了解决这些问题而生的——**将令牌持久化到服务端（数据库/Redis）**，服务端能精准管控每个令牌的生命周期，配合`PersistentTokenBasedRememberMeServices`就能实现**令牌自动刷新、主动失效、多设备管理**，这也是生产环境推荐的记住我实现方案。

### 二、`PersistentTokenRepository`的核心作用&设计初衷
1. **标准化令牌持久化操作**：定义了令牌增、删、改、查的标准方法，Spring Security内部直接调用，开发者无需关注底层调用逻辑，只需实现接口适配存储介质（数据库/Redis）；
2. **支撑令牌的刷新/系列化机制**：接口中专门提供了`updateToken`方法，用于**自动登录成功后刷新令牌**（旧令牌更新为新令牌，核心功能）；
3. **支持服务端主动管理令牌**：提供了根据用户ID/令牌删除的方法，实现「退出所有设备」「取消记住我」「异常令牌作废」等业务；
4. **解耦存储层和业务层**：接口隔离了令牌的持久化细节，不管底层用MySQL、Redis还是其他存储，上层`PersistentTokenBasedRememberMeServices`的逻辑完全不变，符合**面向接口编程**思想。

### 三、`PersistentTokenRepository`的核心方法（官方定义）
Spring Security 对这个接口的方法设计非常精简，完全围绕记住我令牌的生命周期，每个方法都有明确的业务用途，和我们之前自定义的`TokenRepository`思路一致，但更贴合框架原生逻辑，核心方法如下：
```java
public interface PersistentTokenRepository {
    // 1. 新增令牌：手动登录成功（勾选记住我）时调用，保存初始令牌
    void createNewToken(PersistentRememberMeToken token);

    // 2. 更新令牌：自动登录成功时调用，刷新令牌（核心！旧令牌→新令牌）
    void updateToken(String series, String tokenValue, Date lastUsed);

    // 3. 根据令牌系列号查询令牌：自动登录时校验令牌合法性
    PersistentRememberMeToken getTokenForSeries(String series);

    // 4. 删除指定令牌：令牌过期/非法/用户单设备退出时调用
    void removeToken(String series);

    // 5. 删除指定用户的所有令牌：用户退出所有设备/改密码/注销时调用（核心兜底）
    void removeUserTokens(String username);
}
```
#### 关键说明：令牌核心实体`PersistentRememberMeToken`
接口中所有方法围绕**`PersistentRememberMeToken`**这个官方令牌实体展开，它是Spring Security定义的标准令牌模型，包含4个核心字段（缺一不可）：
```java
public class PersistentRememberMeToken {
    private final String username;    // 关联的用户名/用户ID
    private final String series;      // 令牌系列号（核心！唯一标识一个令牌系列）
    private final String tokenValue;  // 令牌值（每次自动登录都会刷新）
    private final Date date;          // 令牌最后使用时间（用于过期判断/活跃度校验）
    // 构造器/GETTER
}
```
##### 核心设计：`series`（系列号） + `tokenValue`（令牌值）的双令牌机制
这是Spring Security的经典设计，**也是令牌能安全刷新的关键**，和我们之前自定义的「随机串+签名」令牌不同，官方用**双令牌**实现更优雅的刷新逻辑：
- **`series`（系列号）**：**固定不变**，标识一个用户的「一次记住我会话」（比如用户勾选记住我登录后，series就固定了，直到手动退出/令牌被删）；
- **`tokenValue`（令牌值）**：**每次自动登录都会刷新**，是真正的动态令牌；
- 校验逻辑：自动登录时，先通过`series`查询令牌，再对比`tokenValue`是否一致，一致则刷新`tokenValue`，**series不变，tokenValue用一次就换**。

✅ 优势：即使令牌被窃取，攻击者只能用一次（用户正常自动登录后，tokenValue就刷新了，攻击者的旧tokenValue立即失效），且series固定，方便服务端管理同一个用户的记住我会话。

### 四、`PersistentTokenRepository`的**官方现成实现**（不用自己写）
Spring Security 为了降低开发成本，提供了**基于关系型数据库的默认实现**，我们直接引入即可使用，无需自己实现接口，这也是生产环境最常用的方式：
#### 1. 官方实现类：`JdbcTokenRepositoryImpl`
这是`PersistentTokenRepository`的**JDBC默认实现**，适配所有关系型数据库（MySQL/Oracle/PostgreSQL），底层用JdbcTemplate操作数据库，核心特性：
- 内置了**令牌表的创建语句**，可自动创建表，无需手动建表；
- 实现了接口的所有方法，支持令牌的增、删、改、查、刷新；
- 操作自带**事务性**，保证令牌更新的原子性（删旧/插新/刷新不会出问题）。

#### 2. 快速使用：配置`JdbcTokenRepositoryImpl`（Spring Boot）
只需几步配置，就能将令牌持久化到数据库，配合`PersistentTokenBasedRememberMeServices`实现安全的记住我功能，无需自定义`RememberMeServices`：
##### 步骤1：配置`PersistentTokenRepository` Bean
```java
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;

import javax.sql.DataSource;

@Configuration
public class RememberMeConfig {
    // 注入Spring Boot自动配置的数据源（application.yml配置即可）
    private final DataSource dataSource;

    public RememberMeConfig(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Bean
    public PersistentTokenRepository persistentTokenRepository() {
        JdbcTokenRepositoryImpl tokenRepository = new JdbcTokenRepositoryImpl();
        tokenRepository.setDataSource(dataSource);
        // 关键：设置为true，启动时自动创建记住我令牌表（首次启动后建议改为false，避免重复建表）
        tokenRepository.setCreateTableOnStartup(true);
        return tokenRepository;
    }
}
```
##### 步骤2：自动创建的令牌表结构（Spring Security内置）
设置`setCreateTableOnStartup(true)`后，启动项目会自动创建`persistent_logins`表，这是Spring Security的标准表结构，无需手动修改：
```sql
CREATE TABLE persistent_logins (
    username VARCHAR(64) NOT NULL,  -- 关联用户名
    series VARCHAR(64) PRIMARY KEY, -- 令牌系列号（主键，唯一）
    token VARCHAR(64) NOT NULL,     -- 令牌值（每次自动登录刷新）
    last_used TIMESTAMP NOT NULL    -- 最后使用时间（用于过期判断）
);
```
##### 步骤3：配置Spring Security的记住我功能（关联仓库）
```java
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private final UserDetailsService userDetailsService;
    private final PersistentTokenRepository tokenRepository;
    // 服务端签名密钥（配置文件读取，不要硬编码）
    private static final String REMEMBER_ME_KEY = "your-random-32bit-key-1234567890abcdef";

    public SecurityConfig(UserDetailsService userDetailsService, PersistentTokenRepository tokenRepository) {
        this.userDetailsService = userDetailsService;
        this.tokenRepository = tokenRepository;
    }

    // 配置持久化记住我服务
    @Bean
    public PersistentTokenBasedRememberMeServices rememberMeServices() {
        // 传入：密钥、用户详情服务、令牌仓库
        PersistentTokenBasedRememberMeServices services = new PersistentTokenBasedRememberMeServices(
                REMEMBER_ME_KEY,
                userDetailsService,
                tokenRepository
        );
        // 设置记住我有效期：7天（秒）
        services.setTokenValiditySeconds(7 * 24 * 60 * 60);
        // 设置前端记住我参数名（默认是remember-me，和前端复选框对应）
        services.setParameter("remember-me");
        // 自动设置Cookie的HttpOnly/Secure等安全属性（无需手动配置）
        return services;
    }

    // 配置Security过滤器链，启用记住我
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form.permitAll())
                // 启用记住我，关联自定义的rememberMeServices
                .rememberMe(rm -> rm.rememberMeServices(rememberMeServices()));
        return http.build();
    }
}
```
这样配置后，Spring Security就会自动实现**令牌持久化、自动刷新、Cookie安全配置**，完全不用自己自定义`RememberMeServices`，开箱即用。

### 五、`PersistentTokenRepository`和**自定义TokenRepository**的对比
你之前在自定义`CustomRememberMeService`时写了自己的`TokenRepository`，和官方的`PersistentTokenRepository`对比，核心区别和适用场景如下，帮你选择：
| 特性                | 官方`PersistentTokenRepository` | 自定义`TokenRepository` |
|---------------------|---------------------------------|------------------------|
| 设计规范            | 贴合Spring Security原生设计，标准化 | 自定义灵活，无框架约束  |
| 实现成本            | 官方有`JdbcTokenRepositoryImpl`，开箱即用 | 需自己实现增删改查，适配存储 |
| 令牌刷新机制        | 内置`series+tokenValue`双令牌刷新 | 需自己实现令牌刷新逻辑  |
| 集成难度            | 低，直接配置Bean即可集成        | 高，需自己整合到`CustomRememberMeService` |
| 灵活性              | 较低，字段/逻辑固定（可扩展）| 极高，可自定义令牌字段（如绑定IP/设备） |

#### 适用场景选择：
1. **选官方`PersistentTokenRepository`**：大部分常规业务，追求**开发效率、框架原生集成、稳定性**，无需复杂的令牌自定义规则（如IP/设备绑定）；
2. **选自定义`TokenRepository`**：高安全要求的业务（如金融、电商），需要**自定义令牌规则、绑定设备/IP、自定义签名算法**，或需要适配非关系型数据库（如Redis，官方无Redis实现，需自己写`PersistentTokenRepository`的Redis实现）。

### 六、扩展：实现Redis版的`PersistentTokenRepository`（进阶）
官方仅提供了JDBC实现，如果想将令牌持久化到Redis（性能更高，适合分布式系统），可以**自己实现`PersistentTokenRepository`接口**，基于RedisTemplate操作Redis，核心思路：
1. 用`series`作为Redis的Key，`PersistentRememberMeToken`序列化为Value；
2. 用用户ID作为二级Key，维护一个用户的所有`series`，方便实现「退出所有设备」；
3. 实现接口的所有方法，操作Redis保证原子性。

这是分布式系统中常用的方案，核心代码示例（简化版）：
```java
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.web.authentication.rememberme.PersistentRememberMeToken;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.concurrent.TimeUnit;

@Component
public class RedisTokenRepositoryImpl implements PersistentTokenRepository {
    private final RedisTemplate<String, Object> redisTemplate;
    // Redis键前缀
    private static final String REDIS_KEY_PREFIX = "SPRING_SECURITY_REMEMBER_ME:";
    // 令牌有效期：7天
    private static final long TOKEN_EXPIRE_DAYS = 7;

    public RedisTokenRepositoryImpl(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void createNewToken(PersistentRememberMeToken token) {
        // 存储令牌：key=前缀+series，value=token对象，设置过期时间
        String key = REDIS_KEY_PREFIX + token.getSeries();
        redisTemplate.opsForValue().set(key, token, TOKEN_EXPIRE_DAYS, TimeUnit.DAYS);
        // 维护用户-系列号的关联：key=前缀+username，value=series集合
        redisTemplate.opsForSet().add(REDIS_KEY_PREFIX + token.getUsername(), token.getSeries());
    }

    @Override
    public void updateToken(String series, String tokenValue, Date lastUsed) {
        // 查询旧令牌
        PersistentRememberMeToken oldToken = getTokenForSeries(series);
        if (oldToken != null) {
            // 构建新令牌（series不变，tokenValue和lastUsed刷新）
            PersistentRememberMeToken newToken = new PersistentRememberMeToken(
                    oldToken.getUsername(),
                    series,
                    tokenValue,
                    lastUsed
            );
            // 更新Redis，重置过期时间
            String key = REDIS_KEY_PREFIX + series;
            redisTemplate.opsForValue().set(key, newToken, TOKEN_EXPIRE_DAYS, TimeUnit.DAYS);
        }
    }

    @Override
    public PersistentRememberMeToken getTokenForSeries(String series) {
        String key = REDIS_KEY_PREFIX + series;
        return (PersistentRememberMeToken) redisTemplate.opsForValue().get(key);
    }

    @Override
    public void removeToken(String series) {
        PersistentRememberMeToken token = getTokenForSeries(series);
        if (token != null) {
            // 删除令牌
            redisTemplate.delete(REDIS_KEY_PREFIX + series);
            // 从用户-系列号关联中移除
            redisTemplate.opsForSet().remove(REDIS_KEY_PREFIX + token.getUsername(), series);
        }
    }

    @Override
    public void removeUserTokens(String username) {
        // 查询用户的所有series
        String userKey = REDIS_KEY_PREFIX + username;
        Set<String> seriesSet = (Set<String>) redisTemplate.opsForSet().members(userKey);
        if (seriesSet != null && !seriesSet.isEmpty()) {
            // 批量删除令牌
            seriesSet.forEach(series -> redisTemplate.delete(REDIS_KEY_PREFIX + series));
            // 删除用户-系列号关联
            redisTemplate.delete(userKey);
        }
    }
}
```
配置时只需将`PersistentTokenRepository`的Bean替换为这个Redis实现即可，其他配置完全不变，Spring Security会自动调用。

### 总结
`PersistentTokenRepository`的核心作用和关键知识点可以用3句话概括：
1. 它是Spring Security官方的**持久化记住我令牌标准接口**，定义了令牌增删改查的标准化方法，适配`PersistentTokenBasedRememberMeServices`；
2. 核心设计是**`series`（固定系列号）+ `tokenValue`（动态令牌值）**的双令牌机制，支撑令牌自动刷新，大幅提升记住我功能的安全性；
3. 官方提供`JdbcTokenRepositoryImpl`开箱即用，适配关系型数据库，也可自己实现接口适配Redis（分布式场景），或结合自定义`RememberMeServices`实现更复杂的令牌规则。

简单说，这个接口是Spring Security为了解决「记住我令牌持久化和安全刷新」而设计的**标准化解决方案**，也是生产环境中记住我功能的**首选方案**，比自定义令牌仓库更高效、更贴合框架设计。