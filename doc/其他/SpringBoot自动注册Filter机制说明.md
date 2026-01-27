# Spring Boot 自动注册 Filter 机制说明

## 问题现象

即使注释掉了 `FilterConfig` 中的 `@Configuration`、`@Resource`、`@Bean` 注解，Filter 仍然被注册到 Servlet 容器中。

## 原因分析

### 1. Spring Boot 的自动注册机制

Spring Boot 有一个**自动配置机制**，会自动将容器中实现了 `Filter` 接口的 Bean 注册为 Filter。

**关键代码位置：**
- `ServletContextInitializerBeans` 类
- 在 Spring Boot 启动时，会自动扫描容器中所有实现了 `Filter` 接口的 Bean
- 并将这些 Bean 自动注册到 Servlet 容器中

### 2. 当前代码情况

```java
// FilterConfig.java - 所有注解都被注释掉了
//@Configuration
public class FilterConfig {
    //@Resource
    private SignAuthenticationFilter mySignAuthenticationFilter;
    
    //@Bean
    public FilterRegistrationBean<SignAuthenticationFilter> signAuthenticationFilter(){
        // ...
    }
}

// SignAuthenticationFilter.java - 仍然有 @Component 注解
@Component("mySignAuthenticationFilter")  // ← 关键：这个注解还在！
@RefreshScope
public class SignAuthenticationFilter implements Filter {
    // ...
}
```

### 3. 为什么 Filter 仍然被注册？

**原因：**
1. `SignAuthenticationFilter` 使用了 `@Component("mySignAuthenticationFilter")` 注解
2. Spring Boot 的组件扫描会将其识别为 Spring Bean
3. Spring Boot 发现这个 Bean 实现了 `Filter` 接口
4. **自动注册机制**：`ServletContextInitializerBeans` 会自动将其注册为 Filter

**执行流程：**
```
1. Spring Boot 启动
2. 组件扫描发现 @Component("mySignAuthenticationFilter")
3. 创建 SignAuthenticationFilter Bean
4. ServletContextInitializerBeans 扫描所有 Filter 类型的 Bean
5. 发现 SignAuthenticationFilter 实现了 Filter 接口
6. 自动注册到 Servlet 容器 ← 即使 FilterConfig 被注释掉
```

## Spring Boot 自动注册 Filter 的机制

### ServletContextInitializerBeans 的工作原理

Spring Boot 在启动时会创建 `ServletContextInitializerBeans` 实例，它会：

1. **扫描所有 Filter 类型的 Bean**
   ```java
   // 伪代码
   List<Filter> filters = applicationContext.getBeansOfType(Filter.class);
   for (Filter filter : filters) {
       // 自动注册到 Servlet 容器
       servletContext.addFilter(filter);
   }
   ```

2. **自动创建 FilterRegistrationBean**
   - 对于没有通过 `FilterRegistrationBean` 注册的 Filter
   - Spring Boot 会自动创建一个 `FilterRegistrationBean` 来注册它
   - 使用默认的 URL 模式：`/*`

3. **优先级处理**
   - 如果同时存在手动注册（通过 `FilterRegistrationBean`）和自动注册
   - 手动注册的优先级更高

## 如何禁用自动注册？

### 方法1：移除 @Component 注解（推荐）

如果不想让 Spring Boot 自动注册 Filter，可以：

```java
// 移除 @Component 注解
// @Component("mySignAuthenticationFilter")  // 注释掉或删除
public class SignAuthenticationFilter implements Filter {
    // ...
}
```

然后通过 `FilterConfig` 手动注册：

```java
@Configuration
public class FilterConfig {
    
    @Bean
    public FilterRegistrationBean<SignAuthenticationFilter> signAuthenticationFilter() {
        FilterRegistrationBean<SignAuthenticationFilter> registrationBean = 
            new FilterRegistrationBean<>();
        
        SignAuthenticationFilter filter = new SignAuthenticationFilter();
        // 手动设置配置
        // ...
        
        registrationBean.setFilter(filter);
        registrationBean.addUrlPatterns("/*");
        registrationBean.setName("signAuthenticationFilter");
        return registrationBean;
    }
}
```

### 方法2：使用 @Order 控制顺序

如果希望自动注册但控制顺序：

```java
@Component("mySignAuthenticationFilter")
@Order(1)  // 控制 Filter 的执行顺序
public class SignAuthenticationFilter implements Filter {
    // ...
}
```

### 方法3：排除自动配置

在启动类中排除 Filter 的自动配置（不推荐，会影响所有 Filter）：

```java
@SpringBootApplication(exclude = {
    // 排除 Filter 自动配置（不推荐）
})
```

## 两种注册方式的对比

### 方式1：自动注册（当前使用）

```java
@Component("mySignAuthenticationFilter")
public class SignAuthenticationFilter implements Filter {
    // Spring Boot 自动注册
}
```

**特点：**
- ✅ 简单，不需要额外配置
- ✅ Spring 依赖注入可用（@Value、@Autowired 等）
- ❌ 无法精确控制注册参数（URL 模式、顺序等）
- ❌ 使用默认的 URL 模式：`/*`

### 方式2：手动注册（通过 FilterRegistrationBean）

```java
// 移除 @Component
public class SignAuthenticationFilter implements Filter {
    // 需要手动注册
}

@Configuration
public class FilterConfig {
    @Bean
    public FilterRegistrationBean<SignAuthenticationFilter> signAuthenticationFilter() {
        // 手动注册，可以精确控制
    }
}
```

**特点：**
- ✅ 可以精确控制注册参数
- ✅ 可以设置 URL 模式、顺序、名称等
- ❌ 需要手动创建 Filter 实例
- ❌ 需要手动注入配置（如果 Filter 需要 Spring Bean）

## 当前项目的实际情况

### 当前状态

1. **FilterConfig 被注释掉**：`@Configuration`、`@Resource`、`@Bean` 都被注释
2. **SignAuthenticationFilter 仍有 @Component**：`@Component("mySignAuthenticationFilter")` 仍然存在
3. **结果**：Filter 通过 Spring Boot 的自动注册机制被注册

### 验证方法

可以通过日志或代码验证：

```java
@PostConstruct
public void checkFilterRegistration() {
    // 检查 Filter 是否被注册
    FilterRegistrationBean<?> registration = 
        applicationContext.getBean("signAuthenticationFilter", FilterRegistrationBean.class);
    log.info("Filter 注册信息：{}", registration);
}
```

或者查看启动日志，应该能看到 Filter 被自动注册的信息。

## 总结

**为什么注释掉 FilterConfig 后 Filter 仍然被注册？**

1. **根本原因**：`SignAuthenticationFilter` 使用了 `@Component` 注解
2. **自动机制**：Spring Boot 会自动注册所有实现了 `Filter` 接口的 Bean
3. **执行时机**：在 `ServletContextInitializerBeans` 初始化时自动执行

**如何控制 Filter 注册？**

- **自动注册**：保留 `@Component`，让 Spring Boot 自动注册（简单但控制有限）
- **手动注册**：移除 `@Component`，通过 `FilterRegistrationBean` 手动注册（复杂但控制精确）

**推荐做法：**

- 如果需要精确控制 Filter 的注册参数（URL 模式、顺序等），使用手动注册
- 如果只需要简单的 Filter 注册，使用自动注册即可

