# ShardingJDBC 动态配置刷新问题与解决方案

本文档记录在实现 ShardingSphere-JDBC 基于 Nacos 的动态配置刷新功能过程中遇到的问题和解决方案。

## 目录

1. [配置前缀错误问题](#1-配置前缀错误问题)
2. [类型转换异常问题](#2-类型转换异常问题)
3. [数据源关闭时机问题](#3-数据源关闭时机问题)
4. [Bean 注册冲突问题](#4-bean-注册冲突问题)
5. [分片策略不生效问题](#5-分片策略不生效问题)

---

## 1. 配置前缀错误问题

### 问题描述

配置刷新时，解析后的配置项数量为 0，无法识别数据源配置。

```
2026-01-18 20:45:03.929  INFO 14596 --- [-refresh-thread] o.l.m.n.listener.ShardingJDBCListener    : 配置解析完成，解析后的配置项数量：0
2026-01-18 20:45:03.930  INFO 14596 --- [-refresh-thread] o.l.m.n.listener.ShardingJDBCListener    : 识别到数据源数量：0，数据源名称：[]
```

### 原因分析

代码中使用的配置前缀不正确：

```java
// 错误：使用了 dataSources（复数）
private static final String DATA_SOURCES_PREFIX = "spring.shardingsphere.dataSources.";

// 但实际配置使用的是 datasource（单数）
spring:
  shardingsphere:
    datasource:  # 注意：是 datasource 不是 dataSources
      names: order-db1, order-db2
```

### 解决方案

修正配置前缀，使用 `datasource`（单数）：

```java
// 修正：使用 datasource（单数）
private static final String DATA_SOURCES_PREFIX = "spring.shardingsphere.datasource.";  // 注意：是 datasource 不是 dataSources
```

### 修改位置

- 文件：`ShardingJDBCListener.java`
- 行号：第 63 行

---

## 2. 类型转换异常问题

### 问题描述

配置解析后，某些配置值的类型为 `Integer`，但代码直接强制转换为 `String`，导致 `ClassCastException`。

```
java.lang.ClassCastException: java.lang.Integer cannot be cast to java.lang.String
	at org.lix.mycatdemo.nacos.listener.ShardingJDBCListener.createBasicDataSource(ShardingJDBCListener.java:465)
```

### 原因分析

YAML 解析器可能会将某些值（如 `true`）解析为 `Integer`（如 `1`），而代码直接使用 `(String)` 强制转换：

```java
// 错误的做法
String url = (String) flatConfigMap.get(urlKey);  // 如果值是 Integer，会抛出 ClassCastException
```

### 解决方案

创建安全的类型转换方法，使用 `toString()` 统一处理：

```java
/**
 * 通用化读取 String 类型配置：避免类型转换异常
 */
private String getStringConfig(Map<String, Object> map, String key) {
    return getStringConfig(map, key, null);
}

private String getStringConfig(Map<String, Object> map, String key, String defaultValue) {
    Object value = map.get(key);
    if (value == null) {
        return defaultValue;
    }
    // 安全转换为字符串：无论原始类型是什么，都转换为字符串
    return value.toString();
}
```

将所有配置读取都替换为使用 `getStringConfig` 方法：

```java
// 修改前
String url = (String) flatConfigMap.get(urlKey);
String username = (String) flatConfigMap.get(usernameKey);

// 修改后
String url = getStringConfig(flatConfigMap, urlKey);
String username = getStringConfig(flatConfigMap, usernameKey);
```

### 修改位置

- 文件：`ShardingJDBCListener.java`
- 方法：`createBasicDataSource`, `buildTableRuleConfig`, `buildMasterSlaveRuleConfig`, `buildDefaultShardingStrategy` 等所有读取配置的方法

---

## 3. 数据源关闭时机问题

### 问题描述

配置刷新后，正在进行的操作失败，报错 `HikariDataSource has been closed`。

```
java.sql.SQLException: HikariDataSource HikariDataSource (HikariPool-6) has been closed.
	at com.zaxxer.hikari.HikariDataSource.getConnection(HikariDataSource.java:96)
```

### 原因分析

1. **关闭顺序问题**：在注册新数据源之前就关闭了旧数据源，导致正在进行的操作失败
2. **销毁回调触发**：使用 `destroySingleton()` 会触发 Bean 销毁回调，导致数据源被关闭
3. **连接池名称冲突**：新旧数据源使用相同的连接池名称，可能导致旧数据源被关闭

### 解决方案

#### 3.1 调整关闭顺序

**修改前：**
```java
// 3. 销毁旧的 ShardingDataSource Bean
destroyOldShardingDataSource();

// 4. 创建新的 ShardingDataSource
DataSource newShardingDataSource = createNewShardingDataSource(...);

// 5. 注册新的 ShardingDataSource
registerShardingDataSourceBean(newShardingDataSource);
```

**修改后：**
```java
// 3. 先保存旧数据源引用
DataSource oldDataSource = getOldShardingDataSource();

// 4. 创建新的 ShardingDataSource
DataSource newShardingDataSource = createNewShardingDataSource(...);

// 5. 移除旧 Bean（不关闭数据源）
removeOldShardingDataSourceBeanBeforeRegister(oldDataSource);

// 6. 注册新的 ShardingDataSource
registerShardingDataSourceBean(newShardingDataSource);

// 7. 不主动关闭旧数据源，让它自然回收（GC）
// scheduleOldDataSourceCleanup(oldDataSource);  // 暂时禁用主动关闭
```

#### 3.2 使用反射移除单例，避免触发销毁回调

**修改前：**
```java
// 使用 destroySingleton 会触发 Bean 销毁回调，导致数据源被关闭
beanFactory.destroySingleton(SHARDING_DATASOURCE_BEAN_NAME);
```

**修改后：**
```java
try {
    // 使用反射直接移除单例，避免触发销毁回调
    java.lang.reflect.Method removeSingletonMethod = 
        org.springframework.beans.factory.support.DefaultSingletonBeanRegistry.class
            .getDeclaredMethod("removeSingleton", String.class);
    removeSingletonMethod.setAccessible(true);
    removeSingletonMethod.invoke(beanFactory, SHARDING_DATASOURCE_BEAN_NAME);
    log.debug("已移除旧数据源单例 Bean 注册（使用反射，避免触发销毁回调）");
} catch (Exception e) {
    // 如果反射失败，不使用 destroySingleton（避免触发销毁回调）
    log.error("使用反射移除单例失败，无法安全移除旧 Bean: {}", e.getMessage());
    throw new RuntimeException("无法安全移除旧数据源 Bean，反射调用失败: " + e.getMessage(), e);
}
```

#### 3.3 设置唯一的连接池名称

```java
// 设置唯一的连接池名称，避免新旧数据源冲突
// 使用时间戳确保每次刷新时创建的数据源都有唯一名称
String uniquePoolName = dsName + "-" + System.currentTimeMillis();
hikariConfig.setPoolName(uniquePoolName);
log.debug("数据源 {} 使用连接池名称: {}", dsName, uniquePoolName);
```

#### 3.4 不主动关闭旧数据源

最安全的做法是**不主动关闭旧数据源**，让它自然回收（GC）。因为：
- 新数据源已经注册，新请求会使用新数据源
- 旧数据源在没有引用后会被 GC 自动回收
- 主动关闭可能导致正在进行的操作失败

### 修改位置

- 文件：`ShardingJDBCListener.java`
- 方法：`receiveConfigInfo`, `removeOldShardingDataSourceBeanBeforeRegister`, `createBasicDataSource`

---

## 4. Bean 注册冲突问题

### 问题描述

注册新数据源时，Spring 容器中已存在同名 Bean，导致注册失败。

```
java.lang.IllegalStateException: Could not register object [...] under bean name 'shardingDataSource': there is already object [...] bound
	at org.springframework.beans.factory.support.DefaultSingletonBeanRegistry.registerSingleton(DefaultSingletonBeanRegistry.java:124)
```

### 原因分析

在注册新数据源之前，旧数据源的 Bean 仍然存在于 Spring 容器中，导致 `registerSingleton` 失败。

### 解决方案

在注册新数据源之前，先移除旧数据源的 Bean（但不关闭数据源对象）：

```java
// 在注册新数据源之前，先移除旧数据源的 Bean
private void removeOldShardingDataSourceBeanBeforeRegister(DataSource oldDataSource) {
    if (oldDataSource == null) {
        log.debug("没有旧数据源需要移除");
        return;
    }
    
    ConfigurableApplicationContext configurableContext = (ConfigurableApplicationContext) applicationContext;
    DefaultListableBeanFactory beanFactory = (DefaultListableBeanFactory) configurableContext.getBeanFactory();

    try {
        // 1. 移除 Bean 定义（如果存在）
        if (beanFactory.containsBeanDefinition(SHARDING_DATASOURCE_BEAN_NAME)) {
            beanFactory.removeBeanDefinition(SHARDING_DATASOURCE_BEAN_NAME);
            log.debug("已移除旧数据源 Bean 定义");
        }
        
        // 2. 使用反射移除单例注册（避免触发销毁回调）
        if (beanFactory.containsSingleton(SHARDING_DATASOURCE_BEAN_NAME)) {
            try {
                java.lang.reflect.Method removeSingletonMethod = 
                    org.springframework.beans.factory.support.DefaultSingletonBeanRegistry.class
                        .getDeclaredMethod("removeSingleton", String.class);
                removeSingletonMethod.setAccessible(true);
                removeSingletonMethod.invoke(beanFactory, SHARDING_DATASOURCE_BEAN_NAME);
                log.debug("已移除旧数据源单例 Bean 注册（使用反射，避免触发销毁回调）");
            } catch (Exception e) {
                log.error("使用反射移除单例失败，无法安全移除旧 Bean: {}", e.getMessage());
                throw new RuntimeException("无法安全移除旧数据源 Bean，反射调用失败: " + e.getMessage(), e);
            }
        }
        
        log.info("旧数据源 Bean 已从 Spring 容器中移除（数据源对象未关闭，将在后续延迟关闭）");
    } catch (Exception e) {
        log.warn("移除旧数据源 Bean 失败: {}", e.getMessage());
    }
}
```

### 关键点

1. **先移除 Bean 定义**：`removeBeanDefinition` 移除 Bean 的定义
2. **使用反射移除单例**：`removeSingleton` 直接移除单例注册，不触发销毁回调
3. **不关闭数据源对象**：只是从容器中移除引用，数据源对象本身不会被关闭

### 修改位置

- 文件：`ShardingJDBCListener.java`
- 方法：`removeOldShardingDataSourceBeanBeforeRegister`

---

## 5. 分片策略不生效问题

### 问题描述

配置刷新后，新的分片策略没有生效，SQL 仍然使用旧的分片策略。

### 原因分析

虽然 Spring 容器中的数据源 Bean 已经更新，但 **MyBatis 的 `SqlSessionFactory` 和 `SqlSessionTemplate` 在初始化时已经绑定了数据源引用**，这些组件不会自动更新引用，导致新的分片策略不生效。

### 解决方案

在注册新数据源后，刷新 MyBatis 相关组件，使其使用新的数据源：

```java
/**
 * 刷新 MyBatis 相关组件，使其使用新的数据源
 * 
 * 原因：MyBatis 的 SqlSessionFactory 和 SqlSessionTemplate 在初始化时绑定了数据源引用
 * 即使替换了 Spring 容器中的数据源 Bean，这些组件仍可能使用旧引用，导致分片策略不生效
 */
private void refreshMyBatisComponents(DataSource newDataSource) {
    try {
        // 尝试获取 SqlSessionFactory Bean
        try {
            org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory = 
                applicationContext.getBean(org.apache.ibatis.session.SqlSessionFactory.class);
            
            if (sqlSessionFactory != null) {
                // 更新 SqlSessionFactory Configuration 中的数据源引用
                updateSqlSessionFactoryDataSource(sqlSessionFactory, newDataSource);
            }
        } catch (Exception e) {
            log.debug("未找到 SqlSessionFactory Bean 或无法更新: {}", e.getMessage());
        }

        log.info("MyBatis 组件刷新完成（数据源引用已更新）");
    } catch (Exception e) {
        log.warn("刷新 MyBatis 组件时发生错误: {}", e.getMessage());
        // 不抛出异常，避免影响数据源注册
    }
}

/**
 * 更新 SqlSessionFactory 中的数据源引用（通过 Configuration）
 */
private void updateSqlSessionFactoryDataSource(org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory, DataSource newDataSource) {
    try {
        // 获取 SqlSessionFactory 的 Configuration
        org.apache.ibatis.session.Configuration configuration = sqlSessionFactory.getConfiguration();
        
        // 获取 Environment
        org.apache.ibatis.mapping.Environment environment = configuration.getEnvironment();
        if (environment != null) {
            // 创建新的 Environment（使用新数据源）
            org.apache.ibatis.transaction.TransactionFactory transactionFactory = environment.getTransactionFactory();
            org.apache.ibatis.mapping.Environment newEnvironment = 
                new org.apache.ibatis.mapping.Environment(environment.getId(), transactionFactory, newDataSource);
            
            // 使用反射更新 Configuration 中的 Environment
            java.lang.reflect.Field environmentField = 
                org.apache.ibatis.session.Configuration.class.getDeclaredField("environment");
            environmentField.setAccessible(true);
            environmentField.set(configuration, newEnvironment);
            
            log.info("SqlSessionFactory Configuration 的数据源引用已更新");
        }
    } catch (Exception e) {
        log.warn("更新 SqlSessionFactory Configuration 数据源引用失败: {}", e.getMessage());
        log.warn("建议：如果分片策略仍未生效，可能需要重新创建 SqlSessionFactory 和 SqlSessionTemplate");
    }
}
```

### 工作流程

```
1. 获取旧数据源引用
2. 创建新数据源（包含新的分片策略）
3. 移除旧 Bean（使用反射，避免触发销毁回调）
4. 注册新数据源 Bean
5. 刷新 MyBatis 组件（关键步骤！）
   - 更新 SqlSessionFactory 的数据源引用
   - 确保新的分片策略生效
6. 旧数据源自然回收
```

### 修改位置

- 文件：`ShardingJDBCListener.java`
- 方法：`refreshMyBatisComponents`, `updateSqlSessionFactoryDataSource`
- 调用位置：`receiveConfigInfo` 方法中，在 `registerShardingDataSourceBean` 之后

---

## 完整的工作流程

### 配置刷新流程

```
1. 监听 Nacos 配置变化
   ↓
2. 解析配置（YAML/Properties）
   ↓
3. 获取旧数据源引用（在注册新数据源之前）
   ↓
4. 创建新数据源（使用唯一连接池名称，包含新分片策略）
   ↓
5. 移除旧 Bean（使用反射，避免触发销毁回调）
   ↓
6. 注册新数据源 Bean
   ↓
7. 刷新 MyBatis 组件（更新数据源引用）
   ↓
8. 旧数据源自然回收（GC）
```

### 关键注意事项

1. **配置前缀**：使用 `spring.shardingsphere.datasource.`（单数），不是 `dataSources`
2. **类型转换**：使用 `getStringConfig()` 安全转换，不要直接强制转换
3. **Bean 移除**：使用反射 `removeSingleton`，避免触发销毁回调
4. **连接池名称**：使用时间戳生成唯一名称，避免冲突
5. **数据源关闭**：不主动关闭旧数据源，让它自然回收
6. **MyBatis 刷新**：必须刷新 `SqlSessionFactory` 的数据源引用

---

## 总结

实现 ShardingSphere-JDBC 动态配置刷新的核心挑战在于：

1. **避免数据源被提前关闭**：使用反射移除 Bean，不触发销毁回调
2. **更新 MyBatis 组件引用**：确保 `SqlSessionFactory` 使用新的数据源
3. **处理类型转换**：安全地将配置值转换为需要的类型
4. **正确的配置前缀**：确保配置键与实际配置一致

通过以上解决方案，可以实现 ShardingSphere-JDBC 配置的动态刷新，无需重启应用即可使新的分片策略生效。

---

## 相关文件

- `ShardingJDBCListener.java`：配置监听和刷新逻辑
- `DynamicConfigManager.java`：动态配置管理器
- `application.yaml`：应用配置文件

---

## 参考资料

- [ShardingSphere-JDBC 官方文档](https://shardingsphere.apache.org/document/current/cn/overview/)
- [Spring Boot 自动配置](https://docs.spring.io/spring-boot/docs/current/reference/html/auto-configuration-classes.html)
- [MyBatis Spring 集成](https://mybatis.org/spring/index.html)

