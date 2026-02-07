# SkyWalking Agent 排查指南

## 问题现象

已配置 SkyWalking Agent 的 JVM 参数，Agent JAR 文件也已下载到相应位置，但在 SkyWalking UI 上看不到服务。

---

## 排查步骤

### 步骤 1：验证 Agent JAR 文件是否存在

**检查 Agent JAR 文件路径是否正确**：

```bash
# Windows PowerShell
Test-Path "D:\tools\skywalking-agent\skywalking-agent-provider\skywalking-agent.jar"

# 或者直接查看文件
dir "D:\tools\skywalking-agent\skywalking-agent-provider\skywalking-agent.jar"
```

**常见问题**：
- ❌ 路径中包含空格或特殊字符
- ❌ 路径使用了反斜杠 `\` 而不是正斜杠 `/`（在某些情况下）
- ❌ 文件路径不存在或文件名错误

**解决方案**：
- ✅ 使用绝对路径，避免相对路径
- ✅ 路径中包含空格时，使用引号：`"D:\tools\skywalking agent\skywalking-agent.jar"`
- ✅ 确认文件名是 `skywalking-agent.jar`（不是 `skywalking-agent-9.7.0.jar`）

---

### 步骤 2：检查 JVM 启动参数格式

**正确的 JVM 参数格式**：

```bash
-javaagent:D:\tools\skywalking-agent\skywalking-agent-provider\skywalking-agent.jar
```

**常见错误**：

1. **路径错误**：
   ```bash
   # ❌ 错误：路径不存在
   -javaagent:D:\skywalking\agent.jar
   
   # ✅ 正确：使用完整路径
   -javaagent:D:\tools\skywalking\skywalking-agent\skywalking-agent.jar
   ```

2. **参数格式错误**：
   ```bash
   # ❌ 错误：等号前后有空格
   -javaagent = D:\tools\skywalking\skywalking-agent\skywalking-agent.jar
   
   # ✅ 正确：等号前后无空格
   -javaagent:D:\tools\skywalking\skywalking-agent\skywalking-agent.jar
   ```

3. **多个参数未正确分隔**：
   ```bash
   # ❌ 错误：参数连在一起
   -javaagent:xxx.jar-Dskywalking.agent.service_name=xxx
   
   # ✅ 正确：每个参数独立一行或使用空格分隔
   -javaagent:D:\tools\skywalking\skywalking-agent\skywalking-agent.jar
   -Dskywalking.agent.service_name=dubbo-provider
   ```

---

### 步骤 3：验证 Agent 是否真正加载

**方法 1：查看应用启动日志**

启动应用时，应该能看到类似以下日志：

```
INFO  org.apache.skywalking.apm.agent.SkyWalkingAgent - SkyWalking agent started, agent version: 9.7.0
```

**如果没有看到这个日志，说明 Agent 没有加载成功。**

**方法 2：检查 JVM 系统属性**

在应用代码中添加以下代码，检查 Agent 是否加载：

```java
import java.lang.management.ManagementFactory;

public class SkyWalkingCheck {
    public static void main(String[] args) {
        // 检查是否加载了 SkyWalking Agent
        String agentPath = System.getProperty("skywalking.agent.service_name");
        System.out.println("SkyWalking Agent Service Name: " + agentPath);
        
        // 检查所有 JVM 参数
        ManagementFactory.getRuntimeMXBean().getInputArguments().forEach(System.out::println);
    }
}
```

**方法 3：查看 Agent 日志文件**

Agent 会在以下位置生成日志文件（默认在项目根目录）：

```
skywalking-api.log
```

检查日志文件中是否有错误信息。

---

### 步骤 4：检查 OAP Server 连接配置

**验证 OAP Server 是否运行**：

```bash
# 检查容器状态
docker ps | grep skywalking-oap

# 检查 OAP Server 健康状态
curl http://localhost:12800/v3/health

# 预期输出
{"details":{"AgentHealthChecker":{"status":"UP"}},"status":"UP"}
```

**验证端口映射**：

```bash
# 检查端口是否映射正确
docker port skywalking-oap

# 应该看到：
# 11800/tcp -> 0.0.0.0:11800
# 12800/tcp -> 0.0.0.0:12800
```

**测试网络连接**：

```bash
# Windows PowerShell - 测试 gRPC 端口（11800）
Test-NetConnection -ComputerName 127.0.0.1 -Port 11800

# 或者使用 telnet
telnet 127.0.0.1 11800
```

**常见问题**：

1. **OAP Server 未启动**：
   ```bash
   # 启动 OAP Server
   cd docker
   docker-compose up -d skywalking-oap
   ```

2. **端口映射错误**：
   ```yaml
   # 检查 docker-compose.yml
   ports:
     - "11800:11800"  # 确保端口映射正确
   ```

3. **防火墙阻止连接**：
   - Windows 防火墙可能阻止了本地连接
   - 检查防火墙设置

---

### 步骤 5：检查 Agent 配置文件

**检查 agent.config 文件**：

位置：`skywalking-agent/config/agent.config`

**关键配置项**：

```properties
# 服务名称（必须配置）
agent.service_name=${SW_AGENT_NAME:default-service}

# OAP Server 地址（必须配置）
collector.backend_service=${SW_AGENT_COLLECTOR_BACKEND_SERVICES:127.0.0.1:11800}

# 日志级别（建议设置为 DEBUG 以便排查）
logging.level=${SW_LOGGING_LEVEL:DEBUG}
```

**配置优先级**：
1. JVM 系统属性（`-D` 参数） - **最高优先级**
2. 环境变量（`SW_AGENT_NAME` 等）
3. agent.config 文件中的默认值

**验证配置是否生效**：

在应用启动时添加以下代码：

```java
System.out.println("SkyWalking Service Name: " + 
    System.getProperty("skywalking.agent.service_name"));
System.out.println("SkyWalking Backend Service: " + 
    System.getProperty("skywalking.collector.backend_service"));
```

---

### 步骤 6：检查服务名称配置

**确保服务名称正确配置**：

```bash
# Provider 服务
-Dskywalking.agent.service_name=dubbo-provider

# Consumer 服务
-Dskywalking.agent.service_name=dubbo-consumer
```

**常见问题**：

1. **服务名称未配置**：
   - Agent 会使用默认名称 `Your_ApplicationName`
   - 在 UI 中可能显示为其他名称

2. **服务名称配置错误**：
   ```bash
   # ❌ 错误：参数名错误
   -Dagent.service_name=dubbo-provider
   
   # ✅ 正确：使用完整参数名
   -Dskywalking.agent.service_name=dubbo-provider
   ```

---

### 步骤 7：检查 Agent 日志

**查看 Agent 日志文件**：

默认位置：项目根目录下的 `skywalking-api.log`

**关键日志信息**：

1. **Agent 启动成功**：
   ```
   INFO  org.apache.skywalking.apm.agent.SkyWalkingAgent - SkyWalking agent started, agent version: 9.7.0
   ```

2. **连接到 OAP Server**：
   ```
   INFO  org.apache.skywalking.apm.dependencies.netty... - Connected to server
   ```

3. **连接失败**：
   ```
   ERROR org.apache.skywalking.apm.agent... - Failed to connect to server
   ```

**设置更详细的日志级别**：

在启动参数中添加：

```bash
-Dskywalking.logging.level=DEBUG
```

或者在 `agent.config` 中设置：

```properties
logging.level=DEBUG
```

---

### 步骤 8：验证版本匹配

**确保 Agent 版本与 OAP Server 版本一致**：

```bash
# 检查 OAP Server 版本
docker exec skywalking-oap cat /skywalking/config/application.yml | grep version

# 检查 Agent 版本
# 查看 skywalking-agent.jar 的 MANIFEST.MF 文件
```

**版本不匹配可能导致的问题**：
- Agent 无法连接到 OAP Server
- 数据格式不兼容
- UI 中看不到服务

**解决方案**：
- 确保 Agent 和 OAP Server 使用相同版本（当前为 9.7.0）

---

### 步骤 9：检查 IDE 配置（如果使用 IDE 启动）

**IntelliJ IDEA 配置**：

1. **Run Configuration → VM options**：
   ```
   -javaagent:D:\tools\skywalking\skywalking-agent\skywalking-agent.jar
   -Dskywalking.agent.service_name=dubbo-provider
   -Dskywalking.collector.backend_service=127.0.0.1:11800
   ```

2. **检查 Environment variables**：
   - 确保没有覆盖 JVM 参数的环境变量

3. **检查 Working directory**：
   - 确保工作目录正确

**Eclipse 配置**：

1. **Run Configuration → Arguments → VM arguments**：
   ```
   -javaagent:D:\tools\skywalking\skywalking-agent\skywalking-agent.jar
   -Dskywalking.agent.service_name=dubbo-provider
   -Dskywalking.collector.backend_service=127.0.0.1:11800
   ```

---

### 步骤 10：完整启动参数示例

**Provider 服务完整启动参数**：

```bash
-javaagent:D:\tools\skywalking\skywalking-agent\skywalking-agent.jar
-Dskywalking.agent.service_name=dubbo-provider
-Dskywalking.collector.backend_service=127.0.0.1:11800
-Dskywalking.logging.level=DEBUG
-Dskywalking.agent.sample_n_per_3_secs=10000
```

**Consumer 服务完整启动参数**：

```bash
-javaagent:D:\tools\skywalking\skywalking-agent\skywalking-agent.jar
-Dskywalking.agent.service_name=dubbo-consumer
-Dskywalking.collector.backend_service=127.0.0.1:11800
-Dskywalking.logging.level=DEBUG
-Dskywalking.agent.sample_n_per_3_secs=10000
```

---

## 快速诊断脚本

创建以下 PowerShell 脚本进行快速诊断：

```powershell
# check-skywalking.ps1

Write-Host "=== SkyWalking Agent 诊断脚本 ===" -ForegroundColor Green

# 1. 检查 Agent JAR 文件
$agentPath = "D:\tools\skywalking\skywalking-agent\skywalking-agent.jar"
Write-Host "`n1. 检查 Agent JAR 文件..." -ForegroundColor Yellow
if (Test-Path $agentPath) {
    Write-Host "   ✓ Agent JAR 文件存在: $agentPath" -ForegroundColor Green
    $fileInfo = Get-Item $agentPath
    Write-Host "   文件大小: $($fileInfo.Length) bytes" -ForegroundColor Cyan
} else {
    Write-Host "   ✗ Agent JAR 文件不存在: $agentPath" -ForegroundColor Red
}

# 2. 检查 OAP Server
Write-Host "`n2. 检查 OAP Server..." -ForegroundColor Yellow
try {
    $response = Invoke-WebRequest -Uri "http://localhost:12800/v3/health" -TimeoutSec 5
    Write-Host "   ✓ OAP Server 运行正常" -ForegroundColor Green
    Write-Host "   响应: $($response.Content)" -ForegroundColor Cyan
} catch {
    Write-Host "   ✗ OAP Server 无法连接: $_" -ForegroundColor Red
}

# 3. 检查端口
Write-Host "`n3. 检查端口..." -ForegroundColor Yellow
$port11800 = Test-NetConnection -ComputerName 127.0.0.1 -Port 11800 -WarningAction SilentlyContinue
if ($port11800.TcpTestSucceeded) {
    Write-Host "   ✓ 端口 11800 (gRPC) 可访问" -ForegroundColor Green
} else {
    Write-Host "   ✗ 端口 11800 (gRPC) 无法访问" -ForegroundColor Red
}

# 4. 检查 Agent 日志
Write-Host "`n4. 检查 Agent 日志..." -ForegroundColor Yellow
$logPath = "skywalking-api.log"
if (Test-Path $logPath) {
    Write-Host "   ✓ 找到日志文件: $logPath" -ForegroundColor Green
    $lastLines = Get-Content $logPath -Tail 10
    Write-Host "   最后 10 行日志:" -ForegroundColor Cyan
    $lastLines | ForEach-Object { Write-Host "   $_" }
} else {
    Write-Host "   ⚠ 未找到日志文件（可能 Agent 未启动）" -ForegroundColor Yellow
}

Write-Host "`n=== 诊断完成 ===" -ForegroundColor Green
```

运行脚本：

```powershell
.\check-skywalking.ps1
```

---

## 常见问题及解决方案

### 问题 1：Agent 日志显示连接失败

**错误信息**：
```
ERROR - Failed to connect to server
```

**解决方案**：
1. 检查 OAP Server 是否运行：`docker ps | grep skywalking-oap`
2. 检查端口映射：`docker port skywalking-oap`
3. 检查防火墙设置
4. 尝试使用 `localhost` 而不是 `127.0.0.1`

### 问题 2：UI 中服务名称显示为 "Your_ApplicationName"

**原因**：服务名称未正确配置

**解决方案**：
```bash
# 确保启动参数中包含服务名称
-Dskywalking.agent.service_name=dubbo-provider
```

### 问题 3：Agent 启动但无数据上报

**可能原因**：
1. 采样率设置过低
2. 没有触发实际的业务调用
3. 插件未启用

**解决方案**：
```bash
# 设置 100% 采样
-Dskywalking.agent.sample_n_per_3_secs=10000

# 触发一次业务调用
# 然后等待几秒钟，数据会上报到 OAP Server
```

### 问题 4：Windows 路径问题

**问题**：Windows 路径中的反斜杠可能导致问题

**解决方案**：
```bash
# 使用正斜杠（在某些情况下）
-javaagent:D:/tools/skywalking/skywalking-agent/skywalking-agent.jar

# 或者使用引号包裹路径
-javaagent:"D:\tools\skywalking\skywalking-agent\skywalking-agent.jar"
```

---

## 验证清单

配置完成后，按以下清单逐一检查：

- [ ] Agent JAR 文件存在且路径正确
- [ ] JVM 启动参数格式正确（`-javaagent:路径`）
- [ ] 服务名称已配置（`-Dskywalking.agent.service_name=xxx`）
- [ ] OAP Server 地址已配置（`-Dskywalking.collector.backend_service=127.0.0.1:11800`）
- [ ] OAP Server 容器运行正常
- [ ] 端口 11800 和 12800 可访问
- [ ] Agent 日志文件已生成（`skywalking-api.log`）
- [ ] 应用启动日志中看到 "SkyWalking agent started"
- [ ] 触发业务调用后，等待 10-30 秒
- [ ] 在 SkyWalking UI 中刷新页面查看服务

---

## 调试技巧

### 1. 启用详细日志

```bash
-Dskywalking.logging.level=DEBUG
```

### 2. 检查 Agent 是否加载

在应用启动类中添加：

```java
public static void main(String[] args) {
    // 检查 Agent 是否加载
    String agentArgs = System.getProperty("javaagent");
    System.out.println("Java Agent: " + agentArgs);
    
    // 检查所有 SkyWalking 相关系统属性
    System.getProperties().stringPropertyNames().stream()
        .filter(name -> name.contains("skywalking"))
        .forEach(name -> System.out.println(name + " = " + System.getProperty(name)));
    
    SpringApplication.run(Application.class, args);
}
```

### 3. 手动测试连接

```bash
# 测试 OAP Server gRPC 端口
telnet 127.0.0.1 11800

# 测试 OAP Server HTTP 端口
curl http://localhost:12800/v3/health
```

---

## 参考资源

- [SkyWalking Agent 配置文档](https://skywalking.apache.org/docs/main/latest/en/setup/service-agent/java-agent/readme/)
- [SkyWalking 故障排查](https://skywalking.apache.org/docs/main/latest/en/FAQ/README/)

---

## 问题记录

如果以上步骤都无法解决问题，请收集以下信息：

1. Agent 日志文件（`skywalking-api.log`）
2. 应用启动日志
3. OAP Server 容器日志：`docker logs skywalking-oap`
4. 完整的 JVM 启动参数
5. Agent 版本和 OAP Server 版本

