# MyCat Demo - 多模块项目结构说明

## 项目结构

```
mycat-demo/
├── pom.xml                          # 父 POM（聚合所有子模块）
├── provider/                        # 服务提供方模块
│   ├── pom.xml                      # Provider 父 POM
│   ├── facade/                      # Facade 模块（接口定义）
│   │   ├── pom.xml
│   │   └── src/main/java/
│   │       └── org/lix/provider/facade/
│   │           ├── UserService.java # Dubbo 服务接口
│   │           └── UserDTO.java     # 数据传输对象
│   └── service/                     # Service 模块（服务实现）
│       ├── pom.xml
│       └── src/main/java/
│           └── org/lix/provider/
│               ├── ProviderApplication.java    # 启动类
│               └── service/
│                   └── UserServiceImpl.java    # 服务实现
└── consumer/                        # 服务消费方模块
    ├── pom.xml
    └── src/main/java/
        └── org/lix/consumer/
            ├── ConsumerApplication.java        # 启动类
            └── controller/
                └── UserController.java        # 控制器（调用远程服务）
```

## 模块说明

### 1. 父模块 (mycat-demo)
- **作用**: 统一管理所有子模块的依赖版本
- **打包方式**: `pom`
- **包含模块**: `provider`, `consumer`

### 2. Provider 模块
- **作用**: Dubbo 服务提供方
- **子模块**:
  - **facade**: 定义服务接口和 DTO，供 provider 和 consumer 共同使用
  - **service**: 实现服务接口，暴露 Dubbo 服务

### 3. Consumer 模块
- **作用**: Dubbo 服务消费方
- **依赖**: 依赖 `provider-facade` 模块获取接口定义

## 依赖关系

```
consumer
  └── provider-facade (接口定义)

provider-service
  └── provider-facade (接口定义)
```

## 启动顺序

1. **启动 Nacos** (如果使用 Nacos 作为注册中心)
   ```bash
   # 确保 Nacos 运行在 localhost:8848
   ```

2. **启动 Provider**
   ```bash
   cd provider/service
   mvn spring-boot:run
   # 或运行 ProviderApplication
   ```
   - 服务端口: 8081
   - Dubbo 协议端口: 20880

3. **启动 Consumer**
   ```bash
   cd consumer
   mvn spring-boot:run
   # 或运行 ConsumerApplication
   ```
   - 服务端口: 8082

## 测试

启动 Consumer 后，可以通过以下接口测试：

- 获取用户名: `GET http://localhost:8082/api/user/name/1`
- 获取用户信息: `GET http://localhost:8082/api/user/info/1`

## 配置说明

### Provider 配置 (provider/service/src/main/resources/application.yaml)
- Dubbo 注册中心: `nacos://localhost:8848`
- Dubbo 协议端口: `20880`
- 服务端口: `8081`

### Consumer 配置 (consumer/src/main/resources/application.yaml)
- Dubbo 注册中心: `nacos://localhost:8848`
- 服务端口: `8082`

## 开发建议

1. **接口定义**: 所有 Dubbo 服务接口和 DTO 都定义在 `provider/facade` 模块中
2. **服务实现**: 服务实现类放在 `provider/service` 模块中，使用 `@DubboService` 注解
3. **服务调用**: Consumer 中使用 `@DubboReference` 注解注入远程服务
4. **版本管理**: 统一在父 POM 中管理依赖版本

