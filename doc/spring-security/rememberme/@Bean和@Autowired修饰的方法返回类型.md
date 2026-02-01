你的问题核心是**Spring容器中，调用被`@Autowired`/`@Bean`修饰的方法，是否能获取到容器管理的Bean对象**，答案分两种核心场景（**`@Bean`方法调用**和**`@Autowired`方法调用**），结论完全不同，且`@Bean`方法的调用行为是Spring的核心特性（易踩坑），`@Autowired`修饰方法本身则是注入方式而非Bean定义，先明确核心结论，再逐一场景讲清：

### 核心结论先记牢
1. **调用被`@Bean`修饰的方法**：**在配置类（`@Configuration`）内部调用** → 返回**Spring容器管理的单例Bean**；**在配置类外部调用** → 返回**普通的新对象**（非Bean，不受容器管理）；
2. **调用被`@Autowired`修饰的方法**：`@Autowired`修饰方法是**依赖注入的一种方式**（并非定义Bean），调用该方法只会执行方法本身逻辑，**不会返回Bean对象**，也和容器Bean无关；

补充：很多人会混淆`@Autowired`修饰**方法**和修饰**字段/构造器**，也会把`@Bean`方法的调用和普通方法混淆，这是核心误区，下面逐一场景讲透，包含原理和示例。

---

## 场景1：调用被`@Bean`修饰的方法（核心重点，易踩坑）
`@Bean`是**定义Spring Bean的核心注解**，用于方法上，告诉Spring「该方法的返回值要纳入容器管理，作为单例Bean」，而**配置类（`@Configuration`）对`@Bean`方法做了动态代理增强**，这是调用行为不同的根本原因。

### 关键原理
Spring会对标注了`@Configuration`的类生成**CGLIB动态代理对象**，当在配置类内部调用本类的`@Bean`方法时，**代理会拦截该调用**，不会执行方法的原始逻辑创建新对象，而是直接从Spring容器中获取已创建的Bean（单例）；若容器中还没有该Bean，则执行方法创建并加入容器后返回。

而**非配置类**（无`@Configuration`，比如普通`@Component`）中的`@Bean`方法（实际不推荐这么用），Spring不会做代理增强，调用时就是普通方法，返回新对象。

### 分情况示例（最常用的配置类场景）
#### 情况1：配置类内部调用`@Bean`方法 → 返回容器Bean（单例）
```java
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration // 关键：必须是配置类，才会被代理增强
public class BeanConfig {

    // 定义Bean1：容器管理的单例
    @Bean
    public User user() {
        System.out.println("执行user()方法，创建User对象");
        return new User("张三");
    }

    // 配置类内部调用@Bean方法user()
    @Bean
    public UserService userService() {
        System.out.println("调用user()方法");
        User user = user(); // 核心：这里调用的是被代理的方法，不是原始方法
        return new UserService(user);
    }
}
```
**执行结果**：
```
执行user()方法，创建User对象
调用user()方法
```
**核心现象**：
- `user()`方法**仅执行1次**，即使被`userService()`调用，也不会重新创建新User对象；
- `userService()`中获取的`user`是**Spring容器中唯一的User Bean**，实现了单例复用。

#### 情况2：配置类外部调用`@Bean`方法 → 返回普通新对象（非Bean）
如果在普通组件中注入配置类，再调用其`@Bean`方法，此时无代理拦截，就是普通方法调用：
```java
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class TestComponent {

    @Autowired
    private BeanConfig beanConfig; // 注入配置类（代理对象）

    public void testCallBeanMethod() {
        User user = beanConfig.user(); // 配置类外部调用@Bean方法
        System.out.println("外部调用获取的user：" + user);
        System.out.println("是否是容器Bean？" + (user == beanConfig.user())); // false，两次调用返回不同对象
    }
}
```
**执行结果**：
```
执行user()方法，创建User对象 // 容器初始化时执行
外部调用获取的user：User(name=张三)
执行user()方法，创建User对象 // 外部调用时，执行原始方法创建新对象
是否是容器Bean？false
```
**核心现象**：
- 容器初始化时，`user()`会执行1次，创建Bean加入容器；
- 外部调用`beanConfig.user()`时，**代理不会拦截**，直接执行原始方法，每次调用都返回**新的User对象**，该对象**不受Spring容器管理**（不是Bean，无法注入、无生命周期管理）。

#### 情况3：非配置类的`@Bean`方法调用（不推荐）
若方法加了`@Bean`，但所在类无`@Configuration`（只有`@Component`），Spring不会做代理增强，**无论内部/外部调用，都是普通方法**，返回新对象（仅容器初始化时，该方法的返回值会被纳入容器）：
```java
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component // 非配置类，无代理增强
public class NonConfigBean {

    @Bean
    public User user() {
        return new User("张三");
    }

    public void testInnerCall() {
        User user = user(); // 内部调用，无代理，返回新对象
        System.out.println("非配置类内部调用：" + user);
    }
}
```
**注意**：Spring官方**强烈推荐**`@Bean`方法只放在`@Configuration`配置类中，非配置类的`@Bean`是低版本兼容特性，易出问题，避免使用。

---

## 场景2：调用被`@Autowired`修饰的方法（无Bean返回，核心是注入）
首先要明确：**`@Autowired`修饰方法，并非定义Bean，而是Spring的「方法级依赖注入」方式**，和修饰字段、构造器的作用一致——都是让Spring在容器初始化时，自动将方法参数中的Bean注入进来，执行该方法完成初始化逻辑。

被`@Autowired`修饰的方法，**本身不会生成Bean**，调用该方法也只是执行方法体的普通逻辑，**不会返回容器中的Bean**（除非方法体自己返回Bean，但这和`@Autowired`无关）。

### 关键特性：`@Autowired`方法的执行时机
Spring容器初始化当前组件（Bean）时，会**自动执行所有被`@Autowired`修饰的方法**（无需手动调用），并自动注入方法参数中的依赖Bean；手动调用该方法时，参数不会自动注入（需自己传参），就是普通方法执行。

### 示例：`@Autowired`修饰方法的本质（注入+初始化，非Bean定义）
```java
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component // 本类是Bean，受容器管理
public class OrderService {

    private UserService userService;
    private ProductService productService;

    // 被@Autowired修饰的方法：方法级注入，Spring容器初始化时自动执行
    // 作用：注入参数中的UserService、ProductService Bean，完成本类属性初始化
    @Autowired
    public void init(UserService userService, ProductService productService) {
        this.userService = userService;
        this.productService = productService;
        System.out.println("执行@Autowired修饰的init方法，完成注入");
    }

    // 手动调用该方法：普通方法执行，无自动注入，需自己传参
    public void testCallAutowiredMethod() {
        // 这里调用init()，必须手动传参，否则报空参异常
        // 执行后只是重新赋值，不会返回任何Bean
        this.init(new UserService(), new ProductService());
    }
}
```
**核心现象**：
1. 容器初始化`OrderService`时，会**自动执行`init()`方法**，并将容器中的`UserService`、`ProductService` Bean自动注入到参数中，无需手动调用；
2. 手动调用`init()`方法时，**Spring不会自动注入参数**，必须自己传参，执行后只是执行方法体逻辑（赋值），**不会返回任何Bean对象**；
3. 该方法本身和「Bean的创建、获取」无关，`@Autowired`只是让Spring自动执行它并注入依赖。

---

## 补充：易混淆的其他场景
### 场景A：调用**被`@Autowired`注入的Bean对象**的方法 → 执行Bean的方法（返回值看方法本身）
这是开发中最常用的场景，很多人会和「调用`@Autowired`修饰的方法」混淆，比如：
```java
@Component
public class TestService {
    // @Autowired注入容器中的UserService Bean
    @Autowired
    private UserService userService;

    public void test() {
        // 调用的是「被注入的Bean对象」的方法，不是「@Autowired修饰的方法」
        User user = userService.getUser(); // 返回值由getUser()决定，若该方法返回Bean则是Bean
    }
}
```
这里的核心是：`@Autowired`是**注入Bean对象**，调用的是该Bean对象的普通方法，返回值和方法本身有关，和`@Autowired`无关。

### 场景B：`@Configuration`配置类中，调用其他配置类的`@Bean`方法
需通过**注入其他配置类**的方式调用，此时依然会被代理拦截，返回容器Bean：
```java
// 配置类1
@Configuration
public class BeanConfig1 {
    @Bean
    public User user() {
        return new User("张三");
    }
}

// 配置类2
@Configuration
public class BeanConfig2 {
    // 注入配置类1的代理对象
    @Autowired
    private BeanConfig1 beanConfig1;

    @Bean
    public UserService userService() {
        User user = beanConfig1.user(); // 调用其他配置类的@Bean方法，返回容器Bean
        return new UserService(user);
    }
}
```
**关键**：注入的`beanConfig1`是**代理对象**，调用其`@Bean`方法时，依然会被Spring拦截，返回容器中的User Bean。

---

## 最终总结（核心要点）
1. **调用`@Bean`方法**：
    - 「`@Configuration`配置类内部」调用 → 返回**Spring容器管理的单例Bean**（CGLIB代理拦截，复用Bean）；
    - 「配置类外部/非配置类」调用 → 返回**普通新对象**（非Bean，不受容器管理）；
    - 官方推荐：`@Bean`方法仅放在`@Configuration`中，保证单例复用。

2. **调用`@Autowired`修饰的方法**：
    - `@Autowired`是**方法级依赖注入**，并非Bean定义，该方法本身不生成Bean；
    - 容器初始化时Spring**自动执行**该方法并注入参数依赖；
    - 手动调用时是**普通方法**，需自己传参，执行后无Bean返回，仅执行方法体逻辑。

3. 易混点区分：
    - `@Autowired`**修饰方法**：注入依赖，自动执行，无Bean返回；
    - `@Autowired`**注入Bean对象**：获取容器Bean，调用该Bean的方法是普通方法调用，返回值由方法本身决定；
    - `@Bean`**修饰方法**：定义Bean，配置类内部调用返回容器Bean，外部调用返回新对象。

简单来说：**只有`@Configuration`配置类内部的`@Bean`方法调用，才会返回Spring生成的Bean对象，其他情况均不成立**。