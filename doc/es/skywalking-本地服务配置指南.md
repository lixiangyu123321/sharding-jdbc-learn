# SkyWalking 本地服务配置指南

## 场景说明

- **SkyWalking OAP Server**：运行在 Docker 容器中
- **Provider 和 Consumer 服务**：本地启动的 Spring Boot 应用
- **目标**：让本地服务通过 SkyWalking Agent 连接到 Docker 中的 SkyWalking OAP Server

---

## 一、SkyWalking Agent 下载与配置

### 1.1 下载 SkyWalking Agent

访问 [Apache SkyWalking 官方下载页面](https://skywalking.apache.org/downloads/)，下载对应版本的 SkyWalking Agent。

**推荐方式**：下载 SkyWalking 完整包（包含 Agent）

```bash
# 创建目录
mkdir -p D:\tools\skywalking

# 下载 SkyWalking 9.7.0（与 Docker 版本一致）
# 访问：https://archive.apache.org/dist/skywalking/9.7.0/
# 下载：apache-skywalking-java-agent-9.7.0.tgz
```

或者使用 Maven 依赖方式（推荐用于项目集成）：

```xml
<!-- 在父 pom.xml 中添加 -->
<properties>
    <skywalking.version>9.7.0</skywalking.version>
</properties>
```

### 1.2 解压 Agent

```bash
# Windows PowerShell
cd D:\tools\skywalking
tar -xzf apache-skywalking-java-agent-9.7.0.tgz

# 解压后的目录结构
# skywalking-agent/
#   ├── config/
#   │   └── agent.config          # Agent 配置文件
#   ├── plugins/                  # 插件目录
#   ├── optional-plugins/         # 可选插件
#   └── skywalking-agent.jar      # Agent JAR 文件
```

---

## 二、配置 SkyWalking Agent

### 2.1 修改 agent.config 配置文件

编辑 `skywalking-agent/config/agent.config` 文件，配置以下关键参数：

```properties
# ============================================
# 核心配置：Agent 命名空间（可选，用于隔离）
# ============================================
agent.namespace=

# ============================================
# 服务名称配置
# ============================================
# Provider 服务名称
agent.service_name=${SW_AGENT_NAME:dubbo-provider}

# Consumer 服务名称（需要在启动时通过环境变量覆盖）
# agent.service_name=${SW_AGENT_NAME:dubbo-consumer}

# ============================================
# 后端服务地址配置（重要！）
# ============================================
# 本地服务连接到 Docker 容器的 SkyWalking OAP Server
# Docker 容器端口映射：11800:11800 (gRPC), 12800:12800 (HTTP)
collector.backend_service=${SW_AGENT_COLLECTOR_BACKEND_SERVICES:127.0.0.1:11800}

# ============================================
# 采样配置
# ============================================
# 采样率：10000 表示 100% 采样（生产环境建议降低）
agent.sample_n_per_3_secs=${SW_AGENT_SAMPLE:10000}

# ============================================
# 日志配置
# ============================================
# 日志级别：TRACE, DEBUG, INFO, WARN, ERROR
logging.level=${SW_LOGGING_LEVEL:INFO}
# 日志文件路径
logging.file_name=${SW_LOGGING_FILE_NAME:skywalking-api.log}

# ============================================
# 插件配置
# ============================================
# 启用 Dubbo 插件（如果使用 Dubbo）
plugin.dubbo.enabled=${SW_PLUGIN_DUBBO_ENABLED:true}
# 启用 Spring 插件
plugin.spring.enabled=${SW_PLUGIN_SPRING_ENABLED:true}
# 启用 MySQL 插件
plugin.mysql.enabled=${SW_PLUGIN_MYSQL_ENABLED:true}
```

### 2.2 关键配置说明

| 配置项 | 说明 | 示例值 |
|--------|------|--------|
| `agent.service_name` | 服务名称，在 SkyWalking UI 中显示 | `dubbo-provider` 或 `dubbo-consumer` |
| `collector.backend_service` | OAP Server 地址，本地连接 Docker 容器 | `127.0.0.1:11800` |
| `agent.sample_n_per_3_secs` | 采样率，每 3 秒采样数量 | `10000`（100% 采样） |

---

## 三、配置本地服务启动参数

### 3.1 Provider 服务配置

在 IDE 中配置 Provider 服务的启动参数（以 IntelliJ IDEA 为例）：

**Run Configuration → VM options**：

```bash
-javaagent:D:\tools\skywalking\skywalking-agent\skywalking-agent.jar
-Dskywalking.agent.service_name=dubbo-provider
-Dskywalking.collector.backend_service=127.0.0.1:11800
-Dskywalking.logging.level=INFO
```

**或者使用环境变量方式**：

```bash
-javaagent:D:\tools\skywalking\skywalking-agent\skywalking-agent.jar
-DSW_AGENT_NAME=dubbo-provider
-DSW_AGENT_COLLECTOR_BACKEND_SERVICES=127.0.0.1:11800
```

### 3.2 Consumer 服务配置

**Run Configuration → VM options**：

```bash
-javaagent:D:\tools\skywalking\skywalking-agent\skywalking-agent.jar
-Dskywalking.agent.service_name=dubbo-consumer
-Dskywalking.collector.backend_service=127.0.0.1:11800
-Dskywalking.logging.level=INFO
```

### 3.3 使用启动脚本（推荐）

创建启动脚本，方便管理：

#### Windows 脚本：`start-provider.bat`

```batch
@echo off
setlocal

set SKYWALKING_AGENT_PATH=D:\tools\skywalking\skywalking-agent\skywalking-agent.jar
set JAVA_OPTS=-javaagent:%SKYWALKING_AGENT_PATH% ^
  -Dskywalking.agent.service_name=dubbo-provider ^
  -Dskywalking.collector.backend_service=127.0.0.1:11800 ^
  -Dskywalking.logging.level=INFO

echo Starting Provider with SkyWalking Agent...
java %JAVA_OPTS% -jar provider\web\target\provider-web-0.0.1-SNAPSHOT.jar

pause
```

#### Windows 脚本：`start-consumer.bat`

```batch
@echo off
setlocal

set SKYWALKING_AGENT_PATH=D:\tools\skywalking\skywalking-agent\skywalking-agent.jar
set JAVA_OPTS=-javaagent:%SKYWALKING_AGENT_PATH% ^
  -Dskywalking.agent.service_name=dubbo-consumer ^
  -Dskywalking.collector.backend_service=127.0.0.1:11800 ^
  -Dskywalking.logging.level=INFO

echo Starting Consumer with SkyWalking Agent...
java %JAVA_OPTS% -jar consumer\web\target\consumer-web-0.0.1-SNAPSHOT.jar

pause
```

---

## 四、Maven 集成方式（可选）

### 4.1 添加 Maven 插件

在 `provider/web/pom.xml` 和 `consumer/web/pom.xml` 中添加 Maven 插件：

```xml
<build>
    <plugins>
        <!-- Spring Boot Maven Plugin -->
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
            <configuration>
                <jvmArguments>
                    -javaagent:D:\tools\skywalking\skywalking-agent\skywalking-agent.jar
                    -Dskywalking.agent.service_name=${project.artifactId}
                    -Dskywalking.collector.backend_service=127.0.0.1:11800
                </jvmArguments>
            </configuration>
        </plugin>
    </plugins>
</build>
```

### 4.2 使用 Maven Profile

在 `pom.xml` 中配置 Profile，方便切换：

```xml
<profiles>
    <profile>
        <id>skywalking</id>
        <properties>
            <skywalking.agent.path>D:\tools\skywalking\skywalking-agent\skywalking-agent.jar</skywalking.agent.path>
            <skywalking.agent.service_name>${project.artifactId}</skywalking.agent.service_name>
            <skywalking.collector.backend_service>127.0.0.1:11800</skywalking.collector.backend_service>
        </properties>
        <build>
            <plugins>
                <plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                    <configuration>
                        <jvmArguments>
                            -javaagent:${skywalking.agent.path}
                            -Dskywalking.agent.service_name=${skywalking.agent.service_name}
                            -Dskywalking.collector.backend_service=${skywalking.collector.backend_service}
                        </jvmArguments>
                    </configuration>
                </plugin>
            </plugins>
        </build>
    </profile>
</profiles>
```

使用方式：

```bash
# 启用 SkyWalking
mvn spring-boot:run -Pskywalking

# 不使用 SkyWalking
mvn spring-boot:run
```

---

## 五、验证配置

### 5.1 检查 SkyWalking OAP Server 是否运行

```bash
# 检查容器状态
docker ps | grep skywalking

# 检查 OAP Server 健康状态
curl http://localhost:12800/v3/health

# 预期输出
{"details":{"AgentHealthChecker":{"status":"UP"}},"status":"UP"}
```

### 5.2 启动本地服务

1. 启动 Provider 服务（端口 8081）
2. 启动 Consumer 服务（端口 8082）
3. 访问 Consumer 服务，触发一次调用

### 5.3 查看 SkyWalking UI

访问：http://localhost:18080

在 SkyWalking UI 中应该能看到：
- **服务列表**：`dubbo-provider` 和 `dubbo-consumer`
- **拓扑图**：显示服务之间的调用关系
- **追踪数据**：显示请求的完整链路

---

## 六、常见问题排查

### 6.1 Agent 无法连接到 OAP Server

**问题**：本地服务启动后，SkyWalking UI 中看不到服务

**排查步骤**：

1. **检查端口映射**：
   ```bash
   # 确认 Docker 端口映射正确
   docker ps | grep skywalking-oap
   # 应该看到：0.0.0.0:11800->11800/tcp
   ```

2. **测试网络连接**：
   ```bash
   # 测试 gRPC 端口（可能需要使用 telnet 或 nc）
   telnet 127.0.0.1 11800
   ```

3. **检查 Agent 日志**：
   - 查看应用启动日志，确认 Agent 是否加载
   - 查看 `skywalking-api.log` 文件（在项目根目录或配置的日志路径）

4. **验证配置**：
   ```bash
   # 确认 agent.config 中的配置
   collector.backend_service=127.0.0.1:11800
   ```

### 6.2 服务名称显示不正确

**问题**：SkyWalking UI 中服务名称不是预期的

**解决方案**：

1. **通过 JVM 参数覆盖**：
   ```bash
   -Dskywalking.agent.service_name=dubbo-provider
   ```

2. **通过环境变量**：
   ```bash
   -DSW_AGENT_NAME=dubbo-provider
   ```

3. **检查优先级**：JVM 参数 > 环境变量 > agent.config 文件

### 6.3 追踪数据不完整

**问题**：部分请求没有追踪数据

**可能原因**：

1. **采样率设置过低**：
   ```properties
   # agent.config
   agent.sample_n_per_3_secs=10000  # 100% 采样
   ```

2. **插件未启用**：
   ```properties
   # 确保相关插件已启用
   plugin.dubbo.enabled=true
   plugin.spring.enabled=true
   ```

3. **跨线程追踪丢失**：
   - 使用异步调用时，需要配置跨线程追踪
   - 参考 SkyWalking 官方文档配置异步追踪

### 6.4 Docker 网络问题

**问题**：如果 Docker 容器使用自定义网络，本地服务可能无法连接

**解决方案**：

1. **使用 host 网络模式**（不推荐，仅用于开发环境）：
   ```yaml
   # docker-compose.yml
   skywalking-oap:
     network_mode: "host"
   ```

2. **使用 Docker 的 host.docker.internal**（Windows/Mac）：
   ```properties
   # agent.config
   collector.backend_service=host.docker.internal:11800
   ```

3. **使用宿主机 IP**：
   ```properties
   # 获取宿主机 IP（Windows）
   ipconfig
   # 使用实际 IP，例如：192.168.1.100:11800
   ```

---

## 七、配置示例总结

### 7.1 完整启动参数示例

**Provider 服务**：

```bash
-javaagent:D:\tools\skywalking\skywalking-agent\skywalking-agent.jar
-Dskywalking.agent.service_name=dubbo-provider
-Dskywalking.collector.backend_service=127.0.0.1:11800
-Dskywalking.logging.level=INFO
-Dskywalking.agent.sample_n_per_3_secs=10000
```

**Consumer 服务**：

```bash
-javaagent:D:\tools\skywalking\skywalking-agent\skywalking-agent.jar
-Dskywalking.agent.service_name=dubbo-consumer
-Dskywalking.collector.backend_service=127.0.0.1:11800
-Dskywalking.logging.level=INFO
-Dskywalking.agent.sample_n_per_3_secs=10000
```

### 7.2 agent.config 关键配置

```properties
# 服务名称（可通过 JVM 参数覆盖）
agent.service_name=${SW_AGENT_NAME:default-service}

# OAP Server 地址（本地连接 Docker 容器）
collector.backend_service=${SW_AGENT_COLLECTOR_BACKEND_SERVICES:127.0.0.1:11800}

# 采样率
agent.sample_n_per_3_secs=${SW_AGENT_SAMPLE:10000}

# 日志级别
logging.level=${SW_LOGGING_LEVEL:INFO}

# 启用插件
plugin.dubbo.enabled=${SW_PLUGIN_DUBBO_ENABLED:true}
plugin.spring.enabled=${SW_PLUGIN_SPRING_ENABLED:true}
plugin.mysql.enabled=${SW_PLUGIN_MYSQL_ENABLED:true}
```

---

## 八、最佳实践

### 8.1 开发环境

- ✅ 使用 100% 采样率，便于调试
- ✅ 使用本地文件日志，方便排查问题
- ✅ 使用 JVM 参数方式，灵活切换

### 8.2 生产环境

- ⚠️ 降低采样率（建议 10-30%），减少性能影响
- ⚠️ 使用远程日志收集
- ⚠️ 通过配置文件统一管理，避免硬编码
- ⚠️ 监控 Agent 性能影响

### 8.3 配置管理

- 使用环境变量或配置中心管理不同环境的配置
- 避免在代码中硬编码 Agent 路径
- 使用 Maven Profile 区分不同环境

---

## 九、参考资源

- [SkyWalking 官方文档](https://skywalking.apache.org/docs/)
- [SkyWalking Java Agent 配置](https://skywalking.apache.org/docs/main/latest/en/setup/service-agent/java-agent/readme/)
- [SkyWalking Agent 配置属性](https://skywalking.apache.org/docs/main/latest/en/setup/service-agent/java-agent/java-agent-configurations/)
- [SkyWalking 插件列表](https://skywalking.apache.org/docs/main/latest/en/setup/service-agent/java-agent/java-plugin-development-guide/)

---

## 十、快速检查清单

配置完成后，按以下清单检查：

- [ ] SkyWalking OAP Server 容器运行正常
- [ ] 端口 11800 和 12800 已正确映射
- [ ] Agent JAR 文件路径正确
- [ ] agent.config 配置正确
- [ ] 启动参数中包含 `-javaagent` 参数
- [ ] 服务名称配置正确（provider/consumer 不同）
- [ ] OAP Server 地址配置为 `127.0.0.1:11800`
- [ ] 启动服务后，SkyWalking UI 中能看到服务
- [ ] 触发调用后，能在 UI 中看到追踪数据

---

## 问题记录

- **配置时间**：2026-02-07
- **SkyWalking 版本**：9.7.0
- **Java 版本**：Java 8
- **Spring Boot 版本**：2.7.18
- **Dubbo 版本**：3.3.0

