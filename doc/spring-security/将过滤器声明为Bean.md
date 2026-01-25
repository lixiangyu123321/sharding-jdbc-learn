### 将过滤器声明为 Bean
当您声明Filter作为 Spring bean，通过使用@Component或者通过在配置中将其声明为 bean，Spring Boot 会自动将其注册到嵌入式容器中。 这可能会导致过滤器被调用两次，一次由容器调用，一次由 Spring Security 调用，并且以不同的顺序调用。

因此，过滤器通常不是 Spring Bean。

但是，如果您的过滤器需要是 Spring bean（例如，为了利用依赖注入），您可以通过声明`FilterRegistrationBeanbean` 并设置其`enabled`属性设置为`false`:
```java
@Bean
public FilterRegistrationBean<TenantFilter> tenantFilterRegistration(TenantFilter filter) {
FilterRegistrationBean<TenantFilter> registration = new FilterRegistrationBean<>(filter);
registration.setEnabled(false);
return registration;
}
```
这使得HttpSecurity是唯一一个添加它的。