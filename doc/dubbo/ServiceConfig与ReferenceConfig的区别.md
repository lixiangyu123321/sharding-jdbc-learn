`ServiceConfig`和`ReferenceConfig`是Dubbo中**描述RPC服务的核心配置类**，但两者的定位完全相反——前者代表**服务提供者**，后者代表**服务消费者**，是Dubbo RPC调用链路中“两端”的配置载体，核心区别如下：


### 一、核心定位区别
| 配置类          | 定位               | 作用场景                     |
|-----------------|--------------------|------------------------------|
| `ServiceConfig` | **服务提供者配置** | 用于描述“要暴露的服务”，是Dubbo服务端发布服务的核心配置 |
| `ReferenceConfig` | **服务消费者配置** | 用于描述“要引用的服务”，是Dubbo客户端发起远程调用的核心配置 |


### 二、核心功能区别
#### 1. `ServiceConfig`（提供者）的核心功能
- 配置**服务接口、实现类**（如`setInterface(DemoService.class)`、`setRef(new DemoServiceImpl())`）；
- 配置服务的**公共属性**（版本、分组、超时、重试次数等）；
- 关联协议配置（`ProtocolConfig`），指定服务暴露的协议和端口；
- 触发服务的**发布流程**（绑定端口、注册到注册中心、启动监听）。


#### 2. `ReferenceConfig`（消费者）的核心功能
- 配置**要调用的服务接口**（如`setInterface(DemoService.class)`）；
- 配置调用的**公共属性**（版本、分组、超时、负载均衡策略等）；
- 关联注册中心配置（`RegistryConfig`），指定从哪里获取提供者地址；
- 生成**远程服务代理对象**（通过`get()`方法获取），封装RPC调用细节。


### 三、使用流程区别
#### 1. `ServiceConfig`（提供者）的使用流程
```java
// 1. 构建服务配置
ServiceConfig<DemoService> serviceConfig = new ServiceConfig<>();
serviceConfig.setInterface(DemoService.class);
serviceConfig.setRef(new DemoServiceImpl());
serviceConfig.setVersion("1.0.0");

// 2. 关联协议+注册中心
serviceConfig.setProtocol(new ProtocolConfig("tri", 50051));
serviceConfig.setRegistry(new RegistryConfig("nacos://localhost:8848"));

// 3. 发布服务
serviceConfig.export();
```


#### 2. `ReferenceConfig`（消费者）的使用流程
```java
// 1. 构建引用配置
ReferenceConfig<DemoService> referenceConfig = new ReferenceConfig<>();
referenceConfig.setInterface(DemoService.class);
referenceConfig.setVersion("1.0.0");

// 2. 关联注册中心
referenceConfig.setRegistry(new RegistryConfig("nacos://localhost:8848"));

// 3. 获取代理对象，发起调用
DemoService demoService = referenceConfig.get();
demoService.sayHello("Dubbo");
```


### 四、依赖关系区别
- `ServiceConfig`是**服务的“生产者”**，依赖服务的**实现类**（必须提供具体的业务逻辑）；
- `ReferenceConfig`是**服务的“消费者”**，不依赖实现类，仅依赖服务的**接口定义**（通过接口发起远程调用）。


### 核心总结
`ServiceConfig`和`ReferenceConfig`是Dubbo RPC的“两端配置”：
- **`ServiceConfig`：我有一个服务，我要把它暴露出去让别人调用**；
- **`ReferenceConfig`：我需要调用某个服务，我要找到它并发起请求**；

两者配合注册中心，完成了“服务发布→服务发现→远程调用”的完整RPC链路。