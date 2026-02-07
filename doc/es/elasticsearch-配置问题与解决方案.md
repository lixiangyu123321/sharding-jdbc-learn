# Elasticsearch 配置问题与解决方案

## 问题概述

在使用 Docker Compose 部署 Elasticsearch 7.17.9 集群时，遇到了两个主要问题：

1. **Elasticsearch 启动失败**：节点配置中使用了索引级别的设置
2. **Kibana 索引分片分配失败**：索引副本分片无法分配，导致搜索被拒绝

---

## 问题一：Elasticsearch 启动失败

### 错误信息

```
java.lang.IllegalArgumentException: node settings must not contain any index level settings
```

### 问题原因

在 `docker-compose.yml` 的 Elasticsearch 节点环境变量配置中，错误地使用了索引级别的设置：

```yaml
environment:
  - index.number_of_replicas=0  # ❌ 错误：这是索引级别设置，不能放在节点配置中
```

在 Elasticsearch 7.x 版本中，`index.number_of_replicas` 是**索引级别**的设置，不能作为**节点级别**的环境变量配置。节点级别的配置只能包含集群级别和节点级别的设置。

### 解决方案

**1. 移除错误的索引级别配置**

从 `docker-compose.yml` 中移除 `index.number_of_replicas=0` 配置：

```yaml
environment:
  - node.name=es-node1
  - cluster.name=es-docker-cluster
  - discovery.seed_hosts=es-node2
  - cluster.initial_master_nodes=es-node1,es-node2
  - ES_JAVA_OPTS=-Xms512m -Xmx512m
  - xpack.security.enabled=false  # ✅ 修正：之前错误写成了 xpack.org.lix.mycatdemo.security.enabled=false
  - xpack.monitoring.enabled=false
  - action.auto_create_index=true
  - cluster.routing.allocation.disk.threshold_enabled=false
  - cluster.routing.allocation.enable=all
  - cluster.routing.allocation.node_concurrent_recoveries=2
  # ❌ 移除：- index.number_of_replicas=0
```

**2. 通过 API 设置默认索引模板**

如果需要为所有新索引设置默认副本数为 0，可以通过 API 创建索引模板：

```bash
docker exec es-node1 curl -X PUT "localhost:9200/_template/default" \
  -H 'Content-Type: application/json' \
  -d '{
    "index_patterns": ["*"],
    "settings": {
      "number_of_replicas": 0
    }
  }'
```

这样所有新创建的索引都会自动使用 0 个副本。

---

## 问题二：Kibana 索引分片分配失败

### 错误信息

```
org.elasticsearch.action.search.SearchPhaseExecutionException: 
Search rejected due to missing shards [[.kibana_7.17.9_001][0]]. 
Consider using `allow_partial_search_results` setting to bypass this error.
```

### 问题原因

1. **索引副本分片无法分配**：Kibana 创建的索引默认有副本，但在集群恢复过程中，某些索引的分片数据丢失（`allocation_status: no_valid_shard_copy`），导致分片无法分配。

2. **集群状态为 RED**：由于存在未分配的分片，集群健康状态变为 RED，影响 Kibana 的正常使用。

### 解决方案

#### 步骤 1：修复现有 Kibana 索引的副本数

```bash
# 修复所有 Kibana 索引的副本数和自动扩展设置
docker exec es-node1 curl -X PUT "localhost:9200/.kibana*/_settings" \
  -H 'Content-Type: application/json' \
  -d '{
    "index": {
      "number_of_replicas": 0,
      "auto_expand_replicas": "0-0"
    }
  }'
```

#### 步骤 2：删除并重建损坏的索引

如果索引的主分片处于 `UNASSIGNED` 状态且无法恢复，需要删除索引让 Kibana 重新创建：

```bash
# 检查索引状态
docker exec es-node1 curl -s "localhost:9200/_cat/indices/.kibana*?v"

# 删除损坏的索引（Kibana 会自动重新创建）
docker exec es-node1 curl -X DELETE "localhost:9200/.kibana_7.17.9_001"
```

#### 步骤 3：修复其他有问题的索引

```bash
# 修复日志索引
docker exec es-node1 curl -X PUT "localhost:9200/logs-*/_settings" \
  -H 'Content-Type: application/json' \
  -d '{
    "index": {
      "number_of_replicas": 0,
      "auto_expand_replicas": "0-0"
    }
  }'

# 修复 .tasks 索引
docker exec es-node1 curl -X PUT "localhost:9200/.tasks/_settings" \
  -H 'Content-Type: application/json' \
  -d '{
    "index": {
      "number_of_replicas": 0,
      "auto_expand_replicas": "0-0"
    }
  }'
```

#### 步骤 4：清理无法恢复的索引

对于数据已完全丢失的索引（`allocation_status: no_valid_shard_copy`），可以删除它们：

```bash
# 删除无法恢复的旧日志索引
docker exec es-node1 curl -X DELETE "localhost:9200/logs-2026.01.21,logs-2026.01.22,logs-2026.01.23,logs-2026.01.26,logs-2026.01.27,logs-2026.01.28,logs-2026.01.29"

# 删除无法恢复的 .tasks 索引
docker exec es-node1 curl -X DELETE "localhost:9200/.tasks"
```

**注意**：某些系统保留索引（如 `.geoip_databases` 和数据流索引）无法直接删除，这些索引的未分配分片不会影响正常使用。

#### 步骤 5：验证修复结果

```bash
# 检查集群健康状态
docker exec es-node1 curl -s "localhost:9200/_cluster/health?pretty"

# 检查 Kibana 索引状态
docker exec es-node1 curl -s "localhost:9200/_cat/indices/.kibana*?v"

# 检查未分配的分片
docker exec es-node1 curl -s "localhost:9200/_cat/shards?h=index,shard,prirep,state" | grep UNASSIGNED
```

---

## 完整修复流程总结

### 1. 修复 docker-compose.yml 配置

```yaml
# 移除索引级别配置
# ❌ - index.number_of_replicas=0

# 修正安全配置
# ✅ - xpack.security.enabled=false
```

### 2. 创建默认索引模板

```bash
docker exec es-node1 curl -X PUT "localhost:9200/_template/default" \
  -H 'Content-Type: application/json' \
  -d '{"index_patterns": ["*"], "settings": {"number_of_replicas": 0}}'
```

### 3. 修复现有索引

```bash
# 批量修复所有索引的副本数
docker exec es-node1 curl -X PUT "localhost:9200/.kibana*/_settings" \
  -H 'Content-Type: application/json' \
  -d '{"index":{"number_of_replicas":0,"auto_expand_replicas":"0-0"}}'

docker exec es-node1 curl -X PUT "localhost:9200/logs-*/_settings" \
  -H 'Content-Type: application/json' \
  -d '{"index":{"number_of_replicas":0,"auto_expand_replicas":"0-0"}}'
```

### 4. 清理无法恢复的索引

```bash
# 删除数据丢失的索引
docker exec es-node1 curl -X DELETE "localhost:9200/logs-2026.01.*"
docker exec es-node1 curl -X DELETE "localhost:9200/.tasks"
```

### 5. 重启服务（如需要）

```bash
cd docker
docker-compose restart es-node1 es-node2 kibana
```

---

## 预防措施

### 1. 正确的配置方式

- ✅ **节点级别配置**：使用环境变量设置集群和节点级别的配置
- ✅ **索引级别配置**：通过 API 或索引模板设置
- ❌ **不要混用**：不要在节点配置中使用索引级别设置

### 2. 推荐的索引模板配置

在 Elasticsearch 启动后，立即创建默认索引模板：

```bash
# 创建默认模板，所有新索引使用 0 个副本
curl -X PUT "localhost:9200/_template/default" \
  -H 'Content-Type: application/json' \
  -d '{
    "index_patterns": ["*"],
    "settings": {
      "number_of_replicas": 0,
      "auto_expand_replicas": "0-0"
    }
  }'
```

### 3. 监控集群健康状态

定期检查集群健康状态：

```bash
# 检查集群健康
curl "localhost:9200/_cluster/health?pretty"

# 检查未分配的分片
curl "localhost:9200/_cat/shards?h=index,shard,prirep,state" | grep UNASSIGNED

# 检查所有索引状态
curl "localhost:9200/_cat/indices?v"
```

---

## 相关配置说明

### 节点级别 vs 索引级别设置

| 设置类型 | 配置位置 | 示例 |
|---------|---------|------|
| **节点级别** | docker-compose.yml 环境变量 | `cluster.name`, `node.name`, `xpack.security.enabled` |
| **索引级别** | 索引模板或创建索引时 | `number_of_replicas`, `number_of_shards` |

### 常用的节点级别配置

```yaml
environment:
  # 节点标识
  - node.name=es-node1
  - cluster.name=es-docker-cluster
  
  # 集群发现
  - discovery.seed_hosts=es-node2
  - cluster.initial_master_nodes=es-node1,es-node2
  
  # JVM 配置
  - ES_JAVA_OPTS=-Xms512m -Xmx512m
  
  # 安全配置
  - xpack.security.enabled=false
  - xpack.monitoring.enabled=false
  
  # 集群路由配置
  - cluster.routing.allocation.enable=all
  - cluster.routing.allocation.disk.threshold_enabled=false
  - cluster.routing.allocation.node_concurrent_recoveries=2
  
  # 索引自动创建
  - action.auto_create_index=true
```

### 常用的索引级别配置（通过模板设置）

```json
{
  "index_patterns": ["*"],
  "settings": {
    "number_of_replicas": 0,
    "number_of_shards": 1,
    "auto_expand_replicas": "0-0",
    "refresh_interval": "1s"
  }
}
```

---

## 验证修复

修复完成后，验证以下内容：

1. ✅ Elasticsearch 节点正常启动
2. ✅ 集群健康状态为 GREEN 或 YELLOW（允许少量系统索引的未分配分片）
3. ✅ 所有 Kibana 索引状态为 GREEN
4. ✅ Kibana 可以正常访问和使用
5. ✅ 新创建的索引自动使用 0 个副本

---

## 参考资源

- [Elasticsearch 官方文档 - Index Settings](https://www.elastic.co/guide/en/elasticsearch/reference/7.17/index-modules.html)
- [Elasticsearch 官方文档 - Index Templates](https://www.elastic.co/guide/en/elasticsearch/reference/7.17/index-templates.html)
- [Elasticsearch 官方文档 - Cluster Settings](https://www.elastic.co/guide/en/elasticsearch/reference/7.17/cluster-update-settings.html)

---

## 问题记录

- **问题发生时间**：2026-02-07
- **Elasticsearch 版本**：7.17.9
- **Docker Compose 版本**：最新
- **问题影响范围**：Elasticsearch 集群启动、Kibana 索引访问
- **修复状态**：✅ 已完全修复

