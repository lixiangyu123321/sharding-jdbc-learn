package org.lix.mycatdemo.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.util.Set;

/**
 * ShardingJDBC 动态配置类
 * 支持基于 Nacos 配置中心的动态刷新
 * 
 * 注意：ShardingSphere 4.1.1 对动态配置的支持有限，
 * 某些配置变化（如数据源、分片规则）可能需要重启应用才能生效
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "spring.shardingsphere.enabled", havingValue = "true", matchIfMissing = true)
public class ShardingJdbcDynamicConfig {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    /**
     * 监听环境变化事件
     * 当 Nacos 配置刷新时，会触发此事件
     */
    @EventListener
    public void onEnvironmentChange(EnvironmentChangeEvent event) {
        Set<String> changedKeys = event.getKeys();
        
        // 检查是否有 ShardingSphere 相关配置变化
        boolean shardingConfigChanged = changedKeys.stream()
                .anyMatch(key -> key.startsWith("spring.shardingsphere"));
        
        if (shardingConfigChanged) {
            log.info("检测到 ShardingSphere 配置变化，变化的配置键: {}", changedKeys);
            log.warn("注意：ShardingSphere 4.1.1 对动态配置支持有限");
            log.warn("数据源和分片规则的变化可能需要重启应用才能完全生效");
            
            // 记录当前配置状态
            logCurrentConfig();
        }
    }

    /**
     * 记录当前 ShardingSphere 配置状态
     */
    private void logCurrentConfig() {
        try {
            String datasourceNames = environment.getProperty("spring.shardingsphere.datasource.names");
            String sqlShow = environment.getProperty("spring.shardingsphere.props.sql-show");
            
            log.info("当前 ShardingSphere 配置状态:");
            log.info("  - 数据源名称: {}", datasourceNames);
            log.info("  - SQL 显示: {}", sqlShow);
            
            // 尝试获取数据源信息
            try {
                DataSource dataSource = applicationContext.getBean(DataSource.class);
                if (dataSource != null) {
                    log.info("  - 数据源类型: {}", dataSource.getClass().getName());
                }
            } catch (Exception e) {
                log.warn("无法获取数据源信息: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.error("记录配置状态时发生错误", e);
        }
    }
}

