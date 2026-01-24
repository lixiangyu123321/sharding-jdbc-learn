package org.lix.mycatdemo.nacos.service;

import com.alibaba.nacos.api.config.annotation.NacosConfigListener;
import com.alibaba.nacos.api.config.annotation.NacosValue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 注意RefreshScope注解是配合@Value注解使用的
 */
@Slf4j
@Component
@RefreshScope
@ConfigurationProperties(prefix = "myapp")
public class NacosExample {

    @Value("${myapp.config}")
    private String config;

    /**
     * XXX 关于该@NacosValue注解的相关信息
     * 可以与@Value注解混合使用
     * autoRefreshed = true 表示该配置项支持动态刷新
     * 当配置中心的该配置项发生变化时，Spring容器中的该字段会自动更新
     * 可以指定不同数据类型，如String、int、boolean、Map等，这里是基于Environment进行加载的
     */
    @NacosValue(value = "${myapp.local-config}", autoRefreshed = true)
    private Map<String, String> map;

    /**
     * XXX 关于@NacosConfigListener注解的相关信息
     * 该注解用于监听Nacos配置中心中指定dataId和groupId
     * 当对应的配置发生变化时，会触发该方法的调用
     * 方法参数为最新所有配置内容
     * 并且可以通过NacosConfigConverter来指定读取配置的格式
     * 并且可以通过NacosProperties来指定更多的信息
     * @param configContent
     */
    @NacosConfigListener(dataId = "myapp-dynamic-config", groupId = "DEFAULT_GROUP")
    public void test(String configContent){
        log.info("动态配置变更，新的配置内容：{}", configContent);
    }
}
