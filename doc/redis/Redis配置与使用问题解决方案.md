# Redis配置与使用问题解决方案

本文档记录了在Spring Boot项目中配置和使用Redis集群时遇到的各种问题及解决方案。

## 目录

1. [Docker Redis集群内外网地址冲突问题](#1-docker-redis集群内外网地址冲突问题)
2. [Jackson版本冲突问题](#2-jackson版本冲突问题)
3. [对象序列化类型转换问题](#3-对象序列化类型转换问题)
4. [环境变量解析问题](#4-环境变量解析问题)

---

## 1. Docker Redis集群内外网地址冲突问题

### 问题描述

Docker的Redis集群使用内网地址（如172.18.0.x），而Lettuce客户端使用外网地址，导致客户端无法访问Redis集群。

**错误表现：**
- `connection timed out: /172.18.0.x:6379`
- `MOVED xxx 172.18.0.x:6379`（重定向到内网IP）
- `Unable to connect to Redis; nested exception is org.springframework.data.redis.connection.PoolException`

### 问题原因

- Redis集群节点间通信依赖**容器内网IP**，但会把这个IP作为「公告地址」广播给客户端
- Lettuce客户端接收到容器内网IP后，尝试直接连接，因宿主机无法访问该IP导致超时/连接失败
- 即使你在应用里配置宿主机IP，Lettuce刷新集群拓扑后，仍会使用Redis广播的内网IP，引发冲突

### 解决方案

#### 步骤1：修改docker-compose.yml，添加公告地址参数

为每个Redis节点添加 `--cluster-announce-ip`、`--cluster-announce-port` 和 `--cluster-announce-bus-port` 参数：

```yaml
services:
  redis-node1:
    image: redis:6.2-alpine
    container_name: redis-node1
    ports:
      - "6379:6379"
      - "16379:16379"
    command:
      - redis-server
      - /etc/redis/redis.conf
      # 核心：公告地址 = 宿主机IP + 宿主机映射端口
      - --cluster-announce-ip
      - ${REDIS_HOST_IP:-172.22.225.108}  # 使用环境变量或默认值
      - --cluster-announce-port
      - "6379"
      - --cluster-announce-bus-port
      - "16379"
    volumes:
      - redis-data-1:/data
      - ./redis.conf:/etc/redis/redis.conf
    privileged: true

  redis-node2:
    # ... 类似配置，端口改为6380和16380
    command:
      - redis-server
      - /etc/redis/redis.conf
      - --cluster-announce-ip
      - ${REDIS_HOST_IP:-172.22.225.108}
      - --cluster-announce-port
      - "6380"
      - --cluster-announce-bus-port
      - "16380"

  redis-node3:
    # ... 类似配置，端口改为6381和16381
    command:
      - redis-server
      - /etc/redis/redis.conf
      - --cluster-announce-ip
      - ${REDIS_HOST_IP:-172.22.225.108}
      - --cluster-announce-port
      - "6381"
      - --cluster-announce-bus-port
      - "16381"
```

**关键点：**
- 使用环境变量 `${REDIS_HOST_IP:-默认值}` 语法，注意默认值前不要有负号
- 确保集群总线端口（16379/16380/16381）已正确映射

#### 步骤2：配置application.yaml

```yaml
spring:
  redis:
    # Redis集群配置（解决Docker内外网地址冲突问题）
    # 集群节点：使用宿主机IP+映射端口（和Redis公告地址一致）
    cluster:
      nodes:
        - "${REDIS_HOST_IP:172.22.225.108}:6379"
        - "${REDIS_HOST_IP:172.22.225.108}:6380"
        - "${REDIS_HOST_IP:172.22.225.108}:6381"
      max-redirects: 3
    timeout: 15000
    # Lettuce连接池配置
    lettuce:
      pool:
        max-active: 8
        max-idle: 8
        min-idle: 0
      # 关键：关闭拓扑自动刷新（避免客户端重新获取内网IP导致连接失败）
      cluster:
        refresh:
          adaptive: false  # 禁用自适应刷新
          period: -1  # -1表示关闭自动刷新，不要设为0
```

**关键配置说明：**
- 使用引号包裹环境变量表达式：`"${REDIS_HOST_IP:172.22.225.108}:6379"`
- `adaptive: false` 和 `period: -1` 关闭拓扑自动刷新，避免重新获取内网IP

#### 步骤3：重新初始化Redis集群

修改公告地址后，必须重新初始化集群：

```bash
# 1. 停止并删除旧容器
docker-compose down redis-node1 redis-node2 redis-node3

# 2. 启动新容器
docker-compose up -d redis-node1 redis-node2 redis-node3

# 3. 等待30秒
sleep 30  # Linux/Mac
timeout /t 30  # Windows

# 4. 初始化集群（替换为你的IP）
docker exec -it redis-node1 redis-cli --cluster create \
  172.22.225.108:6379 \
  172.22.225.108:6380 \
  172.22.225.108:6381 \
  --cluster-replicas 0
```

#### 步骤4：验证集群状态

```bash
# 查看集群节点地址（应该显示宿主机IP，而不是内网IP）
docker exec -it redis-node1 redis-cli -c cluster nodes
```

### 注意事项

1. **必须重新初始化集群**：修改公告地址后，必须删除旧的`nodes.conf`并重新创建集群
2. **使用局域网IP**：不要使用127.0.0.1，必须使用局域网IP地址
3. **端口映射**：确保集群总线端口（16379/16380/16381）已正确映射
4. **关闭拓扑刷新**：Lettuce客户端必须关闭拓扑自动刷新，避免重新获取内网IP

---

## 2. Jackson版本冲突问题

### 问题描述

应用启动时出现以下错误：

```
java.lang.NoSuchMethodError: com.fasterxml.jackson.core.util.BufferRecycler.releaseToPool()V
```

**错误原因：**
- `jackson-databind` 版本是 2.17.0
- `jackson-core` 版本是 2.13.5
- 两个版本不兼容，导致方法不存在

### 解决方案

#### 步骤1：移除显式的Jackson版本依赖

在 `pom.xml` 中，移除或注释掉显式指定版本的Jackson依赖：

```xml
<!-- 移除或注释掉显式版本 -->
<!-- <dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.17.0</version>
</dependency> -->
```

#### 步骤2：在properties中定义Jackson版本

```xml
<properties>
    <java.version>8</java.version>
    <spring-cloud.version>2021.0.8</spring-cloud.version>
    <nacos.version>2021.0.5.0</nacos.version>
    <!-- 统一Jackson版本，与Spring Boot 2.7.18匹配 -->
    <jackson.version>2.13.5</jackson.version>
</properties>
```

#### 步骤3：在dependencyManagement中统一管理版本

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-dependencies</artifactId>
            <version>${spring-cloud.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <!-- 统一管理Jackson版本，解决版本冲突 -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-core</artifactId>
            <version>${jackson.version}</version>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <version>${jackson.version}</version>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-annotations</artifactId>
            <version>${jackson.version}</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### 注意事项

- Spring Boot 2.7.18 默认使用 Jackson 2.13.5，确保所有Jackson组件版本一致
- 如果必须使用特定版本，确保 `jackson-core`、`jackson-databind`、`jackson-annotations` 版本完全一致

---

## 3. 对象序列化类型转换问题

### 问题描述

使用 `RedisTemplate` 存储和读取对象时，出现类型转换错误：

```
java.lang.ClassCastException: java.util.LinkedHashMap cannot be cast to org.lix.mycatdemo.redis.RedisExample$User
```

**问题原因：**
- 使用 `Jackson2JsonRedisSerializer` 时，由于没有类型信息，反序列化会将JSON转换为 `LinkedHashMap` 而不是原始对象类型
- 移除了 `activateDefaultTyping()` 方法（该方法在新版本Jackson中已废弃）

### 解决方案

使用 `GenericJackson2JsonRedisSerializer` 替代 `Jackson2JsonRedisSerializer`：

```java
@Configuration
public class RedisConfig {
    
    @Bean
    public RedisTemplate<String, Object> redisTemplate(LettuceConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // 使用GenericJackson2JsonRedisSerializer来序列化和反序列化redis的value值
        // GenericJackson2JsonRedisSerializer会在JSON中添加类型信息，支持正确的反序列化
        GenericJackson2JsonRedisSerializer jsonRedisSerializer = new GenericJackson2JsonRedisSerializer();

        // 使用StringRedisSerializer来序列化和反序列化redis的key值
        StringRedisSerializer stringRedisSerializer = new StringRedisSerializer();

        // key采用String的序列化方式
        template.setKeySerializer(stringRedisSerializer);
        // hash的key也采用String的序列化方式
        template.setHashKeySerializer(stringRedisSerializer);
        // value序列化方式采用jackson
        template.setValueSerializer(jsonRedisSerializer);
        // hash的value序列化方式采用jackson
        template.setHashValueSerializer(jsonRedisSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
```

### 工作原理

- **序列化时**：对象会被序列化为JSON，并自动添加 `@class` 字段
  - 例如：`{"@class":"org.lix.mycatdemo.redis.RedisExample$User","name":"张三","age":25,...}`
- **反序列化时**：根据 `@class` 字段自动还原为正确的对象类型

### 优势

1. 无需手动配置 `ObjectMapper`
2. 无需使用已废弃的 `activateDefaultTyping()` 方法
3. 自动处理类型信息，支持正确的反序列化
4. 兼容新版本Jackson

---

## 4. 环境变量解析问题

### 问题描述

在 `application.yaml` 中使用环境变量时，IP地址前出现负号：

```
Unable to connect to [-172.22.225.108:6379]: -172.22.225.108
java.net.UnknownHostException: -172.22.225.108
```

**问题原因：**
- YAML中环境变量语法 `${REDIS_HOST_IP:-172.22.225.108}` 被错误解析
- 默认值前的负号被当作IP地址的一部分

### 解决方案

使用引号包裹整个环境变量表达式：

```yaml
spring:
  redis:
    cluster:
      nodes:
        # 错误写法：- ${REDIS_HOST_IP:-172.22.225.108}:6379
        # 正确写法：使用引号包裹
        - "${REDIS_HOST_IP:172.22.225.108}:6379"
        - "${REDIS_HOST_IP:172.22.225.108}:6380"
        - "${REDIS_HOST_IP:172.22.225.108}:6381"
```

**关键点：**
- 使用双引号包裹：`"${REDIS_HOST_IP:172.22.225.108}:6379"`
- 默认值前不要有负号：`172.22.225.108` 而不是 `-172.22.225.108`

### 环境变量设置方式

**Windows PowerShell:**
```powershell
$env:REDIS_HOST_IP="你的IP地址"
```

**Windows CMD:**
```cmd
set REDIS_HOST_IP=你的IP地址
```

**Linux/Mac:**
```bash
export REDIS_HOST_IP=你的IP地址
```

---

## 5. Redis配置类完整示例

### RedisConfig.java

```java
package org.lix.mycatdemo.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis配置类
 * 配置RedisTemplate和StringRedisTemplate，支持Redis集群模式
 * 
 * 注意：LettuceConnectionFactory会由Spring Boot自动配置（从application.yaml读取spring.redis.cluster配置）
 * 这里只需要配置RedisTemplate和StringRedisTemplate的序列化方式
 * 
 * @author lix
 */
@Slf4j
@Configuration
public class RedisConfig {

    /**
     * 配置RedisTemplate（支持对象序列化）
     * 使用GenericJackson2JsonRedisSerializer进行序列化
     * GenericJackson2JsonRedisSerializer会在JSON中添加类型信息（@class字段），支持正确的反序列化
     * 
     * @param connectionFactory Spring Boot自动配置的LettuceConnectionFactory
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(@Autowired LettuceConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // 使用GenericJackson2JsonRedisSerializer来序列化和反序列化redis的value值
        // GenericJackson2JsonRedisSerializer会在JSON中添加类型信息，支持正确的反序列化
        GenericJackson2JsonRedisSerializer jsonRedisSerializer = new GenericJackson2JsonRedisSerializer();

        // 使用StringRedisSerializer来序列化和反序列化redis的key值
        StringRedisSerializer stringRedisSerializer = new StringRedisSerializer();

        // key采用String的序列化方式
        template.setKeySerializer(stringRedisSerializer);
        // hash的key也采用String的序列化方式
        template.setHashKeySerializer(stringRedisSerializer);
        // value序列化方式采用jackson
        template.setValueSerializer(jsonRedisSerializer);
        // hash的value序列化方式采用jackson
        template.setHashValueSerializer(jsonRedisSerializer);

        template.afterPropertiesSet();
        log.info("RedisTemplate初始化完成（支持对象序列化）");
        return template;
    }

    /**
     * 配置StringRedisTemplate（只支持字符串操作）
     * 性能更好，适合简单的字符串操作
     * 
     * @param connectionFactory Spring Boot自动配置的LettuceConnectionFactory
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(@Autowired LettuceConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        log.info("StringRedisTemplate初始化完成（字符串操作）");
        return template;
    }
}
```

---

## 6. 完整配置检查清单

### application.yaml配置检查

- [ ] Redis集群节点地址使用宿主机IP（不是内网IP）
- [ ] 环境变量表达式使用引号包裹：`"${REDIS_HOST_IP:默认值}:端口"`
- [ ] 关闭Lettuce拓扑自动刷新：`adaptive: false` 和 `period: -1`
- [ ] 超时时间配置合理：`timeout: 15000`

### docker-compose.yml配置检查

- [ ] 每个Redis节点都配置了 `--cluster-announce-ip`
- [ ] 每个Redis节点都配置了 `--cluster-announce-port`（宿主机映射端口）
- [ ] 每个Redis节点都配置了 `--cluster-announce-bus-port`（集群总线端口）
- [ ] 集群总线端口已正确映射（16379/16380/16381）

### pom.xml配置检查

- [ ] 移除了显式的Jackson版本依赖（让Spring Boot管理）
- [ ] 在 `dependencyManagement` 中统一管理Jackson版本
- [ ] 所有Jackson组件版本一致（jackson-core、jackson-databind、jackson-annotations）

### RedisConfig配置检查

- [ ] 使用 `GenericJackson2JsonRedisSerializer` 而不是 `Jackson2JsonRedisSerializer`
- [ ] Key使用 `StringRedisSerializer`
- [ ] Value使用 `GenericJackson2JsonRedisSerializer`

---

## 7. 常见问题排查

### 问题1：连接超时

**可能原因：**
- Redis容器未启动
- 端口映射不正确
- 防火墙阻止连接
- IP地址配置错误

**排查步骤：**
1. 检查容器状态：`docker ps | grep redis`
2. 检查端口映射：`docker port redis-node1`
3. 测试连接：`redis-cli -h 172.22.225.108 -p 6379 ping`
4. 检查防火墙规则

### 问题2：MOVED重定向失败

**可能原因：**
- Redis集群公告地址配置错误
- Lettuce拓扑刷新获取到内网IP

**排查步骤：**
1. 检查集群节点地址：`docker exec redis-node1 redis-cli -c cluster nodes`
2. 确认所有节点都使用宿主机IP
3. 检查 `application.yaml` 中的拓扑刷新配置

### 问题3：对象反序列化失败

**可能原因：**
- 使用了 `Jackson2JsonRedisSerializer` 而不是 `GenericJackson2JsonRedisSerializer`
- Jackson版本冲突

**排查步骤：**
1. 检查 `RedisConfig` 中使用的序列化器
2. 检查Jackson版本是否一致
3. 查看Redis中存储的数据格式（应该包含 `@class` 字段）

---

## 8. 总结

### 核心要点

1. **Docker Redis集群**：必须配置公告地址为宿主机IP，避免内外网地址冲突
2. **Lettuce客户端**：关闭拓扑自动刷新，避免重新获取内网IP
3. **Jackson版本**：统一所有Jackson组件版本，避免版本冲突
4. **对象序列化**：使用 `GenericJackson2JsonRedisSerializer` 支持正确的类型转换
5. **环境变量**：使用引号包裹表达式，避免解析错误

### 推荐配置

- **序列化器**：`GenericJackson2JsonRedisSerializer`
- **拓扑刷新**：`adaptive: false`, `period: -1`
- **Jackson版本**：与Spring Boot版本匹配（2.7.18使用2.13.5）
- **集群配置**：使用宿主机IP作为公告地址

按照以上配置，可以确保Redis集群在Docker环境下的正常使用。

