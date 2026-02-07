# Elasticsearch 分片问题排查与解决指南

## 问题概述

在 Elasticsearch 集群运行过程中，可能会遇到索引分片无法分配的问题，导致集群状态变为 RED 或 YELLOW，影响服务的正常使用。

---

## 一、问题原因分析

### 1.1 常见原因

#### 原因 1：数据文件损坏（最常见）

**表现**：
- 分片状态：`UNASSIGNED`
- 错误信息：`allocation_status: no_valid_shard_copy`
- 可能包含：`CorruptIndexException`、`codec header mismatch`

**原因**：
- 集群异常关闭或重启
- 磁盘 I/O 错误
- 数据目录损坏
- 节点崩溃导致数据不一致

#### 原因 2：集群恢复后数据丢失

**表现**：
- 分片状态：`UNASSIGNED`
- 错误信息：`reason: CLUSTER_RECOVERED`
- 错误信息：`allocation_status: no_valid_shard_copy`

**原因**：
- 集群重启后，某些节点的数据丢失
- 主分片和副本分片的数据都不可用
- 集群无法找到有效的分片副本

#### 原因 3：节点资源不足

**表现**：
- 分片状态：`UNASSIGNED`
- 错误信息：`reason: NODE_LEFT` 或资源相关错误

**原因**：
- 磁盘空间不足
- 内存不足
- CPU 负载过高

#### 原因 4：索引配置问题

**表现**：
- 分片状态：`UNASSIGNED`
- 错误信息：配置相关错误

**原因**：
- 索引副本数设置过高，但节点数量不足
- 分片分配策略限制
- 索引模板配置错误

---

## 二、问题排查步骤

### 步骤 1：检查集群健康状态

```bash
# 检查集群整体健康状态
docker exec es-node1 curl -s "localhost:9200/_cluster/health?pretty"
```

**关键指标**：
- `status`: GREEN（健康）、YELLOW（警告）、RED（严重）
- `unassigned_shards`: 未分配的分片数量
- `active_shards_percent_as_number`: 活动分片百分比

### 步骤 2：查找所有未分配的分片

```bash
# 查看所有未分配的分片
docker exec es-node1 curl -s "localhost:9200/_cat/shards?h=index,shard,prirep,state,unassigned.reason" | grep UNASSIGNED
```

**输出说明**：
- `index`: 索引名称
- `shard`: 分片编号
- `prirep`: `p`=主分片，`r`=副本分片
- `state`: 分片状态
- `unassigned.reason`: 未分配的原因

### 步骤 3：检查特定索引的状态

```bash
# 检查特定索引的状态
docker exec es-node1 curl -s "localhost:9200/_cat/indices/索引名称?v"

# 检查特定索引的分片详情
docker exec es-node1 curl -s "localhost:9200/_cat/shards/索引名称?v"
```

### 步骤 4：查看分片分配详情

```bash
# 获取详细的分片分配信息
docker exec es-node1 curl -s "localhost:9200/_cluster/allocation/explain?pretty"
```

**或者针对特定索引**：
```bash
docker exec es-node1 curl -X POST "localhost:9200/_cluster/allocation/explain" \
  -H 'Content-Type: application/json' \
  -d '{"index":"索引名称","shard":0,"primary":true}'
```

### 步骤 5：检查节点状态

```bash
# 查看所有节点状态
docker exec es-node1 curl -s "localhost:9200/_cat/nodes?v"

# 查看节点磁盘使用情况
docker exec es-node1 curl -s "localhost:9200/_cat/allocation?v"
```

---

## 三、解决方案

### 方案 1：修复数据损坏的分片（推荐）

适用于：数据文件损坏、集群恢复后数据丢失的情况

#### 步骤 1：获取索引 UUID

```bash
# 获取索引的 UUID
docker exec es-node1 curl -s "localhost:9200/_cat/indices/索引名称?h=uuid"
```

#### 步骤 2：删除损坏的数据目录

```bash
# 在容器内查找索引数据目录
docker exec es-node1 find /usr/share/elasticsearch/data -name "*UUID*" -type d

# 删除损坏的数据目录（UUID 替换为实际的 UUID）
docker exec es-node1 rm -rf /usr/share/elasticsearch/data/nodes/0/indices/UUID
```

**注意**：
- 删除数据目录会导致该索引的数据丢失
- 确保这是可以接受的数据丢失
- 系统保留索引（如 `.geoip_databases`）无法直接删除，需要特殊处理

#### 步骤 3：强制分配空主分片

```bash
# 获取节点 ID
docker exec es-node1 curl -s "localhost:9200/_cat/nodes?h=id" | head -1

# 强制分配空主分片（替换节点 ID 和索引名称）
docker exec es-node1 curl -X POST "localhost:9200/_cluster/reroute" \
  -H 'Content-Type: application/json' \
  -d '{
    "commands": [{
      "allocate_empty_primary": {
        "index": "索引名称",
        "shard": 0,
        "node": "节点ID",
        "accept_data_loss": true
      }
    }]
  }'
```

#### 步骤 4：验证修复结果

```bash
# 等待几秒钟后检查索引状态
sleep 5
docker exec es-node1 curl -s "localhost:9200/_cat/indices/索引名称?v"
docker exec es-node1 curl -s "localhost:9200/_cat/shards/索引名称?v"
```

### 方案 2：调整索引副本数

适用于：副本数设置过高，节点数量不足的情况

```bash
# 将索引副本数设置为 0
docker exec es-node1 curl -X PUT "localhost:9200/索引名称/_settings" \
  -H 'Content-Type: application/json' \
  -d '{
    "index": {
      "number_of_replicas": 0,
      "auto_expand_replicas": "0-0"
    }
  }'
```

### 方案 3：删除并重建索引

适用于：非关键索引，可以接受数据丢失的情况

```bash
# 删除索引（注意：数据会丢失）
docker exec es-node1 curl -X DELETE "localhost:9200/索引名称"

# 索引会在下次使用时自动重建
```

**注意**：
- 系统保留索引（以 `.` 开头的索引）无法直接删除
- 某些索引（如数据流索引）可能需要特殊处理

### 方案 4：修复副本分片

如果主分片正常，但副本分片无法分配：

```bash
# 方法 1：减少副本数
docker exec es-node1 curl -X PUT "localhost:9200/索引名称/_settings" \
  -H 'Content-Type: application/json' \
  -d '{"index":{"number_of_replicas":0}}'

# 方法 2：强制分配副本分片
docker exec es-node1 curl -X POST "localhost:9200/_cluster/reroute" \
  -H 'Content-Type: application/json' \
  -d '{
    "commands": [{
      "allocate_replica": {
        "index": "索引名称",
        "shard": 0,
        "node": "节点ID"
      }
    }]
  }'
```

---

## 四、完整修复流程示例

### 示例：修复 `.geoip_databases` 索引

```bash
# 1. 检查索引状态
docker exec es-node1 curl -s "localhost:9200/_cat/indices/.geoip_databases?v"
docker exec es-node1 curl -s "localhost:9200/_cat/shards/.geoip_databases?v"

# 2. 获取索引 UUID
UUID=$(docker exec es-node1 curl -s "localhost:9200/_cat/indices/.geoip_databases?h=uuid")
echo "索引 UUID: $UUID"

# 3. 删除损坏的数据目录
docker exec es-node1 rm -rf /usr/share/elasticsearch/data/nodes/0/indices/$UUID

# 4. 获取节点 ID
NODE_ID=$(docker exec es-node1 curl -s "localhost:9200/_cat/nodes?h=id" | head -1)
echo "节点 ID: $NODE_ID"

# 5. 强制分配空主分片
docker exec es-node1 curl -X POST "localhost:9200/_cluster/reroute" \
  -H 'Content-Type: application/json' \
  -d "{
    \"commands\": [{
      \"allocate_empty_primary\": {
        \"index\": \".geoip_databases\",
        \"shard\": 0,
        \"node\": \"$NODE_ID\",
        \"accept_data_loss\": true
      }
    }]
  }"

# 6. 等待并验证
sleep 5
docker exec es-node1 curl -s "localhost:9200/_cat/indices/.geoip_databases?v"
docker exec es-node1 curl -s "localhost:9200/_cat/shards/.geoip_databases?v"
```

### 示例：批量修复多个索引

```bash
# 查找所有有问题的索引
PROBLEM_INDICES=$(docker exec es-node1 curl -s "localhost:9200/_cat/shards?h=index,state" | grep UNASSIGNED | awk '{print $1}' | sort -u)

# 遍历修复
for INDEX in $PROBLEM_INDICES; do
  echo "修复索引: $INDEX"
  
  # 获取 UUID
  UUID=$(docker exec es-node1 curl -s "localhost:9200/_cat/indices/$INDEX?h=uuid")
  
  # 删除数据目录
  docker exec es-node1 rm -rf /usr/share/elasticsearch/data/nodes/0/indices/$UUID
  
  # 获取节点 ID
  NODE_ID=$(docker exec es-node1 curl -s "localhost:9200/_cat/nodes?h=id" | head -1)
  
  # 强制分配
  docker exec es-node1 curl -X POST "localhost:9200/_cluster/reroute" \
    -H 'Content-Type: application/json' \
    -d "{
      \"commands\": [{
        \"allocate_empty_primary\": {
          \"index\": \"$INDEX\",
          \"shard\": 0,
          \"node\": \"$NODE_ID\",
          \"accept_data_loss\": true
        }
      }]
    }"
  
  sleep 2
done
```

---

## 五、常见错误信息解析

### 错误 1：`allocation_status: no_valid_shard_copy`

**含义**：没有有效的分片副本可以恢复

**原因**：
- 数据文件损坏
- 所有分片副本都丢失

**解决方案**：
- 删除损坏的数据目录
- 强制分配空主分片

### 错误 2：`reason: CLUSTER_RECOVERED`

**含义**：集群恢复后无法找到分片

**原因**：
- 集群重启后数据不一致
- 分片数据在恢复过程中丢失

**解决方案**：
- 如果数据不重要：删除并重建
- 如果数据重要：尝试从备份恢复

### 错误 3：`reason: REPLICA_ADDED`

**含义**：副本分片无法分配

**原因**：
- 节点数量不足
- 分片分配策略限制

**解决方案**：
- 减少副本数
- 增加节点数量
- 调整分片分配策略

### 错误 4：`CorruptIndexException`

**含义**：索引文件损坏

**原因**：
- 磁盘 I/O 错误
- 异常关闭
- 数据文件损坏

**解决方案**：
- 删除损坏的数据目录
- 强制分配空主分片
- 从备份恢复（如果有）

---

## 六、预防措施

### 6.1 配置建议

#### 1. 设置合理的副本数

```yaml
# docker-compose.yml 中通过索引模板设置
# 或者在创建索引时设置
```

**推荐配置**：
- 开发/测试环境：`number_of_replicas: 0`
- 生产环境：`number_of_replicas: 1`（至少 2 个节点）

#### 2. 创建默认索引模板

```bash
# 创建默认模板，所有新索引使用 0 个副本
docker exec es-node1 curl -X PUT "localhost:9200/_template/default" \
  -H 'Content-Type: application/json' \
  -d '{
    "index_patterns": ["*"],
    "settings": {
      "number_of_replicas": 0,
      "auto_expand_replicas": "0-0"
    }
  }'
```

#### 3. 定期备份

```bash
# 使用 Elasticsearch 快照功能定期备份
# 配置快照仓库
docker exec es-node1 curl -X PUT "localhost:9200/_snapshot/backup_repo" \
  -H 'Content-Type: application/json' \
  -d '{
    "type": "fs",
    "settings": {
      "location": "/backup"
    }
  }'

# 创建快照
docker exec es-node1 curl -X PUT "localhost:9200/_snapshot/backup_repo/snapshot_1?wait_for_completion=true"
```

### 6.2 监控建议

#### 1. 监控集群健康状态

```bash
# 定期检查集群健康
watch -n 30 'docker exec es-node1 curl -s "localhost:9200/_cluster/health?pretty"'
```

#### 2. 监控未分配分片

```bash
# 检查未分配分片数量
docker exec es-node1 curl -s "localhost:9200/_cluster/health?pretty" | grep unassigned_shards
```

#### 3. 监控磁盘空间

```bash
# 检查节点磁盘使用情况
docker exec es-node1 curl -s "localhost:9200/_cat/allocation?v"
```

### 6.3 运维建议

1. **优雅关闭**：停止服务前先停止数据写入，然后优雅关闭 ES
2. **定期维护**：定期检查集群健康状态和未分配分片
3. **资源监控**：监控磁盘空间、内存、CPU 使用情况
4. **日志检查**：定期检查 ES 日志，及时发现潜在问题

---

## 七、快速诊断脚本

创建 PowerShell 诊断脚本 `check-es-shards.ps1`：

```powershell
# check-es-shards.ps1
# Elasticsearch 分片问题诊断脚本

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   Elasticsearch 分片诊断脚本" -ForegroundColor Cyan
Write-Host "========================================`n" -ForegroundColor Cyan

# 1. 检查集群健康状态
Write-Host "[1/4] 检查集群健康状态..." -ForegroundColor Yellow
$health = docker exec es-node1 curl -s "localhost:9200/_cluster/health?pretty"
Write-Host $health

$status = ($health | Select-String -Pattern '"status" : "(\w+)"').Matches.Groups[1].Value
$unassigned = ($health | Select-String -Pattern '"unassigned_shards" : (\d+)').Matches.Groups[1].Value

if ($status -eq "green" -and $unassigned -eq "0") {
    Write-Host "✓ 集群健康状态正常" -ForegroundColor Green
} else {
    Write-Host "⚠ 集群存在问题" -ForegroundColor Yellow
    Write-Host "  状态: $status" -ForegroundColor Gray
    Write-Host "  未分配分片: $unassigned" -ForegroundColor Gray
}

# 2. 查找未分配的分片
Write-Host "`n[2/4] 查找未分配的分片..." -ForegroundColor Yellow
$unassignedShards = docker exec es-node1 curl -s "localhost:9200/_cat/shards?h=index,shard,prirep,state,unassigned.reason" | Select-String -Pattern "UNASSIGNED"

if ($unassignedShards) {
    Write-Host "发现未分配的分片:" -ForegroundColor Red
    $unassignedShards | ForEach-Object { Write-Host "  $_" -ForegroundColor Gray }
} else {
    Write-Host "✓ 没有未分配的分片" -ForegroundColor Green
}

# 3. 检查 RED 状态的索引
Write-Host "`n[3/4] 检查 RED 状态的索引..." -ForegroundColor Yellow
$redIndices = docker exec es-node1 curl -s "localhost:9200/_cat/indices?v" | Select-String -Pattern "red"

if ($redIndices) {
    Write-Host "发现 RED 状态的索引:" -ForegroundColor Red
    $redIndices | ForEach-Object { Write-Host "  $_" -ForegroundColor Gray }
} else {
    Write-Host "✓ 没有 RED 状态的索引" -ForegroundColor Green
}

# 4. 检查节点状态
Write-Host "`n[4/4] 检查节点状态..." -ForegroundColor Yellow
docker exec es-node1 curl -s "localhost:9200/_cat/nodes?v" | Select-Object -First 5

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "   诊断完成" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
```

使用方法：

```powershell
.\check-es-shards.ps1
```

---

## 八、修复脚本示例

创建自动修复脚本 `fix-es-shards.ps1`：

```powershell
# fix-es-shards.ps1
# 自动修复未分配的分片

param(
    [string]$IndexName = "",
    [switch]$All = $false
)

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   Elasticsearch 分片修复脚本" -ForegroundColor Cyan
Write-Host "========================================`n" -ForegroundColor Cyan

# 获取节点 ID
$nodeId = (docker exec es-node1 curl -s "localhost:9200/_cat/nodes?h=id" | Select-Object -First 1).Trim()
Write-Host "使用节点 ID: $nodeId" -ForegroundColor Cyan

if ($All) {
    # 修复所有未分配的分片
    Write-Host "`n查找所有未分配的分片..." -ForegroundColor Yellow
    $unassigned = docker exec es-node1 curl -s "localhost:9200/_cat/shards?h=index,shard,prirep,state" | Select-String -Pattern "UNASSIGNED"
    
    $indices = $unassigned | ForEach-Object {
        ($_ -split '\s+')[0]
    } | Sort-Object -Unique
    
    foreach ($idx in $indices) {
        Write-Host "`n修复索引: $idx" -ForegroundColor Yellow
        
        # 获取 UUID
        $uuid = (docker exec es-node1 curl -s "localhost:9200/_cat/indices/$idx?h=uuid").Trim()
        Write-Host "  UUID: $uuid" -ForegroundColor Gray
        
        # 删除数据目录
        docker exec es-node1 rm -rf "/usr/share/elasticsearch/data/nodes/0/indices/$uuid" 2>$null
        
        # 强制分配
        docker exec es-node1 curl -X POST "localhost:9200/_cluster/reroute" `
          -H 'Content-Type: application/json' `
          -d "{\"commands\":[{\"allocate_empty_primary\":{\"index\":\"$idx\",\"shard\":0,\"node\":\"$nodeId\",\"accept_data_loss\":true}}]}"
        
        Start-Sleep -Seconds 2
    }
} elseif ($IndexName) {
    # 修复指定索引
    Write-Host "修复索引: $IndexName" -ForegroundColor Yellow
    
    $uuid = (docker exec es-node1 curl -s "localhost:9200/_cat/indices/$IndexName?h=uuid").Trim()
    Write-Host "UUID: $uuid" -ForegroundColor Gray
    
    docker exec es-node1 rm -rf "/usr/share/elasticsearch/data/nodes/0/indices/$uuid" 2>$null
    
    docker exec es-node1 curl -X POST "localhost:9200/_cluster/reroute" `
      -H 'Content-Type: application/json' `
      -d "{\"commands\":[{\"allocate_empty_primary\":{\"index\":\"$IndexName\",\"shard\":0,\"node\":\"$nodeId\",\"accept_data_loss\":true}}]}"
} else {
    Write-Host "请指定要修复的索引名称，或使用 -All 参数修复所有" -ForegroundColor Yellow
    Write-Host "用法: .\fix-es-shards.ps1 -IndexName '索引名称'" -ForegroundColor Gray
    Write-Host "     .\fix-es-shards.ps1 -All" -ForegroundColor Gray
    exit
}

Write-Host "`n等待修复完成..." -ForegroundColor Yellow
Start-Sleep -Seconds 5

# 验证结果
Write-Host "`n验证修复结果..." -ForegroundColor Yellow
docker exec es-node1 curl -s "localhost:9200/_cluster/health?pretty" | Select-String -Pattern "status|unassigned_shards"

Write-Host "`n完成！" -ForegroundColor Green
```

使用方法：

```powershell
# 修复指定索引
.\fix-es-shards.ps1 -IndexName "sw_management"

# 修复所有未分配的分片
.\fix-es-shards.ps1 -All
```

---

## 九、注意事项

### 9.1 数据丢失警告

⚠️ **重要**：使用 `allocate_empty_primary` 会创建空的主分片，**会导致该索引的所有数据丢失**。

**建议**：
- 修复前确认数据是否可以丢失
- 如果有备份，优先考虑从备份恢复
- 对于重要数据，先尝试其他恢复方法

### 9.2 系统保留索引

某些索引是系统保留的，无法直接删除或修改：

- `.geoip_databases` - GeoIP 数据库
- `.kibana*` - Kibana 配置索引
- `.tasks` - 任务索引
- `.ds-*` - 数据流索引

**处理方式**：
- 只能通过删除数据目录 + 强制分配的方式修复
- 无法通过 API 直接删除

### 9.3 集群状态

修复过程中：
- 集群状态可能暂时变为 RED
- 某些操作可能需要几分钟完成
- 建议在业务低峰期进行修复

---

## 十、问题记录与总结

### 10.1 本次修复的问题

**问题时间**：2026-02-07

**修复的索引**：
1. `.geoip_databases` - GeoIP 数据库索引
2. `.kibana_task_manager_7.17.9_001` - Kibana 任务管理器索引
3. `sw_management` - SkyWalking 管理索引（第一次修复）
4. `.ds-.logs-deprecation.elasticsearch-default-2026.01.20-000001` - 数据流索引
5. `sw_management` - SkyWalking 管理索引（第二次修复，数据文件再次损坏）

**修复前状态**：
- 集群状态：RED
- 未分配分片：6 个（第一次），2 个（第二次）
- 健康度：66.67%（第一次），99.xx%（第二次）

**修复后状态**：
- 集群状态：GREEN
- 未分配分片：0 个
- 健康度：100%

**第二次修复详情**（2026-02-07 14:14）：
- **问题**：`sw_management` 索引的数据文件再次损坏
- **错误信息**：`CorruptIndexException: codec header mismatch: actual header=626283332 vs expected header=1071082519`
- **损坏文件**：`/usr/share/elasticsearch/data/nodes/0/indices/z7OkPuAtSMOEzKXzbC34HQ/0/index/_1f_Lucene84_0.doc`
- **影响节点**：两个节点都有该索引的数据目录，但数据都已损坏
- **修复方法**：
  1. 删除两个节点上的损坏数据目录
  2. 强制分配空主分片到 es-node1
  3. Elasticsearch 自动创建新的空索引
  4. SkyWalking 重新写入管理数据
- **修复结果**：索引状态从 RED 恢复为 GREEN，主分片和副本分片都正常启动

### 10.2 根本原因

根据错误信息和修复过程，本次问题的根本原因是：

1. **集群异常恢复**：集群在异常关闭或重启后，某些索引的数据文件损坏
2. **数据文件损坏**：索引的 Lucene 数据文件损坏（`CorruptIndexException`）
3. **分片副本丢失**：主分片和副本分片的数据都不可用（`no_valid_shard_copy`）

### 10.3 预防措施总结

1. ✅ 创建默认索引模板，设置合理的副本数
2. ✅ 定期监控集群健康状态
3. ✅ 优雅关闭 Elasticsearch 服务
4. ✅ 定期备份重要数据
5. ✅ 监控磁盘空间和节点资源

---

## 十一、参考资源

- [Elasticsearch 官方文档 - Cluster Health](https://www.elastic.co/guide/en/elasticsearch/reference/7.17/cluster-health.html)
- [Elasticsearch 官方文档 - Shard Allocation](https://www.elastic.co/guide/en/elasticsearch/reference/7.17/modules-cluster.html#shard-allocation)
- [Elasticsearch 官方文档 - Cluster Reroute](https://www.elastic.co/guide/en/elasticsearch/reference/7.17/cluster-reroute.html)
- [Elasticsearch 故障排查指南](https://www.elastic.co/guide/en/elasticsearch/reference/7.17/troubleshooting.html)

---

## 十二、快速参考命令

### 检查命令

```bash
# 集群健康状态
docker exec es-node1 curl -s "localhost:9200/_cluster/health?pretty"

# 未分配的分片
docker exec es-node1 curl -s "localhost:9200/_cat/shards?h=index,shard,prirep,state" | grep UNASSIGNED

# 所有索引状态
docker exec es-node1 curl -s "localhost:9200/_cat/indices?v"

# 特定索引状态
docker exec es-node1 curl -s "localhost:9200/_cat/indices/索引名称?v"
```

### 修复命令

```bash
# 获取索引 UUID
docker exec es-node1 curl -s "localhost:9200/_cat/indices/索引名称?h=uuid"

# 删除数据目录
docker exec es-node1 rm -rf /usr/share/elasticsearch/data/nodes/0/indices/UUID

# 强制分配空主分片
docker exec es-node1 curl -X POST "localhost:9200/_cluster/reroute" \
  -H 'Content-Type: application/json' \
  -d '{"commands":[{"allocate_empty_primary":{"index":"索引名称","shard":0,"node":"节点ID","accept_data_loss":true}}]}'

# 设置索引副本数为 0
docker exec es-node1 curl -X PUT "localhost:9200/索引名称/_settings" \
  -H 'Content-Type: application/json' \
  -d '{"index":{"number_of_replicas":0}}'
```

---

## 附录：完整修复流程检查清单

修复分片问题时，按以下清单逐步执行：

- [ ] 检查集群健康状态
- [ ] 查找所有未分配的分片
- [ ] 确认未分配的原因（`allocation_status`）
- [ ] 检查索引是否重要（是否可以接受数据丢失）
- [ ] 获取索引 UUID
- [ ] 删除损坏的数据目录
- [ ] 获取节点 ID
- [ ] 强制分配空主分片
- [ ] 等待分片初始化（5-10 秒）
- [ ] 验证索引状态变为 GREEN
- [ ] 验证集群健康状态
- [ ] 检查是否有其他未分配的分片
- [ ] 记录修复过程和结果

---

**文档版本**：1.0  
**最后更新**：2026-02-07  
**适用版本**：Elasticsearch 7.17.9

