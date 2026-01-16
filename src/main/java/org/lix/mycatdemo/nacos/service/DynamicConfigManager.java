package org.lix.mycatdemo.nacos.service;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class DynamicConfigManager implements ApplicationListener<EnvironmentChangeEvent>, EnvironmentAware {

    @Value("${spring.cloud.nacos.config.timeout}")
    private Long DEFAULT_TIMEOUT;

    /**
     * 主配置 Data ID（从 Environment 读取）
     */
    @Value("${spring.config.import[0]}")
    private String mainDataId;

    /**
     * 配置默认分组（从 Environment 读取）
     */
    @Value("${spring.cloud.nacos.config.group}")
    private String group;

    /**
     * 命名空间（从 Environment 读取）
     */
    @Value("${spring.cloud.nacos.config.namespace}")
    private String namespace;

    /**
     * 配置格式（json/yml/yaml/properties）
     */
    @Value("${spring.cloud.nacos.config.file-extension}")
    private String configType;

    /**
     * 是否启用 Nacos 配置中心
     */
    @Value("${spring.cloud.nacos.config.enabled}")
    private boolean nacosEnabled;

    /**
     * 获得环境
     */
    private Environment environment;

    /**
     * Bean注入NacosConfigService
     */
    private ConfigService configService;


    /**
     * 配置变更监听器列表
     * Key: 配置键, Value: 监听器列表
     */
    private final Map<String, List<Listener>> listeners = new ConcurrentHashMap<>();

    /**
     * 从配置中心中获得配置值，没有则返回默认值
     * @param key 键
     * @param defaultValue 默认值
     * @return 对应值的String
     */
    public String getString(String key, String defaultValue) {
        return environment.getProperty(key, defaultValue);
    }

    /**
     * 获取配置值（String 类型，无默认值）
     *
     * @param key 配置键
     * @return 配置值，如果不存在返回 null
     */
    public String getString(String key) {
        return getString(key, null);
    }


    /**
     * 获取配置值（Integer 类型）
     *
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 配置值
     */
    public Integer getInteger(String key, Integer defaultValue) {
        String value = getString(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("配置项 {} 的值 {} 无法转换为 Integer，使用默认值 {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    /**
     * 获取配置值（Long 类型）
     *
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 配置值
     */
    public Long getLong(String key, Long defaultValue) {
        String value = getString(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.warn("配置项 {} 的值 {} 无法转换为 Long，使用默认值 {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    /**
     * 获取配置值（Boolean 类型）
     *
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 配置值
     */
    public Boolean getBoolean(String key, Boolean defaultValue) {
        String value = getString(key);
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    /**
     * 获取配置值（Double 类型）
     *
     * @param key 配置键
     * @param defaultValue 默认值
     * @return 配置值
     */
    public Double getDouble(String key, Double defaultValue) {
        String value = getString(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            log.warn("配置项 {} 的值 {} 无法转换为 Double，使用默认值 {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    /**
     * 注册配置监听
     * @param key 配置键
     * @param dataId dataId
     * @param group 组
     * @param listener 监听器
     */
    public void addListener(String key, String dataId, String group, Listener listener) {
        listeners.computeIfAbsent(key, k -> new ArrayList<>()).add(listener);
        try{
            configService.addListener(dataId, group, listener);
        } catch(NacosException e){

        }
    }




    @Override
    public void onApplicationEvent(EnvironmentChangeEvent event) {

    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

}
