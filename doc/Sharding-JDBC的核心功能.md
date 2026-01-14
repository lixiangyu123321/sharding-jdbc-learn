你想明确 ShardingSphere-JDBC（简称 Sharding-JDBC）的核心功能，它作为轻量级客户端分布式数据库中间件，所有能力都围绕“在 Java 应用层透明增强数据库分布式能力”展开，核心功能可归纳为**数据分片、读写分离、分布式事务、数据安全**四大核心模块，且所有功能对业务代码无侵入。

## 用户无感知

### 一、核心功能详解
#### 1. 分布式数据分片（最核心能力）
这是 Sharding-JDBC 解决“单库单表性能瓶颈”的核心，支持垂直/水平拆分，且对业务完全透明：
- **分片类型全覆盖**：
    - 垂直分片：支持垂直分库（按业务拆分库）、垂直分表（按字段拆分表）；
    - 水平分片：支持水平分库（跨实例拆分行）、水平分表（同实例拆分行）；
- **丰富的分片策略**：
- 
  | 策略类型 | 典型场景 | 示例 |
  |----------|----------|------|
  | 行内表达式（INLINE） | 哈希取模分片 | `t_order_${user_id % 8}` |
  | 范围分片（RANGE） | 时间维度分片 | 按月份拆分订单表 `t_order_202601` |
  | 一致性哈希（CONSISTENT_HASH） | 扩容少迁移数据 | 缓存/数据库扩容场景 |
  | 自定义分片（CUSTOM） | 特殊业务规则 | 按地区/用户等级分片 |
- **智能分片优化**：
    - 绑定表：关联表（如 `t_order` 和 `t_order_item`）按相同分片键拆分，避免跨分片 JOIN；
    - 广播表：字典表等全量数据复制到所有分片，本地完成 JOIN；
    - 分片键自动补全：优化 SQL，减少全分片扫描；
    - 结果合并：自动合并多分片的查询结果（支持排序、分组、分页）。

#### 2. 读写分离
解决“主库读压力过大”问题，自动路由读写请求，适配主流数据库主从架构：
- **核心能力**：
    - 读写请求自动路由：写操作（INSERT/UPDATE/DELETE）走主库，读操作（SELECT）走从库；
    - 负载均衡策略：支持轮询（ROUND_ROBIN）、随机（RANDOM）、权重（WEIGHT）等；
    - 故障自动切换：从库不可用时自动剔除，恢复后自动加入；
    - 主从延迟感知：可配置延迟阈值，超过阈值的从库暂时不接收读请求；
- **灵活适配**：可与分库分表结合，实现“分片 + 读写分离”一体化（如每个分片都有主从架构）。

#### 3. 分布式事务
解决“跨库操作原子性”问题，支持不同一致性级别，适配不同业务场景：
- **事务模式（按需选择）**：
- 
  | 事务模式 | 一致性 | 性能 | 适用场景 |
  |----------|--------|------|----------|
  | XA 事务 | 强一致性 | 低 | 金融、支付等核心场景 |
  | Seata AT 事务 | 最终一致性 | 高 | 电商、订单等大部分场景 |
  | BASE 柔性事务 | 最终一致性 | 极高 | 异步通知、补偿型业务 |
- **无侵入实现**：通过注解（如 `@Transactional`）或 API 即可使用，无需大幅修改业务代码。

#### 4. 数据安全（敏感数据保护）
解决“数据泄露风险”，对敏感字段透明处理，无需修改业务逻辑：
- **透明加密/解密**：
    - 配置敏感字段（如手机号、身份证、银行卡）后，写入自动加密，查询自动解密；
    - 支持对称加密（AES）、非对称加密（RSA）等算法；
- **数据脱敏**：
    - 查询结果自动脱敏，如手机号显示 `138****1234`、身份证显示 `110***********1234`；
    - 支持自定义脱敏规则；
- **权限控制**：细粒度控制数据库表/字段的访问权限，防止越权操作。

#### 5. 其他核心增强能力
- **影子库**：SQL 灰度验证，新 SQL 先在影子库执行，避免影响生产数据；
- **高可用**：支持数据库故障自动切换、连接池管理、超时重试；
- **SQL 解析与改写**：自动解析 SQL，按分片规则改写为目标库/表的 SQL，对业务透明；
- **生态兼容**：
    - 支持 MySQL、Oracle、PostgreSQL 等主流数据库；
    - 适配 MyBatis、JPA、Hibernate 等 ORM 框架；
    - 兼容 Druid、HikariCP 等主流连接池。

### 二、核心功能使用示例（分片 + 读写分离）
以下是 Spring Boot 中配置核心功能的极简示例，体现“无侵入”特性：
```yaml
spring:
  shardingsphere:
    # 1. 数据源配置（主从）
    datasource:
      names: master, slave
      master: # 主库（写）
        type: com.zaxxer.hikari.HikariDataSource
        jdbc-url: jdbc:mysql://127.0.0.1:3306/order_db_master
        username: root
        password: 123456
      slave: # 从库（读）
        type: com.zaxxer.hikari.HikariDataSource
        jdbc-url: jdbc:mysql://127.0.0.1:3306/order_db_slave
        username: root
        password: 123456
    # 2. 核心规则
    rules:
      # 读写分离规则
      readwrite-splitting:
        data-sources:
          order-db-group:
            type: STATIC
            props:
              write-data-source-name: master
              read-data-source-names: slave
      # 分表规则
      sharding:
        tables:
          t_order:
            actual-data-nodes: order-db-group.t_order_${0..1}
            table-strategy:
              inline:
                sharding-column: user_id
                algorithm-expression: t_order_${user_id % 2}
    # 3. 全局配置
    props:
      sql-show: true # 打印改写后的SQL，便于调试
```
业务代码无需任何修改，直接操作逻辑表 `t_order` 即可：
```java
@Service
public class OrderService {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 写操作：自动路由到主库，按user_id分表
    public void addOrder(Long userId, String orderNo) {
        jdbcTemplate.update("INSERT INTO t_order (user_id, order_no) VALUES (?, ?)", userId, orderNo);
    }

    // 读操作：自动路由到从库，按user_id查询对应分表
    public List<Map<String, Object>> getOrders(Long userId) {
        return jdbcTemplate.queryForList("SELECT * FROM t_order WHERE user_id = ?", userId);
    }
}
```

### 总结
1. Sharding-JDBC 核心功能围绕**数据分片、读写分离、分布式事务、数据安全**四大模块展开，核心是“透明增强”；
2. 所有功能对业务代码无侵入，仅需配置即可启用，适配 Java 生态所有主流框架；
3. 核心价值是在不修改业务逻辑的前提下，解决分布式场景下数据库的性能、一致性、安全问题。

简单来说，Sharding-JDBC 就是 Java 应用的“分布式数据库增强插件”，让单库应用低成本升级为分布式应用。