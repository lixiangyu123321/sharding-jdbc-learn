是的，**不管是自定义的`TokenRepository`，还是Spring Security官方的`PersistentTokenRepository`，都需要注入到对应的RememberMe服务中手动调用其方法**——因为这类令牌仓库只是「数据持久化工具」，本身不会主动执行任何操作，必须由RememberMe服务在对应时机（登录成功、自动登录、登录失败）调用其增/删/改/查方法，才能完成令牌的生命周期管理。

简单说：**RememberMe服务是「业务执行者」，TokenRepository是「数据操作工具」**，执行者必须持有工具的引用，才能在需要的时候（比如生成令牌、刷新令牌）调用工具完成数据操作，这是典型的**依赖注入+面向接口编程**的设计思路。

下面分**「自定义RememberMeService + 自定义TokenRepository」**和**「官方PersistentTokenBasedRememberMeServices + 官方PersistentTokenRepository」**两种最常用的场景，讲清楚**如何注入、在哪里调用**，这也是你实际开发中会用到的两种方式，对比着看会更清晰。

### 一、场景1：自定义`CustomRememberMeService` + 自定义`TokenRepository`（你的代码场景）
这种场景下，**需要手动注入+手动调用所有方法**，因为整个RememberMe流程都是你自定义的，Spring Security不会帮你做任何自动调用，所有令牌的增删改查都要在三个核心方法中手动触发。

#### 步骤1：给自定义`TokenRepository`加注解，让Spring管理（如`@Repository`/`@Component`）
```java
// 自定义的令牌仓库，标注为Spring组件，方便注入
@Repository
public class MyTokenRepository implements TokenRepository {
    // 实现接口的增删改查方法（操作数据库/Redis）
    @Override
    public TokenInfo selectByToken(String token) { /* 实现 */ }
    @Override
    public void insertToken(String token, String userId, long expireTime) { /* 实现 */ }
    @Override
    public void deleteByToken(String token) { /* 实现 */ }
    @Override
    public void deleteByUserId(String userId) { /* 实现 */ }
}
```

#### 步骤2：在`CustomRememberMeService`中**构造器/`@Autowired`注入**`TokenRepository`
推荐用**构造器注入**（Spring官方推荐，依赖更清晰，避免空指针），而非`@Autowired`字段注入：
```java
public class CustomRememberMeService implements RememberMeServices {

    // 1. 声明令牌仓库依赖（面向接口，不依赖具体实现）
    private final TokenRepository tokenRepository;
    // 其他依赖（用户详情服务、签名密钥等）
    private final UserDetailsService userDetailsService;
    private final String rememberMeKey;

    // 2. 构造器注入：让Spring将MyTokenRepository的实例传入
    // 若用Spring Boot，只需保证TokenRepository有Spring注解，会自动注入
    @Autowired
    public CustomRememberMeService(TokenRepository tokenRepository,
                                   UserDetailsService userDetailsService,
                                   @Value("${security.remember-me.key}") String rememberMeKey) {
        this.tokenRepository = tokenRepository;
        this.userDetailsService = userDetailsService;
        this.rememberMeKey = rememberMeKey;
    }

    // 下面三个核心方法中，手动调用tokenRepository的方法
    @Override
    public Authentication autoLogin(...) { /* 调用tokenRepository */ }
    @Override
    public void loginFail(...) { /* 可选调用 */ }
    @Override
    public void loginSuccess(...) { /* 调用tokenRepository */ }
}
```

#### 步骤3：在三个核心方法中**手动调用**令牌仓库的方法（核心）
根据之前讲的业务逻辑，在对应时机调用增/删/改/查，这是**必须手动做**的，没有任何自动触发：
| 核心方法         | 调用TokenRepository的时机&方法                     | 业务目的                     |
|------------------|---------------------------------------------------|------------------------------|
| `loginSuccess`   | 勾选记住我→生成令牌→**调用insertToken**            | 持久化初始令牌               |
| `autoLogin`      | 解析旧令牌→**调用selectByToken**校验→校验成功→**调用deleteByToken（删旧）+ insertToken（插新）**→校验失败→**调用deleteByToken** | 令牌校验、刷新、清理无效令牌 |
| `loginFail`      | （可选）**调用deleteByUserId**清理该用户旧令牌     | 登录失败后作废残留令牌       |

**示例：核心调用代码片段**（复用之前的逻辑）
```java
// loginSuccess中调用：插入新令牌
tokenRepository.insertToken(newToken, userId, expireTime);

// autoLogin中调用：查询令牌
TokenInfo tokenInfo = tokenRepository.selectByToken(oldToken);
// autoLogin中调用：刷新令牌（删旧插新）
tokenRepository.deleteByToken(oldToken);
tokenRepository.insertToken(newToken, userId, newExpireTime);

// autoLogin中调用：清理无效令牌
tokenRepository.deleteByToken(oldToken);
```

#### 步骤4：将`CustomRememberMeService`注册到Spring Security配置中
最后需要把注入了令牌仓库的自定义服务，配置到Spring Security的过滤器链中，让框架在对应时机调用：
```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   CustomRememberMeService rememberMeService) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .formLogin(form -> form.permitAll())
                // 注册自定义的RememberMeService
                .rememberMe(rm -> rm.rememberMeServices(rememberMeService));
        return http.build();
    }

    // 把CustomRememberMeService声明为Bean，让Spring管理（方便注入到配置中）
    @Bean
    public CustomRememberMeService customRememberMeService(TokenRepository tokenRepository,
                                                           UserDetailsService userDetailsService,
                                                           @Value("${security.remember-me.key}") String rememberMeKey) {
        return new CustomRememberMeService(tokenRepository, userDetailsService, rememberMeKey);
    }
}
```

### 二、场景2：官方`PersistentTokenBasedRememberMeServices` + 官方`PersistentTokenRepository`（开箱即用场景）
这种场景下，**只需注入到Spring容器，无需手动调用方法**——因为官方的`PersistentTokenBasedRememberMeServices`已经内置了所有令牌仓库的调用逻辑（登录成功插令牌、自动登录刷新令牌、校验失败删令牌），你只需要把`PersistentTokenRepository`的Bean注入到Spring容器，再将其传入官方RememberMe服务即可，框架会自动完成所有调用。

简单说：**官方已经帮你写好了「在哪里调用令牌仓库」的逻辑，你只需要做好「依赖注入」**，这也是开箱即用的核心原因。

#### 步骤1：配置`PersistentTokenRepository`的Bean（如Jdbc版/自定义Redis版）
```java
@Configuration
public class RememberMeConfig {
    @Bean
    public PersistentTokenRepository persistentTokenRepository(DataSource dataSource) {
        JdbcTokenRepositoryImpl tokenRepo = new JdbcTokenRepositoryImpl();
        tokenRepo.setDataSource(dataSource);
        tokenRepo.setCreateTableOnStartup(false); // 首次启动后改为false
        return tokenRepo;
    }
}
```

#### 步骤2：将令牌仓库**注入到官方RememberMe服务**中（构造器传入）
```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    // 1. 注入官方的PersistentTokenRepository
    private final PersistentTokenRepository tokenRepository;
    private final UserDetailsService userDetailsService;
    private final String rememberMeKey = "your-random-key";

    // 构造器注入令牌仓库
    public SecurityConfig(PersistentTokenRepository tokenRepository,
                          UserDetailsService userDetailsService) {
        this.tokenRepository = tokenRepository;
        this.userDetailsService = userDetailsService;
    }

    // 2. 配置官方RememberMe服务，将令牌仓库传入
    @Bean
    public PersistentTokenBasedRememberMeServices rememberMeServices() {
        PersistentTokenBasedRememberMeServices services = new PersistentTokenBasedRememberMeServices(
                rememberMeKey,
                userDetailsService,
                tokenRepository // 核心：传入令牌仓库，官方服务会自动调用其方法
        );
        services.setTokenValiditySeconds(7 * 24 * 60 * 60);
        return services;
    }

    // 3. 注册到Spring Security配置
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .formLogin(form -> form.permitAll())
                .rememberMe(rm -> rm.rememberMeServices(rememberMeServices()));
        return http.build();
    }
}
```

#### 关键：官方服务会**自动调用**令牌仓库的方法
你无需写任何调用代码，`PersistentTokenBasedRememberMeServices`内部已经在对应时机调用了`PersistentTokenRepository`的所有方法，对应关系如下（框架底层已实现）：
| 官方RememberMe服务的操作 | 自动调用官方令牌仓库的方法       | 触发时机                     |
|--------------------------|----------------------------------|------------------------------|
| 登录成功生成令牌         | `createNewToken`                 | 勾选记住我，手动登录成功     |
| 自动登录刷新令牌         | `getTokenForSeries` + `updateToken` | 自动登录，令牌校验成功       |
| 令牌非法/过期            | `removeToken`                    | 自动登录，令牌校验失败       |
| 退出所有设备             | `removeUserTokens`               | 调用退出接口，手动触发       |

### 三、核心区别：「自定义场景手动调用」vs「官方场景自动调用」
| 场景                | 注入方式       | 令牌仓库方法调用 | 核心原因                     |
|---------------------|----------------|------------------|------------------------------|
| 自定义RememberMeService + 自定义TokenRepository | 构造器/@Autowired | 完全手动调用     | 整个RememberMe流程自定义，框架无内置逻辑 |
| 官方PersistentTokenBasedRememberMeServices + 官方PersistentTokenRepository | 构造器传入     | 框架自动调用     | 官方服务已内置所有业务与数据操作的绑定逻辑 |

**核心结论**：**注入是必须的，调用是否手动取决于RememberMe服务是否是自定义的**——只要是你自己写的`RememberMeServices`实现，就必须手动调用令牌仓库的方法；如果用Spring Security官方的RememberMe服务，就只需注入，无需手动调用。

### 四、额外避坑点
1. **令牌仓库必须是Spring管理的Bean**：只有给TokenRepository加`@Repository`/`@Component`/`@Bean`等注解，让Spring创建并管理其实例，才能成功注入到RememberMe服务中，否则会报`NoSuchBeanDefinitionException`；
2. **推荐构造器注入**：避免使用`@Autowired`字段注入，构造器注入能保证依赖在对象创建时就初始化完成，防止空指针异常，也让依赖关系更清晰；
3. **分布式场景注意原子性**：如果令牌仓库是Redis/MongoDB等分布式存储，调用其方法时要保证**操作原子性**（比如数据库事务、Redis原子操作），尤其是刷新令牌时的「删旧令牌+插新令牌」，避免数据不一致；
4. **退出登录必须手动调用清理方法**：不管是自定义还是官方场景，**系统的「退出登录」接口中，都需要手动调用令牌仓库的`deleteByUserId`/`removeUserTokens`方法**，并清理前端Cookie，否则记住我令牌仍有效，攻击者可继续自动登录。

### 总结
1. TokenRepository**必须注入到对应的RememberMe服务中**才能使用，它是RememberMe服务的依赖组件，无注入则无法完成令牌的持久化；
2. 调用方式分两种：**自定义RememberMeService需完全手动调用令牌仓库的增删改查方法**，**官方RememberMeService由框架自动调用**，无需手动操作；
3. 注入的核心方式是**构造器注入**（Spring官方推荐），且令牌仓库必须被Spring管理（加对应注解）；
4. 本质是**业务执行者（RememberMeService）持有数据工具（TokenRepository）的引用**，在对应业务时机调用工具完成数据操作，是典型的面向接口编程思想。

你的`CustomRememberMeService`属于自定义场景，所以接下来只需要给你的TokenRepository加Spring注解，构造器注入到自定义服务中，再在`autoLogin`/`loginSuccess`/`loginFail`中手动调用其方法即可。