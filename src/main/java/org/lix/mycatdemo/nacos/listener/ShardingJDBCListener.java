package org.lix.mycatdemo.nacos.listener;

import com.alibaba.cloud.commons.lang.StringUtils;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.google.common.collect.Sets;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.api.config.masterslave.LoadBalanceStrategyConfiguration;
import org.apache.shardingsphere.api.config.masterslave.MasterSlaveRuleConfiguration;
import org.apache.shardingsphere.api.config.sharding.KeyGeneratorConfiguration;
import org.apache.shardingsphere.api.config.sharding.ShardingRuleConfiguration;
import org.apache.shardingsphere.api.config.sharding.TableRuleConfiguration;
import org.apache.shardingsphere.api.config.sharding.strategy.InlineShardingStrategyConfiguration;
import org.apache.shardingsphere.shardingjdbc.api.ShardingDataSourceFactory;
import org.apache.shardingsphere.shardingjdbc.jdbc.core.datasource.ShardingDataSource;
import org.lix.mycatdemo.nacos.config.DynamicConfigManager;
import org.lix.mycatdemo.nacos.refresher.ShardingJDBCConfigRefresher;
import org.lix.mycatdemo.parser.ConfigFileTypeEnum;
import org.lix.mycatdemo.parser.ConfigParserHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.DefaultSingletonBeanRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * XXX 目前来说只是基于一些固定规则进行相关配置调整，需要全面接入相关配置，不过目前够用了
 */
@Slf4j
@Component
public class ShardingJDBCListener {

    @Resource
    private ShardingJDBCConfigRefresher shardingJDBCConfigRefresher;

    @Resource
    private DynamicConfigManager myDynamicConfigManager;

    @Value("${sharding-JDBC.dataId}")
    private String dataId;

    @Value("${sharding-JDBC.group}")
    private String group;

    //@EventListener
    public void onContextRefreshed(ContextRefreshedEvent event) {
        // 确保只执行一次（避免父子容器重复执行）
        if (event.getApplicationContext().getParent() != null) {
            return;
        }
        addListener();
    }

    public void addListener() {
        try {
            String namespaceId = myDynamicConfigManager.getNamespaceId("public");
            ConfigService configService = myDynamicConfigManager.getConfigService(namespaceId);
            log.info("dataId:{}", dataId);
            log.info("group:{}", group);
            // 注册 Nacos 配置监听器
            configService.addListener(dataId, group, new Listener() {
                @Override
                public void receiveConfigInfo(String configContent) {
                    shardingJDBCConfigRefresher.refresh(configContent);
                }

                @Override
                public Executor getExecutor() {
                    // 自定义单线程池：避免阻塞 Nacos 共享线程池，且保证刷新串行
                    return new ThreadPoolExecutor(
                            1, 1,
                            60L, TimeUnit.SECONDS,
                            new LinkedBlockingQueue<>(10),
                            new ThreadFactory() {
                                @Override
                                public Thread newThread(Runnable r) {
                                    return new Thread(r, "sharding-jdbc-universal-refresh-thread");
                                }
                            },
                            new ThreadPoolExecutor.DiscardOldestPolicy() // 队列满时丢弃最旧任务，避免阻塞
                    );
                }
            });
            log.info("通用 Sharding-JDBC 4.1.1 版本 Nacos 配置监听器注册成功，dataId: {}", dataId);
        } catch (Exception e) {
            log.error("注册通用 Sharding-JDBC 监听器失败", e);
            throw new RuntimeException("注册 Sharding-JDBC 监听器失败", e);
        }
    }
}
