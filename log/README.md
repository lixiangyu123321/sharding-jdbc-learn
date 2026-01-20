# 日志中心代码示例

本目录包含日志中心相关的代码示例和配置。

## 目录结构

```
log/
├── README.md                    # 本文件
├── config/                      # 日志配置
│   └── logback-spring.xml      # Logback 配置（已复制到 src/main/resources/）
├── example/                     # 代码示例（已移动到 src/main/java/org/lix/mycatdemo/log/）
│   ├── LogProducer.java        # 日志生产者示例
│   └── LogConfig.java           # 日志配置类
└── scripts/                     # 脚本文件
    └── generate-test-logs.sh   # 生成测试日志脚本
```

## 已集成到主应用

以下组件已集成到主应用中：

### 1. Logback 配置
- 位置：`src/main/resources/logback-spring.xml`
- 功能：支持控制台、文件、JSON 格式输出
- 日志路径：`/var/log/app/`（可通过环境变量 `LOG_HOME` 配置）

### 2. 日志服务
- `LogService`：提供统一的日志记录方法
- `LogInterceptor`：请求拦截器，自动记录请求日志和追踪ID
- `LogStatistics`：日志统计服务

### 3. 日志生产者
- `LogProducer`：可选的日志生成器（通过 `log.producer.enabled=true` 启用）

### 4. 全局异常处理
- `GlobalExceptionHandler`：增强的异常处理器，记录详细的异常信息

## 使用说明

### 1. 配置日志路径

在 `application.yaml` 中配置：

```yaml
logging:
  file:
    path: /var/log/app  # 或通过环境变量 LOG_HOME 设置
```

### 2. 启用日志生产者（可选）

```yaml
log:
  producer:
    enabled: true
```

### 3. 使用日志服务

```java
@Autowired
private LogService logService;

// 记录业务操作
logService.logBusinessOperation("创建订单", "user001", orderId);

// 记录性能日志
logService.logPerformance("/api/orders", 150, true);

// 记录审计日志
logService.logAudit("DELETE", "/api/orders/123", "admin", "SUCCESS");
```

### 4. 查看日志

- **控制台**：实时查看应用日志
- **文件**：`/var/log/app/application.log`
- **JSON格式**：`/var/log/app/application-json.log`（用于 Logstash 解析）
- **错误日志**：`/var/log/app/application-error.log`
- **Kibana**：访问 `http://localhost:5601` 查看可视化日志

## 日志格式

### 标准格式
```
2026-01-20 10:30:45.123 [http-nio-8080-exec-1] INFO  o.l.m.controller.OrderController - 创建订单成功
```

### JSON格式（用于 Logstash）
```json
{
  "timestamp": "2026-01-20T10:30:45.123+08:00",
  "level": "INFO",
  "logger": "org.lix.mycatdemo.controller.OrderController",
  "message": "创建订单成功",
  "thread": "http-nio-8080-exec-1",
  "traceId": "550e8400-e29b-41d4-a716-446655440000"
}
```

## 注意事项

1. **日志路径**：确保应用有权限写入 `/var/log/app/` 目录
2. **日志轮转**：日志文件按天轮转，保留30天
3. **性能影响**：JSON 格式输出会增加少量性能开销
4. **追踪ID**：每个请求自动生成追踪ID，便于日志关联分析
