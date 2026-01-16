你关心的核心问题是：激活dev环境加载`application-dev.yml`时，这份配置是否会“替代”主配置文件的内容，答案是**不会直接替代，而是“叠加 + 覆盖”** —— 具体来说，是主配置（`application.yml`）和环境配置（`application-dev.yml`）合并，相同配置项以环境配置为准，不同配置项则互补。

### 一、核心规则：Spring Boot配置加载的优先级与合并逻辑
Spring Boot加载多环境配置时，遵循“**先加载主配置，再加载环境配置，环境配置覆盖主配置中相同的项**”的原则，具体流程：
1. 先加载根目录的 `application.yml`（主配置）；
2. 再加载激活的环境配置（如 `application-dev.yml`）；
3. 合并规则：
    - 若 `application-dev.yml` 中有和主配置**相同的配置项** → 环境配置的值会**覆盖**主配置的值；
    - 若 `application-dev.yml` 中有主配置**没有的配置项** → 该配置项会**追加**到整体配置中；
    - 若主配置中有 `application-dev.yml` 没有的配置项 → 保留主配置的该值。

### 二、举个新手易懂的例子
#### 1. 主配置 `application.yml`：
```yaml
spring:
  application:
    name: sharding-jdbc-demo  # 主配置的应用名
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
  datasource:
    url: jdbc:mysql://localhost:3306/default_db  # 主配置的数据库地址
    username: root  # 主配置的数据库用户名
```

#### 2. 环境配置 `application-dev.yml`：
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/dev_db  # 环境配置的数据库地址（和主配置同名）
    password: 123456  # 环境配置新增的密码项
  logging:
    level:
      root: debug  # 环境配置新增的日志级别
```

#### 3. 最终生效的配置（合并后）：
```yaml
spring:
  application:
    name: sharding-jdbc-demo  # 保留主配置（环境配置未覆盖）
  profiles:
    active: dev  # 激活dev环境
  datasource:
    url: jdbc:mysql://localhost:3306/dev_db  # 环境配置覆盖主配置
    username: root  # 保留主配置（环境配置未提及）
    password: 123456  # 环境配置追加
  logging:
    level:
      root: debug  # 环境配置追加
```

### 三、关键补充：为什么不是“替代”
- “替代”意味着主配置完全失效，但Spring Boot的设计是**主配置作为基础，环境配置作为环境专属的补充/覆盖**；
- 这样做的好处：通用配置（如应用名、核心框架配置）放在主配置，无需在每个环境配置中重复写；环境专属配置（如数据库地址、日志级别）放在对应环境配置中，便于管理。

### 四、特殊情况：想让环境配置完全替代主配置？
如果确实需要让环境配置“完全替代”主配置（极少场景），可以在环境配置中显式重置配置：
```yaml
# application-dev.yml 中强制清空主配置的某个节点
spring:
  datasource: ~  # 波浪线表示清空该节点，主配置的datasource会完全失效
  # 然后重新定义datasource
  datasource:
    url: jdbc:mysql://localhost:3306/dev_db
    username: dev_user
    password: dev_pwd
```

### 总结
1. 激活dev环境后，`application-dev.yml` **不会替代** `application.yml`，而是和主配置**合并**；
2. 相同配置项：环境配置覆盖主配置；不同配置项：环境配置追加、主配置保留；
3. 核心目的：实现“通用配置复用 + 环境配置隔离”，减少配置冗余，降低维护成本。