package org.lix.mycatdemo.interceptor;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

//@Configuration
public class WebInterceptorConfig implements WebMvcConfigurer {

    @Resource
    private SecurityInterceptor securityInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 拦截所有路径
        registry.addInterceptor(securityInterceptor).addPathPatterns("/api/**");
    }
}
