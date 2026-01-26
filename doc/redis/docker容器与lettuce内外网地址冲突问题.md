你现在遇到的是 Docker 部署 Redis 分片（集群）时，Lettuce 客户端因「内外网地址冲突」导致无法正常连接的典型问题，这也是 Docker 环境下 Redis 集群最常见的坑。我会从**问题本质、核心解决方案、实操步骤、避坑要点**四个维度，给你一套完整且可落地的解决方案。

### 一、问题本质：内外网地址不一致
#### 1. 核心矛盾
| 网络层面       | 地址类型                | 可访问性                     |
|----------------|-------------------------|------------------------------|
| Docker 容器内网 | 172.18.0.x:6379（示例） | 仅容器间可访问，宿主机不可达 |
| 宿主机外网     | 192.168.1.100:6379（示例） | 宿主机/局域网可访问          |

- Redis 集群节点间通信依赖**容器内网 IP**，但会把这个 IP 作为「公告地址」广播给客户端；
- Lettuce 客户端接收到容器内网 IP 后，尝试直接连接，因宿主机无法访问该 IP 导致超时/连接失败；
- 即使你在应用里配置宿主机 IP，Lettuce 刷新集群拓扑后，仍会使用 Redis 广播的内网 IP，引发冲突。

#### 2. 典型错误表现
- `connection timed out: /172.18.0.x:6379`
- `MOVED xxx 172.18.0.x:6379`（重定向到内网 IP）
- `Unable to connect to [redis://127.0.0.1:6379, ...]`

### 二、核心解决方案：统一公告地址为宿主机IP
核心思路是让 Redis 集群对外广播**宿主机可访问的 IP+端口**，而非容器内网 IP，确保 Lettuce 客户端拿到的地址是「能访问的」。

#### 步骤1：准备工作（查宿主机IP）
```bash
# Windows 查宿主机局域网 IP
ipconfig 
# 找到「WLAN/以太网」的 IPv4 地址，比如 192.168.1.100

# Linux/Mac 查宿主机 IP
ifconfig
```

#### 步骤2：修改 Redis 集群配置（关键）
##### 1. 调整 docker-compose.yml（核心：指定公告地址）
```yaml
version: '3.8'
networks:
  docker-cluster-network:
    driver: bridge

volumes:
  redis-data-1:
  redis-data-2:
  redis-data-3:

services:
  # Redis 节点1
  redis-node1:
    image: redis:6.2-alpine
    container_name: redis-node1
    restart: always
    networks:
      - docker-cluster-network
    ports:
      - "6379:6379"       # 业务端口映射
      - "16379:16379"     # 集群总线端口（必须映射，默认+10000）
    command: 
      - redis-server 
      - /etc/redis/redis.conf
      # 核心：公告地址 = 宿主机IP + 宿主机映射端口
      - --cluster-announce-ip 192.168.1.100  # 替换为你的宿主机IP
      - --cluster-announce-port 6379
      - --cluster-announce-bus-port 16379
    volumes:
      - redis-data-1:/data
      - ./redis.conf:/etc/redis/redis.conf
    privileged: true

  # Redis 节点2
  redis-node2:
    image: redis:6.2-alpine
    container_name: redis-node2
    restart: always
    networks:
      - docker-cluster-network
    ports:
      - "6380:6379"
      - "16380:16379"
    command: 
      - redis-server 
      - /etc/redis/redis.conf
      - --cluster-announce-ip 192.168.1.100
      - --cluster-announce-port 6380
      - --cluster-announce-bus-port 16380
    volumes:
      - redis-data-2:/data
      - ./redis.conf:/etc/redis/redis.conf
    privileged: true

  # Redis 节点3
  redis-node3:
    image: redis:6.2-alpine
    container_name: redis-node3
    restart: always
    networks:
      - docker-cluster-network
    ports:
      - "6381:6379"
      - "16381:16379"
    command: 
      - redis-server 
      - /etc/redis/redis.conf
      - --cluster-announce-ip 192.168.1.100
      - --cluster-announce-port 6381
      - --cluster-announce-bus-port 16381
    volumes:
      - redis-data-3:/data
      - ./redis.conf:/etc/redis/redis.conf
    privileged: true
```

##### 2. 简化版 redis.conf（仅保留核心）
```conf
# 基础配置
port 6379
bind 0.0.0.0
protected-mode no
appendonly yes

# 集群配置
cluster-enabled yes
cluster-config-file nodes.conf
cluster-node-timeout 15000  # 15秒，避免网络波动误判
```

#### 步骤3：重新初始化 Redis 集群
```bash
# 1. 停止并删除旧容器（清空残留）
docker-compose down redis-node1 redis-node2 redis-node3

# 2. 启动新容器
docker-compose up -d redis-node1 redis-node2 redis-node3

# 3. 等待30秒，确保节点完全启动
sleep 30

# 4. 进入节点1，用宿主机IP初始化集群
docker exec -it redis-node1 sh

# 执行集群创建命令（替换为你的宿主机IP）
redis-cli --cluster create \
  192.168.1.100:6379 \
  192.168.1.100:6380 \
  192.168.1.100:6381 \
  --cluster-replicas 0

# 输入 yes 确认槽位分配
```

#### 步骤4：配置 Lettuce 客户端（Spring Boot）
修改 `application.yml`，确保配置和 Redis 公告地址一致：
```yaml
spring:
  redis:
    # 集群节点：宿主机IP+映射端口（和Redis公告地址一致）
    cluster:
      nodes:
        - 192.168.1.100:6379
        - 192.168.1.100:6380
        - 192.168.1.100:6381
      max-redirects: 3  # 允许最大重定向次数
    timeout: 15000      # 超时时间和Redis配置匹配
    lettuce:
      pool:
        max-active: 8
        max-idle: 8
        min-idle: 0
      # 关键：关闭拓扑自动刷新（避免客户端重新获取内网IP）
      cluster:
        refresh:
          adaptive: false
          period: -1  # -1表示关闭，不要设0
```

### 三、验证是否修复成功
#### 1. 验证 Redis 集群公告地址
```bash
docker exec -it redis-node1 redis-cli -c
127.0.0.1:6379> cluster nodes
# 正常输出示例（地址应为宿主机IP）：
# a1b2c3d4... 192.168.1.100:6379@16379 myself,master - 0 1737840000000 1 connected 0-5460
# b2c3d4e5... 192.168.1.100:6380@16380 master - 0 1737840000100 2 connected 5461-10922
# c3d4e5f6... 192.168.1.100:6381@16381 master - 0 1737840000200 3 connected 10923-16383
```

#### 2. 验证 Spring Boot 连接
```java
// 新增测试代码
@Component
public class RedisTest {
    @Autowired
    private StringRedisTemplate redisTemplate;

    @PostConstruct
    public void testRedis() {
        // 写入数据
        redisTemplate.opsForValue().set("test-key", "hello-redis-cluster");
        // 读取数据
        String value = redisTemplate.opsForValue().get("test-key");
        System.out.println("Redis 读取结果：" + value);
        // 输出应为：Redis 读取结果：hello-redis-cluster
    }
}
```

### 四、避坑要点（关键）
1. **集群总线端口必须映射**：Redis 集群节点间通信依赖 `6379+10000=16379` 端口，必须在 docker-compose 中映射（16379/16380/16381），否则节点间无法通信；
2. **禁用 Lettuce 拓扑刷新**：开发环境关闭 `adaptive refresh`，避免客户端自动刷新后获取到内网 IP；
3. **不要用 127.0.0.1 作为公告地址**：容器内的 Redis 会把 127.0.0.1 解析为容器自身，而非宿主机，必须用局域网 IP；
4. **重新初始化集群**：修改公告地址后，必须删除旧的 `nodes.conf` 并重新创建集群，否则配置不生效。

### 总结
1. **核心矛盾**：Redis 集群广播容器内网 IP，Lettuce 客户端无法访问；
2. **核心方案**：通过 `--cluster-announce-ip/port` 让 Redis 广播宿主机 IP+端口，统一内外网地址；
3. **关键配置**：
    - Redis 侧：公告地址=宿主机IP+映射端口，映射集群总线端口；
    - 客户端侧：配置宿主机IP，关闭 Lettuce 拓扑自动刷新。

按这套方案配置后，Lettuce 客户端就能正确识别 Redis 集群节点地址，彻底解决内外网地址冲突导致的连接超时/重定向失败问题。