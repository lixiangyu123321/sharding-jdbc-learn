package org.lix.provider;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Dubbo 服务提供方启动类
 */
@SpringBootApplication(
        exclude = {
                org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
                org.apache.shardingsphere.shardingjdbc.spring.boot.SpringBootConfiguration.class,
                // 可选：排除Spring Boot默认的数据源自动装配（Druid Starter已自定义，可加可不加）
                org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class
        }
)
@EnableDubbo
public class ProviderApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(ProviderApplication.class, args);
    }
}

