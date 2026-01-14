# Sharding-JDBC 与 SpringBoot 配置冲突问题及解决方案
在 SpringBoot 集成 Sharding-JDBC 过程中，因自动配置、数据源优先级、Bean 加载顺序等问题，极易出现配置冲突，导致分片/读写分离失效、数据源加载异常、Bean 重复定义等问题。本文梳理**核心冲突场景**及**可落地的解决方案**，适配 Sharding-JDBC 4.x/5.x + SpringBoot 2.x 版本。

## 一、核心冲突场景及表现
### 1.1 数据源自动配置冲突
#### 冲突表现
- SpringBoot 原生 `DataSourceAutoConfiguration` 自动加载默认数据源（如 HikariCP），覆盖 Sharding-JDBC 生成的分片/读写分离数据源；
- 报错：`No qualifying bean of type 'javax.sql.DataSource' available` 或 `DataSource bean already defined`；
- 分片规则不生效，SQL 直接操作物理表而非逻辑表。

#### 根本原因
SpringBoot 自动配置优先级高于自定义 Sharding-JDBC 数据源配置，且默认扫描 `application.yml` 中的 `spring.datasource` 配置生成原生数据源 Bean。

### 1.2 配置属性前缀冲突
#### 冲突表现
- 自定义 Sharding-JDBC 配置（如 `spring.shardingsphere`）与 SpringBoot 内置属性重名；
- 分片规则配置不生效（如分表算法、主键生成策略），日志无报错但数据未分片；
- 读写分离主从切换失效，所有 SQL 均走主库/从库。

### 1.3 Bean 加载顺序冲突
#### 冲突表现
- MyBatis/MyBatis-Plus 的 `SqlSessionFactory` 提前初始化，未使用 Sharding-JDBC 数据源；
- 报错：`Invalid bound statement (not found)` 或 SQL 执行时未经过 Sharding-JDBC 拦截；
- 事务管理器（`DataSourceTransactionManager`）绑定原生数据源，导致分片事务异常。

### 1.4 多数据源 + Sharding-JDBC 冲突
#### 冲突表现
- 项目中同时存在 Sharding-JDBC 数据源和普通业务数据源，导致数据源 Bean 名称冲突；
- 部分 Mapper 绑定错误的数据源，读写分离/分片仅对部分表生效。

## 二、通用解决方案（核心配置）
### 2.1 禁用 SpringBoot 原生数据源自动配置
**第一步**：在 SpringBoot 启动类添加注解，排除原生数据源自动配置类：
```java
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;

// 核心：排除数据源自动配置和事务管理器自动配置
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,          // 禁用原生数据源自动配置
        DataSourceTransactionManagerAutoConfiguration.class // 禁用原生事务管理器自动配置
})
public class ShardingJdbcDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShardingJdbcDemoApplication.class, args);
    }
}
```

**第二步**：删除 `application.yml` 中原生数据源配置（避免冲突），仅保留 Sharding-JDBC 配置：
```yaml
# 移除原生数据源配置（如下配置需删除）
# spring:
#   datasource:
#     driver-class-name: com.mysql.cj.jdbc.Driver
#     url: jdbc:mysql://localhost:3306/test
#     username: root
#     password: 123456

# 仅保留 Sharding-JDBC 配置
spring:
  shardingsphere:
    datasource:
      names: ds0,ds1  # 分片数据源名称
      ds0:
        type: com.zaxxer.hikari.HikariDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        jdbc-url: jdbc:mysql://localhost:3306/ds0?useUnicode=true&characterEncoding=utf8
        username: root
        password: 123456
      ds1:
        type: com.zaxxer.hikari.HikariDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        jdbc-url: jdbc:mysql://localhost:3306/ds1?useUnicode=true&characterEncoding=utf8
        username: root
        password: 123456
    sharding:
      tables:
        t_order:
          actual-data-nodes: ds${0..1}.t_order${0..1}
          table-strategy:
            inline:
              sharding-column: order_id
              algorithm-expression: t_order${order_id % 2}
    props:
      sql-show: true  # 开启SQL打印，验证分片是否生效
```

### 2.2 手动配置 Sharding-JDBC 数据源 Bean（优先级最高）
通过自定义配置类手动生成 Sharding-JDBC 数据源 Bean，避免自动配置冲突，适用于复杂场景（如分片+读写分离+多数据源）。

#### 完整配置类示例
```java
import com.zaxxer.hikari.HikariDataSource;
import org.apache.shardingsphere.api.config.sharding.ShardingRuleConfiguration;
import org.apache.shardingsphere.api.config.sharding.TableRuleConfiguration;
import org.apache.shardingsphere.api.config.sharding.strategy.InlineShardingStrategyConfiguration;
import org.apache.shardingsphere.shardingjdbc.api.ShardingDataSourceFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

@Configuration
public class ShardingDataSourceConfig {

    // 核心：手动构建分片数据源，@Primary 确保优先级最高
    @Bean(name = "shardingDataSource")
    @Primary
    public DataSource shardingDataSource() throws SQLException {
        // 1. 配置分片规则
        ShardingRuleConfiguration shardingRuleConfig = new ShardingRuleConfiguration();
        TableRuleConfiguration orderTableRule = new TableRuleConfiguration("t_order", "ds${0..1}.t_order${0..1}");
        // 分表策略
        orderTableRule.setTableShardingStrategyConfig(
                new InlineShardingStrategyConfiguration("order_id", "t_order${order_id % 2}")
        );
        shardingRuleConfig.getTableRuleConfigs().add(orderTableRule);

        // 2. 配置真实数据源
        Map<String, DataSource> dataSourceMap = new HashMap<>();
        dataSourceMap.put("ds0", createHikariDataSource("jdbc:mysql://localhost:3306/ds0", "root", "123456"));
        dataSourceMap.put("ds1", createHikariDataSource("jdbc:mysql://localhost:3306/ds1", "root", "123456"));

        // 3. 创建 Sharding-JDBC 数据源
        return ShardingDataSourceFactory.createDataSource(
                dataSourceMap, shardingRuleConfig, new Properties()
        );
    }

    // 构建 HikariCP 数据源
    private DataSource createHikariDataSource(String jdbcUrl, String username, String password) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(jdbcUrl);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setMaximumPoolSize(10);
        dataSource.setMinimumIdle(2);
        return dataSource;
    }

    // 配置事务管理器，绑定 Sharding-JDBC 数据源
    @Bean
    public PlatformTransactionManager transactionManager(DataSource shardingDataSource) {
        return new DataSourceTransactionManager(shardingDataSource);
    }
}
```

#### 关键说明
- `@Primary`：确保 Sharding-JDBC 数据源 Bean 为默认数据源，避免 MyBatis/事务管理器绑定错误数据源；
- 手动构建数据源而非依赖 `application.yml` 自动解析，完全掌控配置逻辑；
- 事务管理器必须绑定 Sharding-JDBC 数据源，否则分片事务会异常。

### 2.3 MyBatis/MyBatis-Plus 适配（避免 SqlSessionFactory 冲突）
#### 问题表现
MyBatis-Plus 自动配置的 `SqlSessionFactory` 绑定原生数据源，导致 Mapper 执行 SQL 不经过 Sharding-JDBC 拦截。

#### 解决方案：手动配置 SqlSessionFactory
```java
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.annotation.Resource;
import javax.sql.DataSource;

@Configuration
// 指定 Mapper 扫描路径，绑定 Sharding-JDBC 数据源
@MapperScan(basePackages = "com.xxx.mapper", sqlSessionTemplateRef = "sqlSessionTemplate")
public class MyBatisConfig {

    @Resource(name = "shardingDataSource") // 注入 Sharding-JDBC 数据源
    private DataSource dataSource;

    @Bean
    public SqlSessionFactory sqlSessionFactory() throws Exception {
        SqlSessionFactoryBean sessionFactoryBean = new SqlSessionFactoryBean();
        sessionFactoryBean.setDataSource(dataSource); // 绑定分片数据源
        // 配置 Mapper 映射文件路径
        sessionFactoryBean.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources("classpath:mapper/*.xml")
        );
        // MyBatis 配置（可选）
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true); // 驼峰转换
        sessionFactoryBean.setConfiguration(configuration);
        return sessionFactoryBean.getObject();
    }

    @Bean
    public SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }
}
```

### 2.4 多数据源 + Sharding-JDBC 冲突解决
#### 场景
项目中同时存在：
- Sharding-JDBC 分片数据源（处理 `t_order`/`t_order_item` 等表）；
- 普通业务数据源（处理 `t_user`/`t_config` 等非分片表）。

#### 解决方案：数据源路由 + 注解切换
**第一步**：定义数据源枚举和上下文持有类
```java
public enum DataSourceType {
    SHARDING, // 分片数据源
    BUSINESS  // 普通业务数据源
}

// 数据源上下文（ThreadLocal 存储当前线程数据源类型）
public class DataSourceContextHolder {
    private static final ThreadLocal<DataSourceType> CONTEXT = new ThreadLocal<>();

    public static void setDataSourceType(DataSourceType type) {
        CONTEXT.set(type);
    }

    public static DataSourceType getDataSourceType() {
        return CONTEXT.get() == null ? DataSourceType.SHARDING : CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
```

**第二步**：自定义动态数据源路由
```java
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

public class DynamicDataSource extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() {
        return DataSourceContextHolder.getDataSourceType();
    }
}
```

**第三步**：配置动态数据源 Bean
```java
@Configuration
public class DynamicDataSourceConfig {

    // 分片数据源
    @Bean(name = "shardingDataSource")
    public DataSource shardingDataSource() throws SQLException {
        // 同 2.2 节的分片数据源配置
    }

    // 普通业务数据源
    @Bean(name = "businessDataSource")
    public DataSource businessDataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:mysql://localhost:3306/business");
        dataSource.setUsername("root");
        dataSource.setPassword("123456");
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return dataSource;
    }

    // 动态数据源（核心：整合分片+业务数据源）
    @Bean(name = "dynamicDataSource")
    @Primary
    public DataSource dynamicDataSource() throws SQLException {
        DynamicDataSource dynamicDataSource = new DynamicDataSource();
        // 配置数据源映射
        Map<Object, Object> dataSourceMap = new HashMap<>();
        dataSourceMap.put(DataSourceType.SHARDING, shardingDataSource());
        dataSourceMap.put(DataSourceType.BUSINESS, businessDataSource());
        dynamicDataSource.setTargetDataSources(dataSourceMap);
        // 默认数据源：分片数据源
        dynamicDataSource.setDefaultTargetDataSource(shardingDataSource());
        return dynamicDataSource;
    }

    // 事务管理器绑定动态数据源
    @Bean
    public PlatformTransactionManager transactionManager(DataSource dynamicDataSource) {
        return new DataSourceTransactionManager(dynamicDataSource);
    }
}
```

**第四步**：自定义注解 + AOP 切换数据源
```java
// 自定义数据源切换注解
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DataSource {
    DataSourceType value() default DataSourceType.SHARDING;
}

// AOP 切面实现数据源切换
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // 确保 AOP 优先级最高
public class DataSourceAspect {

    @Before("@annotation(dataSource)")
    public void setDataSource(DataSource dataSource) {
        DataSourceContextHolder.setDataSourceType(dataSource.value());
    }

    @After("@annotation(dataSource)")
    public void clearDataSource() {
        DataSourceContextHolder.clear();
    }
}
```

**第五步**：Mapper/Service 中使用注解切换
```java
// 分片表 Mapper（默认使用分片数据源，无需注解）
public interface OrderMapper extends BaseMapper<OrderDO> {}

// 普通业务表 Mapper（使用注解切换到业务数据源）
@Service
public class UserService {
    @Resource
    private UserMapper userMapper;

    @DataSource(DataSourceType.BUSINESS) // 切换到普通业务数据源
    public UserDO getUserById(Long id) {
        return userMapper.selectById(id);
    }
}
```

## 三、常见冲突排查技巧
### 3.1 日志排查
开启 Sharding-JDBC 详细日志，验证配置是否生效：
```yaml
logging:
  level:
    org.apache.shardingsphere: DEBUG  # 打印 Sharding-JDBC 核心日志
    com.zaxxer.hikari: INFO          # 数据源连接日志
```
- 日志中搜索 `Actual SQL`：验证分片后执行的物理 SQL 是否正确；
- 搜索 `ShardingRuleConfiguration`：验证分片规则是否加载；
- 搜索 `DataSource`：验证加载的数据源是否为 Sharding-JDBC 数据源（类名包含 `ShardingDataSource`）。

### 3.2 数据源 Bean 校验
在启动类/配置类中添加调试代码，校验数据源类型：
```java
@Autowired
private ApplicationContext context;

@PostConstruct
public void checkDataSource() {
    DataSource dataSource = context.getBean(DataSource.class);
    // 正确类型：org.apache.shardingsphere.shardingjdbc.jdbc.core.ShardingDataSource
    // 错误类型：com.zaxxer.hikari.HikariDataSource
    System.out.println("当前数据源类型：" + dataSource.getClass().getName());
}
```

### 3.3 依赖冲突排查
排除 SpringBoot 中与 Sharding-JDBC 冲突的依赖（如旧版 JDBC 驱动、数据源依赖）：
```xml
<!-- pom.xml 排除冲突依赖 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
    <exclusions>
        <exclusion>
            <groupId>com.zaxxer</groupId>
            <artifactId>HikariCP</artifactId>
        </exclusion>
    </exclusions>
</dependency>

<!-- 明确指定 Sharding-JDBC 版本，避免依赖传递导致版本不一致 -->
<dependency>
    <groupId>org.apache.shardingsphere</groupId>
    <artifactId>sharding-jdbc-spring-boot-starter</artifactId>
    <version>4.1.1</version>
</dependency>
```

## 四、总结
### 核心解决原则
1. **禁用原生自动配置**：排除 `DataSourceAutoConfiguration`，避免 SpringBoot 生成默认数据源；
2. **手动掌控数据源**：通过 `@Bean + @Primary` 手动配置 Sharding-JDBC 数据源，确保优先级；
3. **绑定核心组件**：将 MyBatis `SqlSessionFactory`、事务管理器绑定 Sharding-JDBC 数据源；
4. **多数据源隔离**：通过动态数据源路由 + AOP 实现分片/普通数据源切换，避免冲突。

### 关键注意事项
- Sharding-JDBC 4.x 和 5.x 配置语法差异较大，需匹配对应版本（5.x 更名为 ShardingSphere-JDBC，配置前缀改为 `spring.shardingsphere.datasource`）；
- 避免在 `application.yml` 中同时配置原生数据源和 Sharding-JDBC 数据源；
- 事务管理器必须绑定 Sharding-JDBC 数据源，否则分片事务会出现数据一致性问题。