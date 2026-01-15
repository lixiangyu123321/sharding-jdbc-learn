你关注的 Sharding-JDBC 分布式事务问题，核心是**分库分表后跨库/跨表操作无法依赖单机事务（ACID）保障**，导致数据一致性风险。下面我会从「核心问题表现」「Sharding-JDBC 解决方案」「避坑要点」三个维度，结合实操场景讲清楚分布式事务的处理逻辑。

### 一、先明确：Sharding-JDBC 为什么会引入分布式事务问题？
单机数据库中，`BEGIN/COMMIT/ROLLBACK` 能保证事务 ACID，但分库分表后：
- 一个业务操作（如“创建订单+扣减库存”）可能涉及**多个物理库/表**；
- 各库的事务是独立的（单机事务），无法保证“要么全部成功，要么全部回滚”；
- 典型问题：订单表插入成功，但库存表扣减失败，导致数据不一致。

### 二、Sharding-JDBC 分布式事务的核心问题表现
| 问题类型                | 场景示例                                                                 | 风险后果                     |
|-------------------------|--------------------------------------------------------------------------|------------------------------|
| 跨库事务不一致          | 订单库（ds0）插入订单，库存库（ds1）扣减库存，ds1 失败但 ds0 已提交      | 订单生成但库存未扣减，数据错乱 |
| 事务超时/回滚失败       | 多分片事务执行超时，部分分片已提交，部分未提交                           | 数据部分更新，无法回滚        |
| 幂等性问题              | 事务重试导致重复插入/更新（如订单重复创建）| 数据重复，业务逻辑异常        |
| 锁粒度失效              | 单机行锁无法跨库生效，并发操作导致脏写/脏读（如多线程扣减同一商品库存）| 数据超卖、库存负数           |

### 三、Sharding-JDBC 提供的分布式事务解决方案
Sharding-JDBC 内置了 3 种分布式事务方案，适配不同一致性要求的场景，核心是“弱化强一致性，优先保证最终一致性”：

#### 1. 柔性事务：Seata AT 模式（推荐，适配 90% 业务）
**核心逻辑**：基于 Seata（阿里开源分布式事务框架）的「自动补偿」机制，无需修改业务代码，适合大多数业务场景。
- 执行流程：
  ```mermaid
  graph TD
  A[业务发起事务] --> B[Seata TC 记录全局事务ID]
  B --> C[Sharding-JDBC 分库执行 SQL（预提交）]
  C --> D{所有分片执行成功？}
  D -- 是 --> E[TC 通知所有分片提交]
  D -- 否 --> F[TC 通知所有分片回滚（补偿）]
  ```
- 配置示例（Spring Boot + Sharding-JDBC + Seata）：
  ```yaml
  # 1. Sharding-JDBC 开启分布式事务
  spring:
    shardingsphere:
      rules:
        transaction:
          type: BASE  # 柔性事务（适配 Seata）
          props:
            use-seata: true  # 集成 Seata
      props:
        seata:
          tx-service-group: my_tx_group  # Seata 事务组
  
  # 2. Seata 配置（application.yml）
  seata:
    enabled: true
    tx-service-group: my_tx_group
    registry:
      type: nacos  # 注册中心（也可⽤ eureka/zookeeper）
      nacos:
        server-addr: 127.0.0.1:8848
        namespace: seata-namespace
    config:
      type: nacos
      nacos:
        server-addr: 127.0.0.1:8848
  ```
- 业务代码（仅需加 `@GlobalTransactional` 注解）：
  ```java
  @Service
  public class OrderService {
      @Autowired
      private OrderMapper orderMapper;
      @Autowired
      private StockMapper stockMapper;

      // 标记为全局事务，Seata 自动处理跨库一致性
      @GlobalTransactional(rollbackFor = Exception.class)
      public void createOrder(OrderDTO orderDTO) {
          // 跨库操作1：订单库（ds0）插入订单
          orderMapper.insert(orderDTO);
          // 跨库操作2：库存库（ds1）扣减库存
          stockMapper.deduct(orderDTO.getProductId(), orderDTO.getCount());
      }
  }
  ```

#### 2. 刚性事务：XA 模式（强一致性，性能差）
**核心逻辑**：基于数据库 XA 协议（两阶段提交 2PC），保证强一致性，但性能损耗大（约 30%+），仅适用于金融级场景。
- 配置示例：
  ```yaml
  spring:
    shardingsphere:
      rules:
        transaction:
          type: XA  # 刚性事务（XA 2PC）
          props:
            xa-data-source-class-name: com.mysql.cj.jdbc.MysqlXADataSource  # 数据库XA实现类
  ```
- 缺点：
    - 性能低：两阶段提交需要协调所有分片，耗时久；
    - 阻塞风险：若某分片卡住，整个事务会阻塞，占用连接资源；
    - 数据库依赖：需数据库支持 XA（如 MySQL InnoDB、Oracle），部分轻量数据库（SQLite）不支持。

#### 3. 本地事务表：SAGA 模式（最终一致性，适配长事务）
**核心逻辑**：将分布式事务拆分为多个本地事务，通过“正向操作+补偿操作”保证最终一致，适合跨服务/长耗时场景（如订单超时取消）。
- 执行流程：
    1. 执行「创建订单」本地事务 → 2. 执行「扣减库存」本地事务 → 3. 若步骤2失败，执行「订单回滚」补偿事务；
- 缺点：需手动编写补偿逻辑（如库存扣减失败，要恢复订单状态），开发成本高。

### 四、Sharding-JDBC 分布式事务的核心坑点（避坑要点）
#### 1. 事务传播行为失效
Spring 事务的 `Propagation`（如 `REQUIRED`/`REQUIRES_NEW`）仅对单机事务生效，跨库时：
- ❌ 错误：嵌套事务无法保证跨库一致性；
- ✅ 解决：所有跨库操作必须放在同一个 `@GlobalTransactional` 注解的方法中，避免嵌套事务。

#### 2. 锁机制失效导致并发问题
单机的行锁/表锁无法跨库生效，比如：
- 场景：多线程同时扣减同一商品库存（库存表分布在不同库）；
- 风险：脏写导致库存负数；
- 解决：
    1. 引入分布式锁（Redis/Zookeeper），在跨库操作前加锁；
    2. 库存表按商品ID分片（避免同一商品跨库），减少分布式锁范围。

#### 3. 幂等性缺失导致重复操作
分布式事务重试（如 Seata 补偿）可能导致重复插入/更新：
- 解决：
    1. 为业务表增加「唯一幂等键」（如订单号），插入时加唯一索引；
    2. 补偿操作先查后更，避免重复执行。

#### 4. 事务超时配置不合理
- 问题：跨库事务耗时久，默认超时时间（如 5s）导致部分分片提交、部分回滚；
- 解决：
  ```yaml
  # Seata 全局事务超时配置
  seata:
    client:
      tm:
        default-global-transaction-timeout: 30000  # 超时时间改为30s
  ```

#### 5. 不支持跨服务事务
Sharding-JDBC 仅解决「同一应用内的跨库事务」，若涉及微服务调用（如订单服务→库存服务），需结合 Seata 跨服务事务，而非仅依赖 Sharding-JDBC。

### 五、分布式事务方案选型建议（按优先级）
| 方案         | 一致性等级 | 性能 | 开发成本 | 适用场景                     |
|--------------|------------|------|----------|------------------------------|
| Seata AT     | 最终一致   | 高   | 低       | 电商订单、支付等大部分业务   |
| XA           | 强一致     | 低   | 低       | 金融转账、对账等核心场景     |
| SAGA         | 最终一致   | 中   | 高       | 长事务（如订单超时取消）|
| 避免分布式事务 | -          | 最高 | 中       | 能通过业务设计规避跨库操作   |

**最优实践**：优先通过业务设计规避分布式事务，比如：
1. 按业务维度分片（如订单和库存按用户ID分片，落到同一库）；
2. 异步化处理（如库存扣减失败，通过MQ重试补偿）；
3. 仅对核心操作（如支付）使用分布式事务，非核心操作靠最终一致性兜底。

### 总结
1. **核心问题**：Sharding-JDBC 分库分表打破单机事务，跨库操作无法保证 ACID，导致数据一致性风险；
2. **解决方案**：优先用 Seata AT 模式（最终一致、低开发成本），金融场景用 XA 模式（强一致、性能差），长事务用 SAGA 模式；
3. **避坑关键**：避免事务嵌套、增加分布式锁/幂等键、合理配置超时时间、优先通过业务设计减少跨库操作；
4. **核心原则**：分布式事务“能不用就不用”，优先靠业务设计规避，必须用则选择最弱的一致性满足业务需求（最终一致＞强一致）。

如果你的业务场景是“电商订单+库存的跨库操作”，我可以帮你设计完整的分布式事务配置（含 Sharding-JDBC + Seata 配置、幂等性实现、分布式锁代码），避免数据不一致问题。