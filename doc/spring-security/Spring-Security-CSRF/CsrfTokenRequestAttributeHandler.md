## 使用 CsrfTokenRequestAttributeHandler
### CsrfTokenRequestAttributeHandler 使 CsrfToken 作为名为 _csrf 的 HttpServletRequest attribute 可用。

### CsrfToken 也可作为 request attribute 使用，名称为 CsrfToken.class.getName()。该名称不可配置，但可使用 CsrfTokenRequestAttributeHandler#setCsrfRequestAttributeName 更改名称 _csrf。

### 该实现还将来自请求的 token 值解析为请求头（默认为 X-CSRF-TOKEN 或 X-XSRF-TOKEN 之一）或请求参数（默认为 _csrf）。

### CsrfTokenRequestAttributeHandler 的主要用途是选择退出 CsrfToken 的 BREACH 保护，可通过以下配置进行配置：

```Java


@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
			// ...
			.csrf((csrf) -> csrf
				.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
			);
		return http.build();
	}
}
```