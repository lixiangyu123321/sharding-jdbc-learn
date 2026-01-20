# ELK 日志中心搭建与使用指南

本文档详细介绍基于 Elasticsearch、Logstash、Filebeat 和 Kibana 的日志中心搭建、配置和使用方法。

## 目录

1. [架构概述](#一架构概述)
2. [环境准备](#二环境准备)
3. [Elasticsearch 配置](#三elasticsearch-配置)
4. [Logstash 配置](#四logstash-配置)
5. [Filebeat 配置](#五filebeat-配置)
6. [Kibana 配置](#六kibana-配置)
7. [应用集成](#七应用集成)
8. [使用指南](#八使用指南)
9. [常见问题](#九常见问题)

---

## 一、架构概述

### 1.1 组件说明

**ELK Stack** 是一个完整的日志管理解决方案，由以下组件组成：

- **Elasticsearch**：分布式搜索和分析引擎，用于存储和检索日志数据
- **Logstash**：日志收集、处理和转发工具，负责数据清洗和转换
- **Filebeat**：轻量级日志采集器，从文件系统收集日志并发送到 Logstash
- **Kibana**：数据可视化平台，提供日志查询、分析和仪表板功能

### 1.2 数据流程

```
应用日志文件 → Filebeat → Logstash → Elasticsearch → Kibana
```

1. **应用生成日志**：应用将日志写入文件系统（如 `/var/log/app/`）
2. **Filebeat 采集**：Filebeat 监控日志文件，实时采集新增日志
3. **Logstash 处理**：Logstash 接收日志，进行解析、过滤、转换
4. **Elasticsearch 存储**：处理后的日志存储到 Elasticsearch
5. **Kibana 可视化**：通过 Kibana 查询、分析和可视化日志数据

### 1.3 架构优势

- **实时性**：日志实时采集和处理
- **可扩展性**：支持水平扩展，处理大规模日志
- **灵活性**：支持多种日志格式和自定义处理规则
- **可视化**：丰富的图表和仪表板
- **搜索能力**：强大的全文搜索和过滤功能

---

## 二、环境准备

### 2.1 Docker Compose 配置

所有服务通过 Docker Compose 统一管理，配置文件位于 `docker/docker-compose.yml`。

#### 2.1.1 数据卷配置

```yaml
volumes:
  es-data-1:
    driver: local
    driver_opts:
      type: none
      o: bind
      device: E:\docker-data\elasticsearch\node1
  es-data-2:
    driver: local
    driver_opts:
      type: none
      o: bind
      device: E:\docker-data\elasticsearch\node2
  kibana-data:
    driver: local
    driver_opts:
      type: none
      o: bind
      device: E:\docker-data\kibana\kibana
  logstash-data:
    driver: local
    driver_opts:
      type: none
      o: bind
      device: E:\docker-data\logstash
  filebeat-data:
    driver: local
    driver_opts:
      type: none
      o: bind
      device: E:\docker-data\filebeat
```

#### 2.1.2 网络配置

所有服务使用同一个 Docker 网络 `docker-cluster-network`，便于服务间通信。

### 2.2 版本要求

- **Elasticsearch**: 7.17.9
- **Logstash**: 7.17.9
- **Filebeat**: 7.17.9
- **Kibana**: 7.17.9

**重要**：所有组件版本必须一致，否则可能出现兼容性问题。

### 2.3 启动服务

```bash
# 启动所有服务
cd docker
docker-compose up -d

# 启动特定服务
docker-compose up -d es-node1 es-node2 kibana logstash filebeat

# 查看服务状态
docker-compose ps

# 查看日志
docker-compose logs -f logstash
docker-compose logs -f filebeat
```

---

## 三、Elasticsearch 配置

### 3.1 服务配置

Elasticsearch 采用双节点集群配置，提供高可用性。

#### 3.1.1 ES 节点 1 配置

```yaml
es-node1:
  image: elasticsearch:7.17.9
  container_name: es-node1
  restart: always
  networks:
    - docker-cluster-network
  ports:
    - "9200:9200"  # HTTP API 端口
    - "9300:9300"  # 节点间通信端口
  environment:
    - node.name=es-node1
    - cluster.name=es-docker-cluster
    - discovery.seed_hosts=es-node2
    - cluster.initial_master_nodes=es-node1,es-node2
    - ES_JAVA_OPTS=-Xms512m -Xmx512m
    - xpack.security.enabled=false
    - xpack.monitoring.enabled=false
  volumes:
    - es-data-1:/usr/share/elasticsearch/data
```

#### 3.1.2 ES 节点 2 配置

```yaml
es-node2:
  image: elasticsearch:7.17.9
  container_name: es-node2
  restart: always
  networks:
    - docker-cluster-network
  ports:
    - "9201:9200"
    - "9301:9300"
  environment:
    - node.name=es-node2
    - cluster.name=es-docker-cluster
    - discovery.seed_hosts=es-node1
    - cluster.initial_master_nodes=es-node1,es-node2
    - ES_JAVA_OPTS=-Xms512m -Xmx512m
    - xpack.security.enabled=false
    - xpack.monitoring.enabled=false
  volumes:
    - es-data-2:/usr/share/elasticsearch/data
```

### 3.2 关键配置说明

| 配置项 | 说明 |
|--------|------|
| `node.name` | 节点名称，集群内唯一 |
| `cluster.name` | 集群名称，相同名称的节点会加入同一集群 |
| `discovery.seed_hosts` | 集群发现种子节点列表 |
| `cluster.initial_master_nodes` | 初始主节点列表 |
| `ES_JAVA_OPTS` | JVM 参数，设置堆内存大小 |
| `xpack.security.enabled` | 是否启用安全认证（开发环境建议关闭） |

### 3.3 验证 ES 集群

```bash
# 检查集群健康状态
curl http://localhost:9200/_cluster/health?pretty

# 查看集群节点信息
curl http://localhost:9200/_cat/nodes?v

# 查看索引列表
curl http://localhost:9200/_cat/indices?v
```

### 3.4 索引生命周期管理（ILM）

建议配置索引生命周期管理，自动删除旧日志：

```bash
# 创建 ILM 策略（保留 30 天）
curl -X PUT "http://localhost:9200/_ilm/policy/logs-policy" -H 'Content-Type: application/json' -d'
{
  "policy": {
    "phases": {
      "hot": {
        "actions": {
          "rollover": {
            "max_size": "10GB",
            "max_age": "1d"
          }
        }
      },
      "delete": {
        "min_age": "30d",
        "actions": {
          "delete": {}
        }
      }
    }
  }
}'
```

---

## 四、Logstash 配置

### 4.1 服务配置

```yaml
logstash:
  image: logstash:7.17.9
  container_name: logstash
  restart: always
  networks:
    - docker-cluster-network
  ports:
    - "5044:5044"  # Beats 输入端口
    - "9600:9600"  # Logstash API 端口
  volumes:
    - logstash-data:/usr/share/logstash/data
    - ./logstash/config/logstash.yml:/usr/share/logstash/config/logstash.yml:ro
    - ./logstash/pipeline:/usr/share/logstash/pipeline:ro
  environment:
    - LS_JAVA_OPTS=-Xmx512m -Xms512m
    - XPACK_MONITORING_ENABLED=false
  depends_on:
    - es-node1
    - es-node2
```

### 4.2 Logstash 配置文件

#### 4.2.1 `logstash.yml`（主配置）

```yaml
http.host: "0.0.0.0"
xpack.monitoring.enabled: false
xpack.monitoring.elasticsearch.hosts: ["http://es-node1:9200", "http://es-node2:9200"]
```

#### 4.2.2 `logstash.conf`（管道配置）

管道配置包含三个部分：**input**、**filter**、**output**。

##### Input（输入）

```ruby
input {
  # 接收来自 Filebeat 的日志
  beats {
    port => 5044
    codec => json
  }
}
```

##### Filter（过滤）

Filter 部分负责日志解析、转换和增强：

```ruby
filter {
  # 解析 JSON 格式的日志
  if [fields][log_type] == "application" {
    json {
      source => "message"
      target => "parsed"
    }
    
    # 解析时间戳
    date {
      match => [ "timestamp", "yyyy-MM-dd HH:mm:ss.SSS" ]
      target => "@timestamp"
    }
    
    # 添加字段
    mutate {
      add_field => { 
        "log_level" => "%{[parsed][level]}"
        "logger_name" => "%{[parsed][logger]}"
        "thread_name" => "%{[parsed][thread]}"
        "message_content" => "%{[parsed][message]}"
      }
    }
  }
  
  # 处理 Spring Boot 日志格式
  if [fields][log_type] == "spring-boot" {
    grok {
      match => { 
        "message" => "%{TIMESTAMP_ISO8601:timestamp}%{SPACE}%{LOGLEVEL:log_level}%{SPACE}%{NUMBER:pid}%{SPACE}---%{SPACE}\[%{DATA:thread_name}\]%{SPACE}%{JAVACLASS:logger_name}%{SPACE}:%{SPACE}%{GREEDYDATA:message_content}"
      }
    }
    
    date {
      match => [ "timestamp", "yyyy-MM-dd HH:mm:ss.SSS" ]
      target => "@timestamp"
    }
  }
  
  # 处理错误日志
  if [log_level] == "ERROR" {
    mutate {
      add_tag => [ "error" ]
    }
  }
}
```

##### Output（输出）

```ruby
output {
  # 输出到 Elasticsearch
  elasticsearch {
    hosts => ["http://es-node1:9200", "http://es-node2:9200"]
    index => "logs-%{+YYYY.MM.dd}"
    template_name => "logs"
    template_overwrite => true
  }
}
```

### 4.3 常用 Filter 插件

| 插件 | 用途 | 示例 |
|------|------|------|
| `grok` | 解析非结构化日志 | 解析 Spring Boot 日志格式 |
| `json` | 解析 JSON 格式日志 | 解析应用输出的 JSON 日志 |
| `date` | 解析时间戳 | 将日志时间转换为 @timestamp |
| `mutate` | 字段操作 | 添加、删除、重命名字段 |
| `geoip` | IP 地理位置 | 根据 IP 添加地理位置信息 |
| `useragent` | 解析 User-Agent | 解析浏览器/客户端信息 |

### 4.4 验证 Logstash

```bash
# 查看 Logstash 状态
curl http://localhost:9600/_node/stats?pretty

# 查看管道状态
curl http://localhost:9600/_node/pipelines?pretty
```

---

## 五、Filebeat 配置

### 5.1 服务配置

```yaml
filebeat:
  image: docker.elastic.co/beats/filebeat:7.17.9
  container_name: filebeat
  restart: always
  user: root  # 需要 root 权限读取日志文件
  networks:
    - docker-cluster-network
  volumes:
    - filebeat-data:/usr/share/filebeat/data
    - ./filebeat/filebeat.yml:/usr/share/filebeat/filebeat.yml:ro
    - ./logs:/var/log/app:ro  # 挂载应用日志目录
  environment:
    - ELASTICSEARCH_HOSTS=http://es-node1:9200,http://es-node2:9200
    - LOGSTASH_HOSTS=logstash:5044
  depends_on:
    - logstash
    - es-node1
    - es-node2
```

### 5.2 Filebeat 配置文件

#### 5.2.1 输入配置

```yaml
filebeat.inputs:
  # 应用日志输入
  - type: log
    enabled: true
    paths:
      - /var/log/app/*.log
      - /var/log/app/**/*.log
    fields:
      log_type: application
      environment: production
    fields_under_root: false
    multiline.pattern: '^\d{4}-\d{2}-\d{2}'
    multiline.negate: true
    multiline.match: after
    exclude_lines: ['^DEBUG']
    
  # Spring Boot 日志输入
  - type: log
    enabled: true
    paths:
      - /var/log/app/spring-boot/*.log
    fields:
      log_type: spring-boot
      environment: production
```

#### 5.2.2 输出配置

```yaml
# 输出到 Logstash
output.logstash:
  hosts: ["logstash:5044"]
  loadbalance: true
  compression_level: 3
```

#### 5.2.3 处理器配置

```yaml
processors:
  - add_host_metadata:
      when.not.contains.tags: forwarded
  - add_docker_metadata: ~
  - add_kubernetes_metadata: ~
```

### 5.3 关键配置说明

| 配置项 | 说明 |
|--------|------|
| `paths` | 日志文件路径，支持通配符 |
| `fields` | 自定义字段，用于 Logstash 过滤 |
| `multiline` | 多行日志处理配置 |
| `exclude_lines` | 排除的行（如 DEBUG 日志） |
| `loadbalance` | 负载均衡，多个 Logstash 节点时启用 |

### 5.4 验证 Filebeat

```bash
# 查看 Filebeat 状态
docker exec filebeat filebeat test config
docker exec filebeat filebeat test output

# 查看 Filebeat 日志
docker logs filebeat
```

---

## 六、Kibana 配置

### 6.1 服务配置

```yaml
kibana:
  image: kibana:7.17.9
  container_name: kibana
  restart: always
  networks:
    - docker-cluster-network
  ports:
    - "5601:5601"
  volumes:
    - kibana-data:/usr/share/kibana/data
  environment:
    - ELASTICSEARCH_HOSTS=http://es-node1:9200
    - xpack.security.enabled=false
    - I18N_LOCALE=zh-CN
    - SERVER_NAME=kibana
    - SERVER_HOST=0.0.0.0
  depends_on:
    - es-node1
    - es-node2
```

### 6.2 访问 Kibana

1. 打开浏览器访问：`http://localhost:5601`
2. 首次访问需要创建索引模式：
   - 进入 **Management** → **Index Patterns**
   - 输入索引模式：`logs-*`
   - 选择时间字段：`@timestamp`
   - 点击 **Create index pattern**

### 6.3 常用功能

#### 6.3.1 Discover（发现）

- 实时查看和搜索日志
- 支持时间范围过滤
- 支持字段过滤和搜索

#### 6.3.2 Visualize（可视化）

- 创建各种图表（柱状图、饼图、折线图等）
- 分析日志趋势和分布

#### 6.3.3 Dashboard（仪表板）

- 组合多个可视化图表
- 创建监控仪表板

---

## 七、应用集成

### 7.1 Logback 配置

将 `log/config/logback-spring.xml` 复制到 `src/main/resources/` 目录。

#### 7.1.1 关键配置

```xml
<!-- 定义日志文件路径 -->
<property name="LOG_HOME" value="/var/log/app"/>
<property name="LOG_FILE_NAME" value="application"/>

<!-- JSON 格式输出（用于 Logstash 解析） -->
<appender name="JSON_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>${LOG_HOME}/${LOG_FILE_NAME}-json.log</file>
    <encoder class="net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder">
        <providers>
            <timestamp>
                <timeZone>Asia/Shanghai</timeZone>
            </timestamp>
            <version/>
            <logLevel/>
            <loggerName/>
            <message/>
            <mdc/>
            <stackTrace/>
        </providers>
    </encoder>
</appender>
```

#### 7.1.2 依赖配置

在 `pom.xml` 中添加依赖：

```xml
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>7.4</version>
</dependency>
```

### 7.2 应用配置

在 `application.yml` 中配置日志路径：

```yaml
logging:
  file:
    path: /var/log/app
    name: ${logging.file.path}/application.log
```

### 7.3 日志生成示例

参考 `log/example/LogProducer.java`，创建定时任务生成测试日志。

---

## 八、使用指南

### 8.1 启动流程

1. **启动 ES 集群**
   ```bash
   docker-compose up -d es-node1 es-node2
   ```

2. **启动 Logstash**
   ```bash
   docker-compose up -d logstash
   ```

3. **启动 Filebeat**
   ```bash
   docker-compose up -d filebeat
   ```

4. **启动 Kibana**
   ```bash
   docker-compose up -d kibana
   ```

5. **启动应用**
   ```bash
   mvn spring-boot:run
   ```

### 8.2 查看日志

#### 8.2.1 在 Kibana 中查看

1. 访问 `http://localhost:5601`
2. 进入 **Discover** 页面
3. 选择索引模式：`logs-*`
4. 设置时间范围
5. 搜索和过滤日志

#### 8.2.2 常用搜索语法

- **精确匹配**：`message:"错误信息"`
- **字段过滤**：`log_level:ERROR`
- **范围查询**：`@timestamp:[2026-01-20 TO 2026-01-21]`
- **通配符**：`message:*异常*`
- **逻辑运算**：`log_level:ERROR AND logger_name:org.lix.*`

### 8.3 创建可视化

1. 进入 **Visualize** 页面
2. 选择可视化类型（如柱状图、饼图）
3. 选择数据源（索引模式）
4. 配置指标和聚合
5. 保存可视化

### 8.4 创建仪表板

1. 进入 **Dashboard** 页面
2. 点击 **Create dashboard**
3. 添加已创建的可视化
4. 调整布局和大小
5. 保存仪表板

---

## 九、常见问题

### 9.1 Filebeat 无法读取日志文件

**问题**：Filebeat 提示权限不足

**解决**：
- 确保 Filebeat 容器以 root 用户运行
- 检查日志文件权限：`chmod 644 /var/log/app/*.log`

### 9.2 Logstash 无法连接 ES

**问题**：Logstash 报错无法连接到 Elasticsearch

**解决**：
- 检查 ES 节点是否正常运行
- 验证网络连接：`docker exec logstash ping es-node1`
- 检查 ES 地址配置是否正确

### 9.3 Kibana 无法显示日志

**问题**：Kibana 中看不到日志数据

**解决**：
1. 检查索引是否存在：`curl http://localhost:9200/_cat/indices?v`
2. 创建索引模式：`logs-*`
3. 检查时间范围设置
4. 验证数据是否已写入 ES

### 9.4 日志格式解析失败

**问题**：Logstash 无法正确解析日志格式

**解决**：
- 检查 Logstash filter 配置中的 grok 模式
- 使用 Grok Debugger 工具测试模式：`http://localhost:5601/app/dev_tools#/grokdebugger`
- 调整 filter 配置，匹配实际日志格式

### 9.5 性能优化

**问题**：日志处理速度慢

**解决**：
- 增加 Logstash pipeline workers：`pipeline.workers: 4`
- 优化 Filebeat 批量大小
- 增加 ES 节点数量
- 调整 JVM 参数

---

## 十、总结

本文档详细介绍了 ELK 日志中心的搭建、配置和使用方法。通过合理配置各个组件，可以实现：

- ✅ 实时日志采集和处理
- ✅ 强大的日志搜索和分析能力
- ✅ 丰富的可视化展示
- ✅ 高可用和可扩展的架构

建议在生产环境中：
1. 启用 ES 安全认证
2. 配置索引生命周期管理
3. 设置日志保留策略
4. 监控各组件运行状态
5. 定期备份重要日志数据

---

**文档版本**：v1.0  
**最后更新**：2026-01-20

