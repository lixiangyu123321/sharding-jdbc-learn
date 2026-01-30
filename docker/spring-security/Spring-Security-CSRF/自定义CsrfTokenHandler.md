自定义`CsrfTokenRequestHandler`需要实现Spring Security提供的**`CsrfTokenRequestHandler`接口**（这是专门定义CSRF Token请求处理规范的核心接口），该接口位于`org.springframework.security.web.csrf`包下，是所有CSRF Token处理器（包括默认的`CsrfTokenRequestAttributeHandler`、6.1+的`XorCsrfTokenRequestAttributeHandler`）的顶层规范。

### 一、核心接口：`CsrfTokenRequestHandler`
#### 1. 接口核心方法（仅1个，需强制实现）
该接口的设计非常简洁，只有一个核心方法，封装了**CSRF Token请求处理的全量逻辑**（解析前端Token、管理请求属性、协调传回前端等），方法定义如下：
```java
package org.springframework.security.web.csrf;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;

public interface CsrfTokenRequestHandler {
    /**
     * 处理CSRF Token相关的请求逻辑（核心方法，必须实现）
     * @param request 前端Http请求（用于解析前端Token、设置请求属性）
     * @param response 服务端Http响应（用于将Token传回前端，如写Cookie、写响应体）
     * @param csrfTokenSupplier Token提供者（从CsrfTokenRepository中获取服务端有效Token的回调）
     */
    void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfTokenSupplier);
}
```
**参数关键说明**：
- `csrfTokenSupplier`：这是框架传入的**Token提供者**，调用`csrfTokenSupplier.get()`就能从`CsrfTokenRepository`中获取到服务端的有效`CsrfToken`对象（无需手动操作仓库，框架已做好封装），这是**Handler与仓库协作的核心入口**。

#### 2. 接口设计优势
- 职责单一：仅通过一个方法封装所有核心逻辑，符合「单一职责原则」；
- 解耦仓库：通过`Supplier`回调获取仓库的Token，无需Handler直接依赖`CsrfTokenRepository`，框架层完成解耦；
- 扩展性强：开发者可在方法内自定义**前端Token解析规则**、**请求属性存储规则**、**Token传回前端的方式**，完全覆盖默认处理器的所有能力，还能做定制化扩展。

### 二、自定义开发的最佳实践：继承抽象类（而非直接实现接口）
虽然可以**直接实现`CsrfTokenRequestHandler`接口**，但Spring Security提供了**抽象实现类`CsrfTokenRequestAttributeHandler`**（这也是默认的处理器），该类已经实现了「**请求属性管理**」「**默认前端Token解析**」等基础核心逻辑，开发者只需**继承该类并重写指定方法**，就能实现定制化需求，无需重复开发基础功能，大幅减少代码量。

#### 1. 抽象类`CsrfTokenRequestAttributeHandler`的核心价值
- 已实现`handle`方法的基础逻辑：从`csrfTokenSupplier`获取Token、存入请求属性（默认`_csrf`）、调用解析方法解析前端Token；
- 暴露**可重写的扩展方法**：将「前端Token解析」「Token传回前端」等核心逻辑拆分为独立方法，开发者按需重写即可，无需修改全量逻辑；
- 兼容所有版本：Spring Security 5.8+、6.x均支持，是自定义Handler的官方推荐基类。

#### 2. 抽象类中可重写的核心扩展方法（按需选择）
| 方法名 | 方法作用 | 重写场景 |
|--------|----------|----------|
| `resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken)` | 解析前端传入的CSRF Token字符串（核心解析方法） | 自定义前端Token的传递规则（如修改默认请求头/参数名、增加自定义解析位置） |
| `handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfTokenSupplier)` | 全量处理逻辑 | 需彻底重写所有处理逻辑（如完全自定义请求属性存储、Token传回方式） |
| `setCsrfRequestAttributeName(String name)` | （非重写，是设置方法） | 自定义Token在请求属性中的别名（替代默认的`_csrf`） |

### 三、自定义Handler示例（实用版，继承抽象类）
以**「自定义前端Token解析规则」**为例（将默认请求头`X-XSRF-TOKEN`改为自定义头`X-MY-CSRF-TOKEN`），实现一个自定义Handler，这是开发中最常见的定制化场景，代码可直接复用：

#### 1. 自定义Handler实现类
```java
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 自定义CSRF Token处理器：修改前端Token的解析请求头为X-MY-CSRF-TOKEN
 */
public class MyCustomCsrfTokenHandler extends CsrfTokenRequestAttributeHandler {

    /**
     * 重写前端Token解析方法，自定义解析规则
     * @param request 前端请求
     * @param csrfToken 服务端有效Token（可获取框架默认的头名/参数名）
     * @return 解析出的前端Token字符串（解析不到返回null）
     */
    @Override
    protected String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        // 自定义规则：仅从请求头X-MY-CSRF-TOKEN解析前端Token（可按需增加多位置解析）
        String customCsrfToken = request.getHeader("X-MY-CSRF-TOKEN");
        if (customCsrfToken != null && !customCsrfToken.isBlank()) {
            return customCsrfToken;
        }
        // 可选：解析不到时，调用父类方法按默认规则解析（请求头X-CSRF-TOKEN/X-XSRF-TOKEN → 参数_csrf）
        // return super.resolveCsrfTokenValue(request, csrfToken);
        
        // 解析不到返回null，后续CsrfFilter会判定为Token缺失，抛出403
        return null;
    }
}
```

#### 2. Spring Security配置（启用自定义Handler）
将自定义Handler配置到CSRF规则中，替换默认处理器，代码如下（适配Spring Security 6.xLambda配置风格）：
```java
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .csrf(csrf -> csrf
                // 搭配Cookie存储Token（前后端分离场景）
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                // 启用自定义CSRF Token处理器
                .csrfTokenRequestHandler(new MyCustomCsrfTokenHandler())
        );
    return http.build();
}
```

### 四、直接实现接口的极简示例（了解即可，不推荐）
如果需要**完全自定义所有逻辑**（如放弃请求属性存储、完全自定义Token传回方式），可直接实现`CsrfTokenRequestHandler`接口，以下是极简示例（仅做逻辑演示，实际开发需完善异常处理、边界判断）：
```java
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;

/**
 * 直接实现接口的自定义Handler（极简演示，实际开发需完善）
 */
public class SimpleCsrfTokenHandler implements CsrfTokenRequestHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfTokenSupplier) {
        // 1. 从仓库获取服务端有效Token
        CsrfToken csrfToken = csrfTokenSupplier.get();
        if (csrfToken == null) {
            return;
        }
        // 2. 自定义：将Token存入请求属性，设置自定义别名
        request.setAttribute("my_custom_csrf", csrfToken);
        // 3. 自定义：解析前端Token（从自定义参数my_csrf解析）
        String frontToken = request.getParameter("my_csrf");
        System.out.println("前端传入的Token：" + frontToken);
        // 4. 自定义：将Token传回前端（如写自定义Cookie）
        response.addCookie(new jakarta.servlet.http.Cookie("MY-CSRF-TOKEN", csrfToken.getToken()));
        // 注：实际开发中，还需将解析的前端Token交给CsrfFilter做校验，
        // 直接实现接口时需手动协调，不如继承抽象类便捷
    }
}
```

### 五、关键注意事项
1. **包名适配**：Spring Security 6.x使用`jakarta.servlet`包（替代5.x的`javax.servlet`），开发时需注意导入的`HttpServletRequest`/`HttpServletResponse`包名与项目依赖一致；
2. **与仓库的协作**：无论自定义方式如何，**都不要手动操作`CsrfTokenRepository`**，通过`csrfTokenSupplier.get()`获取Token即可，框架会自动完成仓库的调用与解耦；
3. **403异常触发**：如果解析不到前端Token、或解析的Token与服务端Token不一致，`CsrfFilter`会自动抛出403 Forbidden异常，无需在Handler中手动处理；
4. **XOR防护兼容**：如果是Spring Security 6.1+版本，需要自定义带XOR掩码的Handler，可继承`XorCsrfTokenRequestAttributeHandler`（而非`CsrfTokenRequestAttributeHandler`），重写扩展方法即可保留XOR防BREACH攻击能力。

### 总结
1. 自定义CSRF Token处理器的**顶层接口**是`org.springframework.security.web.csrf.CsrfTokenRequestHandler`，核心需实现`handle`方法；
2. **实际开发首选继承抽象类`CsrfTokenRequestAttributeHandler`**，无需重复开发基础逻辑，按需重写`resolveCsrfTokenValue`（自定义解析）、`handle`（全量自定义）即可；
3. 核心协作入口`csrfTokenSupplier`：调用其`get()`方法可从仓库获取服务端有效Token，无需手动依赖`CsrfTokenRepository`，框架完成解耦；
4. 自定义后通过`.csrfTokenRequestHandler(自定义Handler实例)`配置到Spring Security中，即可替换默认处理器。