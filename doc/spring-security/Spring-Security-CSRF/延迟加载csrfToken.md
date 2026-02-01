## 延迟加载 CsrfToken
### 默认情况下，Spring Security 会推迟加载 CsrfToken，直到需要时才加载。

### 在使用 不安全 HTTP 方法（如 POST）进行请求时，都需要使用 CsrfToken。此外，任何将 token 呈现在响应中的请求都需要该 token，例如带有 <form> 标签的网页，该标签包含一个用于 CSRF token 的隐藏 <input>。

### 由于 Spring Security 默认将 CsrfToken 存储在 HttpSession 中，因此延迟 CSRF token 无需在每个请求中加载 session，从而提高了性能。

### 如果你不希望使用延迟 token，而希望在每次请求时加载 CsrfToken，可以通过以下配置实现：

```Java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		XorCsrfTokenRequestAttributeHandler requestHandler = new XorCsrfTokenRequestAttributeHandler();
		// set the name of the attribute the CsrfToken will be populated on
		requestHandler.setCsrfRequestAttributeName(null);
		http
			// ...
			.csrf((csrf) -> csrf
				.csrfTokenRequestHandler(requestHandler)
			);
		return http.build();
	}
}
```
### 通过将 csrfRequestAttributeName 设置为 null，必须首先加载 CsrfToken 以确定使用什么属性名。这将导致每次请求都加载 CsrfToken。