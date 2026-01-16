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
public class DynamicConfigService {

    @Value("${spring.cloud.nacos.config.server-addr}")
    private static String SERVER_ADDR;

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
