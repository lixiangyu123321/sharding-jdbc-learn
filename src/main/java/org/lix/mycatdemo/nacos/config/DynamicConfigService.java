package org.lix.mycatdemo.nacos.config;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/**
 * 获得基于Nacos的ConfigService
 */
@Slf4j
@Configuration
public class DynamicConfigService {

    /**
     * XXX Spring无法将值注入给静态变量
     */
    @Value("${spring.cloud.nacos.config.server-addr}")
    private String SERVER_ADDR;

    /**
     * 此处将Nacos的NacosConfigService注入Spring容器
     * XXX 另一种方法是通过NacosConfigManager来获得NacosConfigService
     * @return
     */
    @Bean
    public ConfigService nacosConfigService(){
        try {
            Properties properties = new Properties();
            properties.put("serverAddr", SERVER_ADDR);
            ConfigService configService = NacosFactory.createConfigService(properties);
            return configService;
        } catch (NacosException e) {
            log.error("", e);
            return null;
        }
    }
}
