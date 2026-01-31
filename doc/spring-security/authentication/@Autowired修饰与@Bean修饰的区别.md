# @Autowired 修饰方法（有/无返回值）及与@Bean的区别总结
## 一、@Autowired 修饰无返回值方法 vs 有返回值方法
核心共性：**均为Spring原生方法级注入特性**，容器初始化时会自动匹配注入方法参数，且执行方法逻辑；仅在「是否将返回值注册为容器Bean」上存在核心差异，适用于所有Spring项目（非Spring Security独有）。

| 对比维度                | @Autowired + 无返回值方法                | @Autowired + 有返回值方法                |
|-------------------------|------------------------------------------|------------------------------------------|
| 核心执行逻辑            | 注入方法参数 → 执行方法逻辑              | 注入方法参数 → 执行方法逻辑 → 注册返回值为Bean |
| 返回值处理              | 无返回值，不做任何Bean注册操作           | 返回值自动注册为Spring容器Bean；返回null则不注册 |
| 注册Bean规则            | 无                                       | 1. Bean类型：方法返回值类型（支持多态）<br>2. Bean名称：方法名首字母小写<br>3. 可被@Autowired/@Resource正常注入 |
| 核心使用场景            | 对容器中已存在的Bean做**配置/初始化**，无新Bean产生（如Spring Security中配置AuthenticationManagerBuilder） | 「注入依赖配置Bean」+「生产新Bean」，利用注入的参数配置对象后，将对象注册为容器Bean（附加效果） |
| 示例                    | ```java<br>@Autowired<br>public void configure(AuthenticationManagerBuilder builder) {<br>    builder.eraseCredentials(false);<br>}<br>``` | ```java<br>@Autowired<br>public UserService createUserService(MyDataSource dataSource) {<br>    UserService service = new UserService();<br>    service.setDatasource(dataSource);<br>    return service;<br>}<br>``` |

## 二、@Autowired（有返回值方法）与@Bean的核心区别
两者均能将方法返回值注册为Spring Bean，且方法参数均支持注入，但**核心定位、设计初衷、使用规则**存在本质差异；**@Bean是Spring官方推荐的Bean注册方式**，@Autowired+有返回值仅为「注入为核心，注册Bean为附加效果」。

| 对比维度                | @Autowired + 有返回值方法                | @Bean 方法                |
|-------------------------|------------------------------------------|--------------------------|
| 核心设计初衷            | 以**方法参数注入**为核心，Bean注册是执行方法后的**附加效果** | 以**注册Bean**为核心，方法参数注入是框架提供的**语法糖（附加能力）** |
| 方法参数注入规则        | 必须加@Autowired注解，否则参数**不会注入**（默认传null） | 无需加任何注解，参数**自动从容器按类型注入**（Spring专属支持） |
| Bean注册优先级          | 低，若同类型Bean被@Bean注册，会被**@Bean的Bean覆盖** | 高，Spring官方推荐的Bean注册方式，优先级最高 |
| 语义化程度              | 低，阅读代码时无法快速识别「此方法用于注册Bean」 | 高，注解直接标识「此方法用于生产并注册Bean」，符合Spring「约定优于配置」 |
| 冗余性                  | 存在冗余（需同时满足「@Autowired注入」+「返回值」才会注册Bean） | 无冗余，单一注解完成「Bean生产+参数依赖注入」 |
| 推荐使用场景            | 极少使用，仅适用于「需注入依赖配置对象，且顺带注册为Bean」的极端场景 | 所有**手动注册Bean**的场景（如配置第三方组件、自定义核心服务），是开发首选 |
| 相同效果代码示例        | ```java<br>// 必须加@Autowired，否则builder为null<br>@Autowired<br>public AuthenticationManager authManager(AuthenticationManagerBuilder builder) throws Exception {<br>    return builder.userDetailsService(userService()).build();<br>}<br>``` | ```java<br>// 无需加注解，参数自动注入（推荐）<br>@Bean<br>public AuthenticationManager authManager(AuthenticationManagerBuilder builder) throws Exception {<br>    return builder.userDetailsService(userService()).build();<br>}<br>``` |

## 三、通用关键补充
1. **@Autowired的注入规则**：无论修饰有/无返回值方法，参数均**默认按类型注入**；同类型多Bean时，可通过`@Qualifier("bean名")`显式指定，或`@Primary`设置首选Bean。
2. **@Bean的参数注入规则**：与@Autowired完全一致（默认按类型，多Bean支持`@Qualifier/@Primary`），且无需额外注解，框架自动触发。
3. **特殊情况处理**：
    - @Autowired+有返回值方法返回`null`：等同于无返回值方法，仅注入参数+执行方法，不注册任何Bean；
    - 同一类中多个方法注册同类型Bean：按「@Bean > @Autowired+有返回值」的优先级覆盖，同类型@Bean则按方法执行顺序覆盖（不推荐此写法）。
4. **Spring Security中的特殊约定**：
    - @Autowired+无返回值方法被高频用于配置`AuthenticationManagerBuilder`（如关闭凭证擦除、注册认证提供者），是框架**约定用法**，底层仍为Spring原生特性；
    - Spring Security中注册认证相关Bean（如`AuthenticationManager`/`UserDetailsService`），**优先使用@Bean**，而非@Autowired+有返回值。

## 四、开发最佳实践
1. **仅配置/初始化Bean**：使用`@Autowired + 无返回值方法`，专注于对容器中已存在Bean的自定义配置（如Security认证构建器、RedisTemplate序列化配置）。
2. **需要注册新Bean**：**坚决使用@Bean注解**，语义化更强、规则更清晰，避免使用@Autowired+有返回值的冗余写法。
3. **参数注入**：
    - @Autowired方法：必须显式加注解，多Bean注入配合`@Qualifier`；
    - @Bean方法：不写冗余的@Autowired，多Bean注入直接在参数前加`@Qualifier`。
4. **多实例Bean处理**：无论哪种方式，同类型多Bean时均**优先用@Qualifier("bean名")** 显式指定，拒绝依赖「参数名匹配Bean名」的兜底逻辑，提升代码健壮性。