package org.lix.mycatdemo.filter;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.Resource;

//@Configuration
public class FilterConfig {

    //@Resource
    private SignAuthenticationFilter mySignAuthenticationFilter;


    //@Bean
    public FilterRegistrationBean<SignAuthenticationFilter> signAuthenticationFilter(){
        FilterRegistrationBean<SignAuthenticationFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(mySignAuthenticationFilter);
        registrationBean.addUrlPatterns("/*");
        // 过滤器名称
        registrationBean.setName("signAuthenticationFilter");
        return registrationBean;
    }
}
