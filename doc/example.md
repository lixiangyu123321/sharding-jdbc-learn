
```graph
graph TD
    A[SpringBoot启动] --> B[自定义Nacos配置加载器初始化]
    B --> C[加载public命名空间所有配置+指定命名空间配置]
    C --> D[解析配置为键值对，注入Spring Environment]
    B --> E[注册Nacos配置监听器]
    E --> F[监听配置变更]
    F --> G[更新Environment中的配置值]
    G --> H[发布Environment更新事件]
    A --> I[Spring Bean初始化阶段]
    I --> J[自定义注解处理器扫描@CustomNacosValue]
    J --> K[从Environment取值，类型转换后注入字段]
    H --> L[监听Environment事件，触发注解字段动态更新]
```