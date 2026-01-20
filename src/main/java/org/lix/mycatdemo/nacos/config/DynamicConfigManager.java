package org.lix.mycatdemo.nacos.config;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.lix.mycatdemo.parser.ConfigFileTypeEnum;
import org.lix.mycatdemo.parser.ConfigParserHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态管理注册中心
 */
@Slf4j
@Component("myDynamicConfigManager")
public class DynamicConfigManager {

    private DynamicConfigManager() {}

    @Value("${spring.cloud.nacos.config.server-addr}")
    private String serverAddr;

    @Value("${spring.cloud.nacos.config.timeout}")
    private Long timeout;

    @Value("${namespace.dataId}")
    private String namespaceConfig;

    @Value("${namespace.group}")
    private String group;

    /**
     * namespace to configService
     */
    private Map<String, ConfigService> configServiceMap = new ConcurrentHashMap<>();

    /**
     * namespece名称对应Id
     */
    private Map<String, String> namespaceMap = new HashMap<>();


    /**
     * 初始化配置中心命名空间名和Id的映射关系
     */
    @PostConstruct
    public void init(){
        ConfigService configService = getConfigService("");
        try {
            String config = configService.getConfig(namespaceConfig, group, timeout);

            Map<String, Object> properties = ConfigParserHandler.getInstance().parseConfig(config, ConfigFileTypeEnum.of("properties"));
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                namespaceMap.put(entry.getKey(), entry.getValue().toString());
            }
        } catch (NacosException e) {
            // TODO 异常处理
            throw new RuntimeException(e);
        }
    }

    public ConfigService getConfigService(String namespaceId) {
        if (configServiceMap.containsKey(namespaceId)) {
            return configServiceMap.get(namespaceId);
        }

        synchronized (this) {
            if (configServiceMap.containsKey(namespaceId)) {
                return configServiceMap.get(namespaceId);
            }

            // 3. 创建配置
            Properties properties = new Properties();
            properties.put("serverAddr", serverAddr);
            properties.put("namespace", namespaceId);

            try {
                ConfigService configService = NacosFactory.createConfigService(properties);
                configServiceMap.put(namespaceId, configService);
                return configService;
            } catch (NacosException e) {
                log.error("NacosConfigService创建失败, namespace: {}", namespaceId, e);
                throw new RuntimeException("创建Nacos配置服务失败", e);
            }
        }
    }

    /**
     * 注册监听器
     * @param namespaceId 命名空间
     * @param dataId dataId
     * @param group group
     * @param listener 监听器
     */
    public void addListener(String namespaceId, String dataId, String group, Listener listener) {
        ConfigService configService = getConfigService(namespaceId);
        if(configService == null) {
            log.info("监听器注册失败, namespaceId: {}, dataId: {}, group: {}, listener: {}",
                    namespaceId, dataId, group, listener);
            return;
        }
        try {
            configService.addListener(dataId, group, listener);
            log.info("监听器注册成功, namespaceId: {}, dataId: {}, group: {}, listener: {}",
                    namespaceId, dataId, group, listener);
        } catch (NacosException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 获得命名空间Id
     * @param namespace 命名空间名
     * @return 命名空间Id
     */
    public String getNamespaceId(String namespace) {
        if(StringUtils.isBlank(namespace)){
            return "";
        }
        if(namespaceMap.containsKey(namespace)){
            return namespaceMap.get(namespace);
        }
        return "";
    }
}
