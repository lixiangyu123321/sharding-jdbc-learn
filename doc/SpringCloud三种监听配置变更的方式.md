```java
package org.lix.mycatdemo.nacos.service;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.endpoint.event.RefreshEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.concurrent.Executor;

@Slf4j
@Service
public class TestConfigService implements ApplicationListener<RefreshEvent> {

    private Environment environment;

    @Autowired(required = false)
    private NacosConfigManager nacosConfigManager;

    private volatile boolean listenerRegistered = false;

    @Autowired
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    /**
     * 使用 @PostConstruct 注册监听器，但添加延迟和重试机制
     * 确保 NacosConfigManager 已经完全初始化
     */
    @PostConstruct
    public void init() {
        // 延迟注册，确保 NacosConfigManager 已初始化
        new Thread(() -> {
            try {
                // 等待 NacosConfigManager 初始化
                Thread.sleep(2000);
                registerNacosListener();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("注册 Nacos 监听器线程被中断", e);
            }
        }).start();
    }

    /**
     * 监听 ContextRefreshedEvent 事件，在应用完全启动后注册监听器
     */
    @EventListener
    public void onContextRefreshed(ContextRefreshedEvent event) {
        // 确保只执行一次（避免父子容器重复执行）
        if (event.getApplicationContext().getParent() != null) {
            return;
        }
        
        if (!listenerRegistered) {
            registerNacosListener();
        }
    }

    @Autowired
    private ConfigService configService;

    /**
     * 注册 Nacos 配置监听器
     */
    private void registerNacosListener() {
        if (nacosConfigManager == null) {
            log.warn("NacosConfigManager 未初始化，无法注册配置监听器");
            return;
        }

        try {
            // 1. 获取Nacos原生ConfigService（底层核心类）
            ConfigService configService = nacosConfigManager.getConfigService();
            if (configService == null) {
                log.warn("ConfigService 为 null，无法注册配置监听器");
                return;
            }

            // 2. 从配置中动态获取 dataId 和 group（与 application.yaml 中的配置保持一致）
            String applicationName = environment.getProperty("spring.application.name", "sharding-jdbc-demo");
            String activeProfile = environment.getProperty("spring.profiles.active", "dev");
            String dataId = applicationName + "-" + activeProfile + ".yaml";
            String group = environment.getProperty("spring.cloud.nacos.config.group", "DEFAULT_GROUP");
            String namespace = environment.getProperty("spring.cloud.nacos.config.namespace", "");

            log.info("开始注册 Nacos 配置监听器 - dataId: {}, group: {}, namespace: {}", dataId, group, namespace);

            // 3. 注册监听器
            configService.addListener(dataId, group, new Listener() {
                /**
                 * 配置变动时触发的核心方法
                 * @param configContent 变动后的完整配置内容
                 */
                @Override
                public void receiveConfigInfo(String configContent) {
                    log.info("===== Nacos 配置发生变动 =====");
                    log.info("dataId: {}, group: {}", dataId, group);
                    log.info("最新配置内容：\n{}", configContent);
                    System.out.println("===== Nacos 配置发生变动 =====");
                    System.out.println("dataId: " + dataId);
                    System.out.println("最新配置内容：\n" + configContent);

                    // 可以在这里添加自定义的配置处理逻辑
                    // 例如：重新加载某些配置、刷新缓存等
                }

                /**
                 * 监听线程池（返回null则使用Nacos默认线程池）
                 */
                @Override
                public Executor getExecutor() {
                    return null;
                }
            });

            listenerRegistered = true;
            log.info("Nacos 监听器注册成功 - dataId: {}, group: {}", dataId, group);
            System.out.println("Nacos 监听器注册成功 - dataId: " + dataId + ", group: " + group);
        } catch (NacosException e) {
            log.error("注册 Nacos 配置监听器失败", e);
            System.err.println("注册 Nacos 配置监听器失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("注册 Nacos 配置监听器时发生未知错误", e);
            System.err.println("注册 Nacos 配置监听器时发生未知错误: " + e.getMessage());
        }
    }

    /**
     * 监听 Spring Cloud 的配置刷新事件
     * 当 Nacos 配置变化时，Spring Cloud 会触发 RefreshEvent
     */
    @Override
    public void onApplicationEvent(RefreshEvent event) {
        log.info("===== Spring Cloud 配置刷新事件触发 =====");
        log.info("刷新时间：{}", event.getTimestamp());
        System.out.println("===== Spring Cloud 配置刷新事件触发 =====");
        System.out.println("刷新时间：" + event.getTimestamp());

        // 获取变动后的最新配置
        if (environment != null) {
            String testKey = environment.getProperty("test.hello", "默认值");
            log.info("最新 test.hello 配置值：{}", testKey);
            System.out.println("最新 test.hello 配置值：" + testKey);
        }
    }

    /**
     * 监听环境变量变化事件（更细粒度的配置变化监听）
     */
    @EventListener
    public void onEnvironmentChange(EnvironmentChangeEvent event) {
        log.info("===== 环境配置发生变化 =====");
        log.info("变化的配置键：{}", event.getKeys());
        System.out.println("===== 环境配置发生变化 =====");
        System.out.println("变化的配置键：" + event.getKeys());
        System.out.println("变化的配置值：" + event.getSource());
    }
}

```