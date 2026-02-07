# 删除 Elasticsearch 中的 SkyWalking 数据

## 方法一：删除所有 SkyWalking 索引（推荐）

SkyWalking 在 Elasticsearch 中创建的索引通常以 `sw_` 开头。

### 删除所有 sw_ 开头的索引

```bash
# 删除所有 SkyWalking 相关索引
docker exec es-node1 curl -X DELETE "localhost:9200/sw_*"
```

### 验证删除结果

```bash
# 检查是否还有 sw_ 开头的索引
docker exec es-node1 curl -s "localhost:9200/_cat/indices/sw_*?v"
```

---

## 方法二：逐个删除索引

如果需要更精确的控制，可以逐个删除：

```bash
# 删除 segment 数据（链路追踪数据）
docker exec es-node1 curl -X DELETE "localhost:9200/sw_segment-*"

# 删除 log 数据（日志数据）
docker exec es-node1 curl -X DELETE "localhost:9200/sw_log-*"

# 删除 metrics 数据（指标数据）
docker exec es-node1 curl -X DELETE "localhost:9200/sw_metrics-*"

# 删除 records 数据（记录数据）
docker exec es-node1 curl -X DELETE "localhost:9200/sw_records-*"

# 删除 browser error log 数据
docker exec es-node1 curl -X DELETE "localhost:9200/sw_browser_error_log-*"

# 删除 zipkin span 数据
docker exec es-node1 curl -X DELETE "localhost:9200/sw_zipkin_span-*"

# 删除 management 数据（管理数据）
docker exec es-node1 curl -X DELETE "localhost:9200/sw_management"
```

---

## 方法三：删除特定日期的数据

如果只想删除特定日期的数据（例如只删除 2026-02-07 的数据）：

```bash
# 删除 2026-02-07 的所有数据
docker exec es-node1 curl -X DELETE "localhost:9200/sw_*-20260207"
```

---

## 完整操作步骤

### 步骤 1：查看现有索引

```bash
# 查看所有 SkyWalking 相关索引
docker exec es-node1 curl -s "localhost:9200/_cat/indices/sw_*?v"
```

### 步骤 2：确认要删除的索引

根据输出确认要删除的索引列表。

### 步骤 3：执行删除

```bash
# 删除所有 sw_ 开头的索引
docker exec es-node1 curl -X DELETE "localhost:9200/sw_*"
```

### 步骤 4：验证删除结果

```bash
# 再次查看，应该没有结果
docker exec es-node1 curl -s "localhost:9200/_cat/indices/sw_*?v"

# 或者查看所有索引，确认 sw_ 开头的索引已删除
docker exec es-node1 curl -s "localhost:9200/_cat/indices?v" | Select-String -Pattern "sw_"
```

---

## SkyWalking 索引说明

| 索引名称 | 说明 | 数据内容 |
|---------|------|---------|
| `sw_segment-*` | 链路追踪段数据 | 分布式追踪的 segment 数据 |
| `sw_log-*` | 日志数据 | 应用日志 |
| `sw_metrics-*` | 指标数据 | 性能指标、JVM 指标等 |
| `sw_records-*` | 记录数据 | 各种记录数据 |
| `sw_browser_error_log-*` | 浏览器错误日志 | 前端错误日志 |
| `sw_zipkin_span-*` | Zipkin 格式的 span 数据 | Zipkin 兼容数据 |
| `sw_management` | 管理数据 | 服务管理、配置等数据 |

---

## 注意事项

### ⚠️ 删除前确认

1. **数据备份**：删除操作不可逆，请确保不需要这些数据
2. **服务状态**：建议在删除前停止 SkyWalking OAP Server，避免删除过程中产生新数据
3. **磁盘空间**：删除索引会释放磁盘空间，可以通过 `_cat/indices?v` 查看索引大小

### 停止 SkyWalking OAP Server（可选）

```bash
# 停止 OAP Server，避免删除过程中产生新数据
cd docker
docker-compose stop skywalking-oap
```

### 删除后重启（如果需要）

```bash
# 删除完成后，重启 OAP Server
cd docker
docker-compose start skywalking-oap
```

---

## 快速删除脚本

创建 PowerShell 脚本 `delete-skywalking-data.ps1`：

```powershell
# delete-skywalking-data.ps1

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   删除 SkyWalking 数据" -ForegroundColor Cyan
Write-Host "========================================`n" -ForegroundColor Cyan

# 1. 查看现有索引
Write-Host "[1/3] 查看现有 SkyWalking 索引..." -ForegroundColor Yellow
docker exec es-node1 curl -s "localhost:9200/_cat/indices/sw_*?v" | Select-String -Pattern "sw_"

# 2. 确认删除
Write-Host "`n[2/3] 准备删除所有 sw_ 开头的索引..." -ForegroundColor Yellow
$confirm = Read-Host "确认删除？(y/N)"
if ($confirm -ne "y" -and $confirm -ne "Y") {
    Write-Host "已取消删除操作" -ForegroundColor Yellow
    exit
}

# 3. 执行删除
Write-Host "`n[3/3] 正在删除..." -ForegroundColor Yellow
$result = docker exec es-node1 curl -X DELETE "localhost:9200/sw_*" 2>&1
Write-Host $result

# 4. 验证
Write-Host "`n验证删除结果..." -ForegroundColor Yellow
$remaining = docker exec es-node1 curl -s "localhost:9200/_cat/indices/sw_*?v" 2>&1
if ($remaining -match "sw_") {
    Write-Host "⚠ 仍有部分索引未删除" -ForegroundColor Yellow
    Write-Host $remaining
} else {
    Write-Host "✓ 所有 SkyWalking 索引已删除" -ForegroundColor Green
}

Write-Host "`n完成！" -ForegroundColor Green
```

使用方法：

```powershell
.\delete-skywalking-data.ps1
```

---

## 常见问题

### Q1: 删除后 SkyWalking UI 还能访问吗？

A: 可以访问，但不会显示任何数据。OAP Server 会重新创建索引并开始收集新数据。

### Q2: 删除索引会影响正在运行的服务吗？

A: 不会影响正在运行的应用服务，但 SkyWalking UI 中会看不到历史数据。建议先停止 OAP Server 再删除。

### Q3: 如何只删除旧数据，保留最近的数据？

A: 可以使用日期模式删除，例如：
```bash
# 只删除 2026-02-07 之前的数据
docker exec es-node1 curl -X DELETE "localhost:9200/sw_*-20260206"
```

### Q4: 删除后如何清理磁盘空间？

A: Elasticsearch 会自动释放空间，但可能需要一些时间。可以执行以下操作加速：
```bash
# 强制合并段（可选）
docker exec es-node1 curl -X POST "localhost:9200/_forcemerge?only_expunge_deletes=true"
```

---

## 参考命令总结

```bash
# 查看所有 SkyWalking 索引
docker exec es-node1 curl -s "localhost:9200/_cat/indices/sw_*?v"

# 删除所有 SkyWalking 索引
docker exec es-node1 curl -X DELETE "localhost:9200/sw_*"

# 验证删除结果
docker exec es-node1 curl -s "localhost:9200/_cat/indices/sw_*?v"

# 查看集群健康状态
docker exec es-node1 curl -s "localhost:9200/_cluster/health?pretty"
```

