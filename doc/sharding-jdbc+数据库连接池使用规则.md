你想知道基于 `ShardingDataSourceFactory.createDataSource()` 创建的 Sharding-JDBC 数据源，是否能结合数据库连接池使用——核心结论先明确：
**完全可以，而且这是生产环境的标配**！`ShardingDataSourceFactory` 接收的 `dataSourceMap` 本身就应该传入**连接池类型的 DataSource（如 HikariDataSource）**，而非原生的 `DriverManagerDataSource`，Sharding-JDBC 会基于这些连接池数据源管理分片连接，大幅提升性能。

### 一、核心逻辑：Sharding-JDBC 与连接池的关系
Sharding-JDBC 的核心是**分库分表的代理数据源**（`ShardingDataSource`），它本身不管理连接，而是依赖你传入的 `dataSourceMap` 中的每个数据源（对应物理库）来管理连接——因此，你只需要给每个物理库配置连接池数据源（如 HikariCP/Druid），Sharding-JDBC 就会自动复用这些连接池的连接。

简单理解：
```
ShardingDataSource（代理） 
├── 物理库1：HikariDataSource（连接池）
├── 物理库2：HikariDataSource（连接池）
└── 物理库3：HikariDataSource（连接池）
```

### 二、实战示例：创建带连接池的 Sharding-JDBC 数据源
#### 1. 步骤拆解
1. 为每个物理数据库创建**连接池数据源**（如 HikariCP）；
2. 将这些连接池数据源放入 `dataSourceMap`；
3. 传入 `ShardingDataSourceFactory.createDataSource()` 创建 Sharding-JDBC 代理数据源；
4. 最终的 `ShardingDataSource` 会复用底层连接池的连接，而非每次创建新连接。

#### 2. 完整代码示例（基于 HikariCP 连接池）
```java
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.shardingsphere.api.config.sharding.ShardingRuleConfiguration;
import org.apache.shardingsphere.api.config.sharding.TableRuleConfiguration;
import org.apache.shardingsphere.api.config.sharding.strategy.InlineShardingStrategyConfiguration;
import org.apache.shardingsphere.api.config.props.ConfigurationPropertyKey;
import org.apache.shardingsphere.shardingjdbc.api.ShardingDataSourceFactory;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class ShardingJdbcWithPoolDemo {

    public static void main(String[] args) throws SQLException {
        // 步骤1：创建物理库的连接池数据源（HikariCP）
        Map<String, DataSource> dataSourceMap = new HashMap<>();
        
        // 物理库1：ds0（HikariCP 连接池）
        HikariDataSource ds0 = createHikariDataSource("jdbc:mysql://localhost:3306/test_db0?serverTimezone=Asia/Shanghai", "root", "123456");
        dataSourceMap.put("ds0", ds0);
        
        // 物理库2：ds1（HikariCP 连接池）
        HikariDataSource ds1 = createHikariDataSource("jdbc:mysql://localhost:3306/test_db1?serverTimezone=Asia/Shanghai", "root", "123456");
        dataSourceMap.put("ds1", ds1);

        // 步骤2：配置分片规则
        ShardingRuleConfiguration shardingRuleConfig = new ShardingRuleConfiguration();
        // 配置t_order表的分片规则（按user_id分库，按order_id分表）
        TableRuleConfiguration orderTableRule = new TableRuleConfiguration("t_order", "ds${0..1}.t_order_${0..1}");
        // 分库策略：user_id % 2 → ds0/ds1
        orderTableRule.setDatabaseShardingStrategyConfig(new InlineShardingStrategyConfiguration("user_id", "ds${user_id % 2}"));
        // 分表策略：order_id % 2 → t_order_0/t_order_1
        orderTableRule.setTableShardingStrategyConfig(new InlineShardingStrategyConfiguration("order_id", "t_order_${order_id % 2}"));
        shardingRuleConfig.getTableRuleConfigs().add(orderTableRule);

        // 步骤3：配置Sharding-JDBC属性（可选）
        Properties props = new Properties();
        props.setProperty(ConfigurationPropertyKey.SQL_SHOW.getKey(), "true"); // 打印改写后的SQL
        props.setProperty(ConfigurationPropertyKey.CONNECTION_TIMEOUT_MILLIS.getKey(), "3000"); // 连接超时

        // 步骤4：创建Sharding-JDBC数据源（基于连接池数据源）
        DataSource shardingDataSource = ShardingDataSourceFactory.createDataSource(dataSourceMap, shardingRuleConfig, props);
        
        // 后续使用：shardingDataSource 底层复用 HikariCP 连接池，性能最优
        // 比如注入到JdbcTemplate/MyBatis中使用
    }

    /**
     * 创建HikariCP连接池数据源
     */
    private static HikariDataSource createHikariDataSource(String jdbcUrl, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        // 连接池核心参数配置（生产环境必配）
        config.setMaximumPoolSize(10); // 每个物理库的最大连接数
        config.setMinimumIdle(2); // 最小空闲连接数
        config.setConnectionTimeout(3000); // 连接超时时间（ms）
        config.setIdleTimeout(60000); // 空闲连接超时时间（ms）
        config.setDriverClassName("com.mysql.cj.jdbc.Driver"); // MySQL 8.x 驱动
        return new HikariDataSource(config);
    }
}
```

### 三、关键说明
#### 1. 为什么必须用连接池？
- Sharding-JDBC 本身不管理连接，若传入原生 `DriverManagerDataSource`（无连接池），每次执行 SQL 都会创建新的数据库连接（重量级操作），性能极差；
- 传入连接池数据源后，Sharding-JDBC 从连接池获取/归还连接，复用连接大幅提升并发性能。

#### 2. 连接池参数的配置原则
每个物理库的连接池参数（如 `maximumPoolSize`）需单独配置：
- 假设分2个物理库，每个库的 `maximumPoolSize=10`，则整体最大连接数为 2×10=20；
- 避免单个库的连接数过大（如超过20），导致数据库连接耗尽。

#### 3. Spring Boot 中更简洁的方式
在 Spring Boot 中无需手动调用 `ShardingDataSourceFactory`，只需配置 `application.yml`，Sharding-JDBC 会自动使用 Spring Boot 默认的 HikariCP 连接池：
```yaml
spring:
  shardingsphere:
    datasource:
      names: ds0,ds1
      # 配置每个物理库的连接池参数（HikariCP）
      ds0:
        type: com.zaxxer.hikari.HikariDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        jdbc-url: jdbc:mysql://localhost:3306/test_db0?serverTimezone=Asia/Shanghai
        username: root
        password: 123456
        hikari:
          maximum-pool-size: 10
          minimum-idle: 2
      ds1:
        type: com.zaxxer.hikari.HikariDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        jdbc-url: jdbc:mysql://localhost:3306/test_db1?serverTimezone=Asia/Shanghai
        username: root
        password: 123456
        hikari:
          maximum-pool-size: 10
          minimum-idle: 2
    rules:
      sharding:
        tables:
          t_order:
            actual-data-nodes: ds${0..1}.t_order_${0..1}
            database-strategy:
              inline:
                sharding-column: user_id
                algorithm-expression: ds${user_id % 2}
            table-strategy:
              inline:
                sharding-column: order_id
                algorithm-expression: t_order_${order_id % 2}
    props:
      sql-show: true
```

### 四、总结
1. **核心结论**：可以且必须在 `ShardingDataSourceFactory.createDataSource()` 基础上使用连接池——只需将 `dataSourceMap` 中的每个数据源替换为连接池类型（如 HikariCP/Druid）；
2. **实现方式**：为每个物理库创建连接池数据源，传入 `ShardingDataSourceFactory`，最终的 `ShardingDataSource` 会复用底层连接池的连接；
3. **生产标配**：原生 Sharding-JDBC 不管理连接，连接池是提升性能的核心，Spring Boot 中可通过配置自动集成 HikariCP；
4. **关键原则**：每个物理库独立配置连接池参数，避免连接数过度消耗。

简单记：Sharding-JDBC 是“分库分表代理”，连接池是“连接管理器”，两者结合才是生产环境的最优方案，`dataSourceMap` 传入连接池数据源即可实现。