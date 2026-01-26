你提供的这段`RedisConfig`配置类是基于Spring Boot的Redis集群配置，核心作用是**整合Redisson客户端实现Redis集群连接，并定制化生成适用于集群环境的`RedisTemplate`和`StringRedisTemplate`模板**，替代Spring默认的Redis连接方式。下面我会逐模块、逐方法拆解代码的功能和设计思路。

### 一、整体功能总结
这段代码的核心目标是：
1. 配置Redisson客户端连接Redis集群（而非单机），指定集群节点、扫描间隔、传输模式等核心参数；
2. 基于Redisson客户端构建`RedissonConnectionFactory`（Spring Data Redis的连接工厂适配类）；
3. 定制化创建两个Redis操作模板：
    - `redisClusterTemplate`：支持JDK序列化的通用Redis模板；
    - `stringRedisClusterTemplate`：仅支持字符串序列化的轻量模板（适配你验签场景的`setIfAbsent`操作）；
4. 所有配置适配Redis集群环境，而非单机Redis。

### 二、逐行/逐方法详细解释

#### 1. 配置项注入（@Value注解）
```java
@Value("${redis.cluster.config}")
private String redisConfigs;

@Value("${redis.cluster.scanInterval:5000}")
private Integer scanInterval;
```
- `redisConfigs`：从配置文件（如application.yml）读取Redis集群节点配置，格式通常是`ip1:port1,ip2:port2,ip3:port3`（比如`192.168.1.10:6379,192.168.1.11:6379`）；
- `scanInterval`：Redis集群节点的扫描间隔（默认5000ms），Redisson会定期扫描集群节点状态，确保连接的是可用节点，`:5000`是默认值（配置文件未配置时使用）。

#### 2. 核心Bean：RedissonClient（Redisson集群客户端）
```java
@Bean
public RedissonClient getRedissonClient() {
    Config config = new Config();
    // 设置传输模式为NIO（非阻塞IO，高性能，Redisson默认）
    config.setTransportMode(TransportMode.NIO);
    // 设置序列化器为StringCodec（默认是Jackson，这里指定字符串序列化）
    config.setCodec(new StringCodec());
    // 启用Redis集群模式（核心：区别于单机模式）
    ClusterServersConfig clusterServersConfig = config.useClusterServers();
    // 设置集群节点扫描间隔（检测节点是否可用）
    clusterServersConfig.setScanInterval(scanInterval);
    // 遍历集群节点配置，添加所有节点地址（拼接redis://前缀，符合Redis协议）
    for (String redisCfg : redisConfigs.split(",")) {
        clusterServersConfig.addNodeAddress("redis://" + redisCfg);
    }
    // 创建Redisson客户端实例（集群模式）
    return Redisson.create(config);
}
```
**关键作用**：
- `Config`：Redisson的核心配置类，用于定义Redis连接方式（单机/集群/哨兵）；
- `TransportMode.NIO`：使用非阻塞IO模型，适配高并发场景，比BIO性能提升显著；
- `StringCodec`：指定全局序列化器为字符串序列化（避免默认Jackson序列化带来的额外开销，适合纯字符串操作的场景，如你的nonce防重放）；
- `useClusterServers()`：明确使用Redis集群模式（如果是单机，会用`useSingleServer()`）；
- `addNodeAddress()`：添加所有集群节点，Redisson会自动发现集群的主从节点、槽位分布。

#### 3. 适配Bean：RedissonConnectionFactory
```java
@Bean
public RedissonConnectionFactory getRedissonConnectionFactory(RedissonClient redissonClient) {
    return new RedissonConnectionFactory(redissonClient);
}
```
**关键作用**：
- `RedissonConnectionFactory`是Redisson提供的、适配Spring Data Redis的连接工厂类；
- Spring Data Redis的`RedisTemplate`需要依赖`ConnectionFactory`来获取Redis连接，这里用Redisson的连接工厂替代Spring默认的Lettuce/Jedis连接工厂，实现`RedisTemplate`复用Redisson的集群连接；
- 入参`redissonClient`是上面创建的集群客户端，通过Spring自动注入。

#### 4. 定制化RedisTemplate：redisClusterTemplate
```java
@Bean("redisClusterTemplate")
public RedisTemplate getRedisTemplate(RedissonConnectionFactory redissonConnectionFactory) {
    RedisTemplate redisTemplate = new RedisTemplate<>();
    // 绑定Redisson连接工厂（核心：让模板使用集群连接）
    redisTemplate.setConnectionFactory(redissonConnectionFactory);
    // 设置Key的序列化器为字符串（避免Key出现乱码，如\xAC\xED\x00\x05t\x00\x03abc）
    redisTemplate.setKeySerializer(new StringRedisSerializer());
    // 设置HashKey的序列化器为字符串（Hash类型的Key同理）
    redisTemplate.setHashKeySerializer(new StringRedisSerializer());
    // 设置Value的序列化器为JDK序列化（支持任意Java对象序列化）
    redisTemplate.setValueSerializer(new JdkSerializationRedisSerializer());
    return redisTemplate;
}
```
**关键作用**：
- 命名为`redisClusterTemplate`（通过`@Bean("redisClusterTemplate")`），方便在业务代码中通过`@Resource(name = "redisClusterTemplate")`精准注入；
- 序列化器设计：
    - Key/HashKey用`StringRedisSerializer`：保证Redis中的Key是可读的字符串（如`sign:nonce:abc123`），而非序列化后的乱码；
    - Value用`JdkSerializationRedisSerializer`：支持将任意Java对象（如自定义实体类）序列化存储到Redis（但序列化后的值是二进制，不可读）；
- 适配集群环境：所有操作都会通过Redisson的集群连接执行，自动处理集群的槽位路由、主从切换。

#### 5. 定制化StringRedisTemplate：stringRedisClusterTemplate
```java
@Bean("stringRedisClusterTemplate")
public StringRedisTemplate getStringRedisTemplate(RedissonConnectionFactory redissonConnectionFactory) {
    StringRedisTemplate stringRedisTemplate = new StringRedisTemplate();
    // 绑定Redisson集群连接工厂
    stringRedisTemplate.setConnectionFactory(redissonConnectionFactory);
    // 关闭事务支持（Redis集群通常不建议开启事务，性能低且兼容性差）
    stringRedisTemplate.setEnableTransactionSupport(false);
    // 暴露底层连接（便于调试，生产环境可关闭）
    stringRedisTemplate.setExposeConnection(true);
    // Key/Value都用字符串序列化（核心：纯字符串操作，无乱码）
    stringRedisTemplate.setKeySerializer(new StringRedisSerializer());
    stringRedisTemplate.setValueSerializer(new StringRedisSerializer());
    return stringRedisTemplate;
}
```
**关键作用**（适配你的验签场景）：
- `StringRedisTemplate`是`RedisTemplate`的子类，专门用于字符串的KV操作，比通用`RedisTemplate`更轻量；
- `setEnableTransactionSupport(false)`：Redis集群的事务支持有限（仅支持同一槽位的命令），关闭后性能更高，符合集群最佳实践；
- Key/Value都用`StringRedisSerializer`：你的验签场景中，nonce的Key和Value都是字符串，这种配置无额外序列化开销，且Redis中的值可读（如`sign:nonce:abc123`对应值`abc123`）；
- 命名为`stringRedisClusterTemplate`，业务代码中可通过`@Resource(name = "stringRedisClusterTemplate")`注入，用于nonce防重放、时间戳缓存等纯字符串操作。

### 三、核心设计思路&适用场景
1. **为什么用Redisson而非原生Lettuce/Jedis？**
    - Redisson对Redis集群的支持更完善：自动节点发现、槽位重分片、主从切换感知；
    - 除了基础KV操作，Redisson还提供分布式锁、分布式集合（如RMap、RList）等高级功能，便于后续扩展；
    - `RedissonConnectionFactory`适配Spring Data Redis，可复用`RedisTemplate`的API，学习成本低。

2. **两个模板的分工**：
    - `redisClusterTemplate`：用于存储Java对象（如用户信息、业务数据），支持JDK序列化；
    - `stringRedisClusterTemplate`：用于纯字符串操作（如你的验签nonce、计数器、缓存token），性能更高、可读性更好。

### 四、关键点回顾
1. 该配置类是**Redis集群环境**的专属配置，核心是通过Redisson构建集群连接；
2. `RedissonClient`是底层核心客户端，`RedissonConnectionFactory`是Spring适配层，`RedisTemplate/StringRedisTemplate`是业务操作层；
3. 序列化器设计：Key统一用字符串序列化（避免乱码），Value根据场景分JDK序列化（对象）和字符串序列化（纯文本）；
4. 你的验签场景中，应注入`stringRedisClusterTemplate`来执行`setIfAbsent`操作，适配集群环境且性能最优。