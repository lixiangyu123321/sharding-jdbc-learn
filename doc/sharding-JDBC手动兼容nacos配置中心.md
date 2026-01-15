你想知道在使用 4.1.1 版本的 `sharding-jdbc-spring-boot-starter` 时，该如何集成 Nacos 作为配置中心，这是一个很实际的版本兼容问题——因为 4.x 版本和 5.x 版本的配置中心集成方式差异很大。

### 核心结论
ShardingSphere 4.1.1 版本**没有原生支持 Nacos 作为配置中心**（4.x 版本的治理模块仅原生支持 Zookeeper/Etcd/Consul），但你可以通过**自定义实现**的方式，让 4.1.1 版本对接 Nacos，核心思路是：从 Nacos 拉取分片配置，手动注入到 ShardingSphere 的配置体系中。

### 一、4.1.1 版本集成 Nacos 的实现方案
#### 1. 引入必要依赖
在 `pom.xml` 中添加 Nacos 客户端依赖（和 ShardingSphere 4.1.1 兼容）：
```xml
<!-- Sharding-JDBC 4.1.1 核心依赖 -->
<dependency>
    <groupId>org.apache.shardingsphere</groupId>
    <artifactId>sharding-jdbc-spring-boot-starter</artifactId>
    <version>4.1.1</version>
</dependency>

<!-- Nacos 配置中心客户端 -->
<dependency>
    <groupId>com.alibaba.nacos</groupId>
    <artifactId>nacos-client</artifactId>
    <version>1.4.2</version> <!-- 选择和 4.1.1 兼容的稳定版本 -->
</dependency>

<!-- 配置解析依赖（用于解析 Nacos 中的 YAML/Properties 配置） -->
<dependency>
    <groupId>org.yaml</groupId>
    <artifactId>snakeyaml</artifactId>
    <version>1.29</version>
</dependency>
```

#### 2. 自定义 Nacos 配置加载类
核心逻辑：启动时从 Nacos 拉取分片配置，解析后手动构建 ShardingSphere 的数据源配置。
```java
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import org.apache.shardingsphere.api.config.sharding.ShardingRuleConfiguration;
import org.apache.shardingsphere.api.config.sharding.TableRuleConfiguration;
import org.apache.shardingsphere.api.config.sharding.strategy.InlineShardingStrategyConfiguration;
import org.apache.shardingsphere.shardingjdbc.api.ShardingDataSourceFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

@Configuration
public class ShardingNacosConfig {

    // Nacos 配置参数（可配置在本地 application.properties 中）
    @Value("${nacos.server-addr:localhost:8848}")
    private String nacosServerAddr;
    @Value("${nacos.namespace:public}")
    private String nacosNamespace;
    @Value("${nacos.data-id:sharding-rule-4.1.1.yaml}")
    private String nacosDataId;
    @Value("${nacos.group:DEFAULT_GROUP}")
    private String nacosGroup;

    /**
     * 从 Nacos 加载分片配置，构建 ShardingDataSource
     */
    @Bean
    @Primary // 优先使用该数据源，覆盖默认的自动配置
    public DataSource shardingDataSource() throws SQLException, NacosException {
        // 1. 从 Nacos 获取分片配置（这里简化处理，也可以解析 YAML 配置）
        String shardingConfig = getConfigFromNacos();
        
        // 2. 构建数据源配置（实际可解析 Nacos 中的 YAML 配置，这里手动构建示例）
        Map<String, DataSource> dataSourceMap = createDataSourceMap();
        
        // 3. 构建分片规则配置（核心：对应你原来的分片规则）
        ShardingRuleConfiguration shardingRuleConfig = new ShardingRuleConfiguration();
        
        // 配置 t_order 表规则
        TableRuleConfiguration orderTableRule = new TableRuleConfiguration("t_order", "ds${0..1}.t_order${0..1}");
        orderTableRule.setDatabaseShardingStrategyConfig(new InlineShardingStrategyConfiguration("user_id", "ds${user_id % 2}"));
        orderTableRule.setTableShardingStrategyConfig(new InlineShardingStrategyConfiguration("order_id", "t_order${order_id % 2}"));
        shardingRuleConfig.getTableRuleConfigs().add(orderTableRule);
        
        // 配置 t_order_item 表规则
        TableRuleConfiguration orderItemTableRule = new TableRuleConfiguration("t_order_item", "ds${0..1}.t_order_item${0..1}");
        orderItemTableRule.setDatabaseShardingStrategyConfig(new InlineShardingStrategyConfiguration("user_id", "ds${user_id % 2}"));
        orderItemTableRule.setTableShardingStrategyConfig(new InlineShardingStrategyConfiguration("order_id", "t_order_item${order_id % 2}"));
        shardingRuleConfig.getTableRuleConfigs().add(orderItemTableRule);
        
        // 绑定表、广播表配置
        shardingRuleConfig.getBindingTableGroups().add("t_order,t_order_item");
        shardingRuleConfig.getBroadcastTables().add("t_config");
        
        // 4. 构建 ShardingDataSource
        Properties props = new Properties();
        props.setProperty("sql.show", "true"); // 显示 SQL
        return ShardingDataSourceFactory.createDataSource(dataSourceMap, shardingRuleConfig, props);
    }

    /**
     * 从 Nacos 拉取配置内容
     */
    private String getConfigFromNacos() throws NacosException {
        ConfigService configService = NacosFactory.createConfigService(
                "serverAddr=" + nacosServerAddr + ",namespace=" + nacosNamespace);
        // 拉取配置（超时时间 3000ms）
        return configService.getConfig(nacosDataId, nacosGroup, 3000);
    }

    /**
     * 构建数据源映射（对应你原来的 ds/ds0/ds1）
     */
    private Map<String, DataSource> createDataSourceMap() {
        Map<String, DataSource> dataSourceMap = new HashMap<>(3);
        
        // 构建 ds 数据源
        org.apache.commons.dbcp.BasicDataSource ds = new org.apache.commons.dbcp.BasicDataSource();
        ds.setDriverClassName("com.mysql.jdbc.Driver");
        ds.setUrl("jdbc:mysql://localhost:3306/ds");
        ds.setUsername("root");
        ds.setPassword("");
        dataSourceMap.put("ds", ds);
        
        // 构建 ds0 数据源
        org.apache.commons.dbcp.BasicDataSource ds0 = new org.apache.commons.dbcp.BasicDataSource();
        ds0.setDriverClassName("com.mysql.jdbc.Driver");
        ds0.setUrl("jdbc:mysql://localhost:3306/ds0");
        ds0.setUsername("root");
        ds0.setPassword("");
        dataSourceMap.put("ds0", ds0);
        
        // 构建 ds1 数据源
        org.apache.commons.dbcp.BasicDataSource ds1 = new org.apache.commons.dbcp.BasicDataSource();
        ds1.setDriverClassName("com.mysql.jdbc.Driver");
        ds1.setUrl("jdbc:mysql://localhost:3306/ds1");
        ds1.setUsername("root");
        ds1.setPassword("");
        dataSourceMap.put("ds1", ds1);
        
        return dataSourceMap;
    }
}
```

#### 3. Nacos 中配置分片规则（可选优化）
上面的示例是手动构建配置，你也可以将分片规则写成 YAML 格式存到 Nacos，然后在代码中解析：
```yaml
# Nacos 中 sharding-rule-4.1.1.yaml 内容
dataSources:
  ds:
    driverClassName: com.mysql.jdbc.Driver
    url: jdbc:mysql://localhost:3306/ds
    username: root
    password: ""
  ds0:
    driverClassName: com.mysql.jdbc.Driver
    url: jdbc:mysql://localhost:3306/ds0
    username: root
    password: ""
  ds1:
    driverClassName: com.mysql.jdbc.Driver
    url: jdbc:mysql://localhost:3306/ds1
    username: root
    password: ""

shardingRule:
  tables:
    t_order:
      actualDataNodes: ds${0..1}.t_order${0..1}
      databaseStrategy:
        inline:
          shardingColumn: user_id
          algorithmExpression: ds${user_id % 2}
      tableStrategy:
        inline:
          shardingColumn: order_id
          algorithmExpression: t_order${order_id % 2}
    t_order_item:
      actualDataNodes: ds${0..1}.t_order_item${0..1}
      databaseStrategy:
        inline:
          shardingColumn: user_id
          algorithmExpression: ds${user_id % 2}
      tableStrategy:
        inline:
          shardingColumn: order_id
          algorithmExpression: t_order_item${order_id % 2}
  bindingTables:
    - t_order,t_order_item
  broadcastTables:
    - t_config
  defaultDataSourceName: ds

props:
  sql.show: true
```
然后在 `getConfigFromNacos()` 后，用 `snakeyaml` 解析 YAML 字符串为 Map，再转换为 ShardingSphere 的配置对象。

#### 4. 关闭 ShardingSphere 自动配置（关键）
在 Spring Boot 启动类上排除默认的自动配置，避免冲突：
```java
import org.apache.shardingsphere.shardingjdbc.spring.boot.ShardingSphereAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class, // 排除默认数据源自动配置
        ShardingSphereAutoConfiguration.class // 排除 ShardingSphere 自动配置
})
public class ShardingApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShardingApplication.class, args);
    }
}
```

### 二、4.1.1 版本集成 Nacos 的注意事项
1. **无动态刷新能力**：4.1.1 版本无法像 5.x 那样自动感知 Nacos 配置变化，若要实现动态刷新，需要自己监听 Nacos 配置变更事件，然后重新构建数据源（复杂度较高）；
2. **版本兼容**：Nacos 客户端建议选择 1.x 版本（如 1.4.2），避免和 4.1.1 版本的依赖冲突；
3. **配置解析**：4.1.1 版本的 ShardingSphere 配置格式和 5.x 不同（比如 4.x 用 `shardingRule`，5.x 用 `rules`），务必按 4.x 格式编写 Nacos 配置；
4. **替代方案**：如果不需要动态配置，仅需集中管理，也可以将 `application.properties` 中的 ShardingSphere 配置全量放到 Nacos 的配置中心（Spring Cloud Nacos 方式），让 Spring Boot 优先加载 Nacos 中的配置（这种方式更简单，但无动态刷新分片规则的能力）。

### 总结
1. ShardingSphere 4.1.1 无原生 Nacos 支持，需通过**自定义加载 Nacos 配置 + 手动构建 ShardingDataSource** 实现集成；
2. 核心步骤：引入 Nacos 依赖 → 关闭 ShardingSphere 自动配置 → 从 Nacos 拉取配置 → 手动构建分片数据源；
3. 局限性：4.1.1 版本无法实现分片规则的动态刷新（需手动监听 Nacos 配置变更并重建数据源），若需动态配置建议升级到 5.x 版本。