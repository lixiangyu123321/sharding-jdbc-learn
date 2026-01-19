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

@Slf4j
@Component
public class ShardingJDBCListener {

    /**
     * 用于获得不同命名空间的配置
     */
    @Resource
    private DynamicConfigManager myDynamicConfigManager;

    @Value("${sharding-JDBC.dataId}")
    private String dataId;

    @Value("${sharding-JDBC.group}")
    private String group;

    @Resource
    private ApplicationContext applicationContext;

    /**
     * shardingDataSource数据源
     */
    private static final String SHARDING_DATASOURCE_BEAN_NAME = "shardingDataSource";

    /**
     * Sharding-JDBC 配置前缀常量
     */
    private static final String SHARDING_TABLES_PREFIX = "spring.shardingsphere.sharding.tables.";
    private static final String DATA_SOURCES_PREFIX = "spring.shardingsphere.datasource.";
    private static final String MASTER_SLAVE_RULES_PREFIX = "spring.shardingsphere.sharding.master-slave-rules.";

    /**
     * 锁对象：保证数据源刷新线程安全
     */
    private final Object refreshLock = new Object();

    @EventListener
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
                    // 加锁：避免多线程同时刷新数据源
                    synchronized (refreshLock) {
                        DataSource oldDataSource = null;
                        try {
                            log.info("===== 收到 Nacos 配置变化通知 =====");
                            log.info("{}", configContent);
                            log.debug("配置内容长度: {} 字符", configContent != null ? configContent.length() : 0);
                            
                            // 1. 解析配置（使用自定义 YAML 解析器）
                            String extension = dataId.substring(dataId.indexOf(".") + 1);
                            log.info("开始解析配置，dataId: {}, 文件类型: {}", dataId, extension);
                            
                            Map<String, Object> flatConfigMap = ConfigParserHandler.getInstance()
                                    .parseConfig(configContent, ConfigFileTypeEnum.of(extension));

                            log.info("{}", flatConfigMap);
                            log.info("配置解析完成，解析后的配置项数量：{}", flatConfigMap.size());
                            
                            // 打印前10个配置项用于调试
                            if (flatConfigMap.isEmpty()) {
                                log.warn("配置解析结果为空！请检查配置内容是否正确");
                                log.debug("原始配置内容（前500字符）:\n{}", 
                                        configContent != null && configContent.length() > 500 
                                                ? configContent.substring(0, 500) 
                                                : configContent);
                                return; // 配置为空，不进行刷新
                            } else {
                                log.debug("配置项示例（前10个）:");
                                flatConfigMap.entrySet().stream()
                                        .limit(10)
                                        .forEach(entry -> log.debug("  {} = {}", entry.getKey(), entry.getValue()));
                            }

                            // 2. 通用化构建配置：动态识别所有表、数据源
                            ShardingRuleConfiguration shardingRuleConfig = buildUniversalShardingRuleConfig(flatConfigMap);
                            Map<String, DataSource> actualDataSources = buildUniversalDataSources(flatConfigMap);
                            
                            // 检查数据源是否为空
                            if (actualDataSources.isEmpty()) {
                                log.error("数据源构建失败：未识别到任何数据源！");
                                log.error("请检查配置中是否包含 spring.shardingsphere.datasource.names 配置");
                                log.debug("所有配置键: {}", flatConfigMap.keySet());
                                return; // 数据源为空，不进行刷新
                            }

                            // 3. 先保存旧数据源引用（在注册新数据源之前）
                            oldDataSource = getOldShardingDataSource();

                            // 4. 创建新的 ShardingDataSource
                            DataSource newShardingDataSource = createNewShardingDataSource(flatConfigMap, actualDataSources, shardingRuleConfig);
                            
                            // 5. 在注册新数据源之前，先移除旧数据源的 Bean（但不关闭数据源对象）
                            // 这样新数据源才能成功注册
                            removeOldShardingDataSourceBeanBeforeRegister(oldDataSource);
                            
                            // 6. 注册新的 ShardingDataSource 到 Spring 容器（先注册，确保新请求使用新数据源）
                            registerShardingDataSourceBean(newShardingDataSource);
                            
                            // 7. 刷新 MyBatis 相关组件，使其使用新数据源
                            // 关键：MyBatis 的 SqlSessionFactory 和 SqlSessionTemplate 在创建时绑定了数据源引用
                            // 即使数据源 Bean 已更新，这些组件仍可能使用旧引用，需要刷新

                            if(!hasActiveConnections(oldDataSource)) {
                                // XXX 应该在刷新MyBatis配置的时候检查旧数据源是否有活跃连接，如果没有，立即切换并将旧数据源的关闭放入异步任务中处理
                                refreshMyBatisComponents(newShardingDataSource);
                            }

                            // 主动关闭旧数据源
                            log.info("主动关闭旧数据源");
                            scheduleOldDataSourceCleanup(oldDataSource);

                            log.info("通用 Sharding-JDBC 4.1.1 版本数据源刷新成功，包含 {} 个数据源，{} 个分片表",
                                    actualDataSources.size(), shardingRuleConfig.getTableRuleConfigs().size());
                        } catch (Exception e) {
                            log.error("刷新通用 Sharding-JDBC 数据源失败", e);
                            // TODO 这里应该将try-catch块拆开，如果新数据源配置失败，使用旧的数据源
                        }
                    }
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

    /**
     * 通用化构建分片规则配置：动态识别所有表、分片策略
     */
    private ShardingRuleConfiguration buildUniversalShardingRuleConfig(Map<String, Object> flatConfigMap) {
        ShardingRuleConfiguration ruleConfig = new ShardingRuleConfiguration();

        // ========== 步骤1：动态解析所有分片表 ==========
        Set<String> tableNames = extractAllShardingTableNames(flatConfigMap);
        log.info("识别到分片表数量：{}，表名：{}", tableNames.size(), tableNames);

        // ========== 步骤2：为每个表构建分片规则 ==========
        for (String tableName : tableNames) {
            TableRuleConfiguration tableRuleConfig = buildTableRuleConfig(flatConfigMap, tableName);
            if (tableRuleConfig != null) {
                ruleConfig.getTableRuleConfigs().add(tableRuleConfig);
            }
        }

        // ========== 步骤3：动态解析广播表 ==========
        String broadcastTablesStr = getStringConfig(flatConfigMap, "spring.shardingsphere.sharding.broadcast-tables");
        if (StringUtils.isNotBlank(broadcastTablesStr)) {
            String[] broadcastTables = broadcastTablesStr.split(",");
            ruleConfig.getBroadcastTables().addAll(Arrays.asList(broadcastTables));
            log.info("识别到广播表：{}", Arrays.toString(broadcastTables));
        }

        // ========== 步骤4：动态解析绑定表 ==========
        String bindingTablesStr = getStringConfig(flatConfigMap, "spring.shardingsphere.sharding.binding-tables");
        if (StringUtils.isNotBlank(bindingTablesStr)) {
            String[] bindingTables = bindingTablesStr.split(",");
            ruleConfig.getBindingTableGroups().addAll(Arrays.asList(bindingTables));
            log.info("识别到绑定表：{}", Arrays.toString(bindingTables));
        }

        // ========== 步骤5：动态解析默认分片策略（可选） ==========
        buildDefaultShardingStrategy(flatConfigMap, ruleConfig);

        // ========== 步骤6：动态解析续写分离策略 ==========
        buildMasterSlaveRules(flatConfigMap, ruleConfig);

        return ruleConfig;
    }

    /**
     * 配置读写分离规则
     * @param flatConfigMap
     * @param ruleConfig
     */
    private void buildMasterSlaveRules(Map<String, Object> flatConfigMap, ShardingRuleConfiguration ruleConfig) {
        // 步骤1：提取所有主从规则名称（如 ms_0、ms_1）
        Set<String> masterSlaveRuleNames = extractAllMasterSlaveRuleNames(flatConfigMap);
        if (CollectionUtils.isEmpty(masterSlaveRuleNames)) {
            log.info("未识别到读写分离（主从）配置，跳过读写分离规则构建");
            return;
        }
        log.info("识别到读写分离规则数量：{}，规则名称：{}", masterSlaveRuleNames.size(), masterSlaveRuleNames);

        // 步骤2：为每个主从规则构建配置
        for (String msRuleName : masterSlaveRuleNames) {
            MasterSlaveRuleConfiguration msConfig = buildMasterSlaveRuleConfig(flatConfigMap, msRuleName);
            if (msConfig != null) {
                ruleConfig.getMasterSlaveRuleConfigs().add(msConfig);
                log.info("成功构建读写分离规则：{}，主库：{}，从库：{}",
                        msRuleName, msConfig.getMasterDataSourceName(), msConfig.getSlaveDataSourceNames());
            }
        }
    }

    /**
     * 新增：构建单个读写分离（主从）规则配置
     * 适配 Sharding-JDBC 4.1.1 配置结构：
     * spring.shardingsphere.sharding.master-slave-rules.ms_0.master-data-source-name=ds_0_master
     * spring.shardingsphere.sharding.master-slave-rules.ms_0.slave-data-source-names=ds_0_slave_0,ds_0_slave_1
     * spring.shardingsphere.sharding.master-slave-rules.ms_0.load-balance-algorithm-type=ROUND_ROBIN
     */
    private MasterSlaveRuleConfiguration buildMasterSlaveRuleConfig(Map<String, Object> flatConfigMap, String msRuleName) {
        String rulePrefix = MASTER_SLAVE_RULES_PREFIX + msRuleName + ".";

        // 1. 读取主库名称（必选）
        String masterDsName = getStringConfig(flatConfigMap, rulePrefix + "master-data-source-name");
        if (StringUtils.isBlank(masterDsName)) {
            log.warn("主从规则 {} 未配置主库名称（{}master-data-source-name），跳过", msRuleName, rulePrefix);
            return null;
        }

        // 2. 读取从库名称列表（必选，多个用逗号分隔）
        String slaveDsNamesStr = getStringConfig(flatConfigMap, rulePrefix + "slave-data-source-names");
        if (StringUtils.isBlank(slaveDsNamesStr)) {
            log.warn("主从规则 {} 未配置从库名称（{}slave-data-source-names），跳过", msRuleName, rulePrefix);
            return null;
        }
        List<String> slaveDsNames = Arrays.stream(slaveDsNamesStr.split(",")).map(String::trim).collect(Collectors.toList());

        if (CollectionUtils.isEmpty(slaveDsNames)) {
            log.warn("主从规则 {} 的从库名称列表为空，跳过", msRuleName);
            return null;
        }

        // 3. 读取负载均衡策略（可选，默认 ROUND_ROBIN）
        String loadBalanceType = getStringConfig(flatConfigMap, rulePrefix + "load-balance-algorithm-type");
        if (StringUtils.isBlank(loadBalanceType)) {
            loadBalanceType = "round_robin";
            log.info("主从规则 {} 未配置负载均衡策略，使用默认值：{}", msRuleName, loadBalanceType);
        }

        // 4. 构建主从规则配置（4.1.1 核心类）
        MasterSlaveRuleConfiguration msConfig = new MasterSlaveRuleConfiguration(msRuleName,
                masterDsName, slaveDsNames, new LoadBalanceStrategyConfiguration(loadBalanceType));

        return msConfig;
    }

    /**
     * 提取所有主从规则名称（从 spring.shardingsphere.sharding.master-slave-rules.xxxx.master-data-source-name 中识别）
     */
    private Set<String> extractAllMasterSlaveRuleNames(Map<String, Object> flatConfigMap) {
        Set<String> msRuleNames = new HashSet<>();
        for (String key : flatConfigMap.keySet()) {
            if (key.startsWith(MASTER_SLAVE_RULES_PREFIX) && key.endsWith(".master-data-source-name")) {
                // 示例：key = sharding.master-slave-rules.ms_0.master-data-source-name → 提取 ms_0
                String msRuleName = key.substring(MASTER_SLAVE_RULES_PREFIX.length(), key.indexOf(".master-data-source-name"));
                msRuleNames.add(msRuleName);
            }
        }
        return msRuleNames;
    }

    /**
     * 提取所有分片表名：从 flatConfigMap 中识别 sharding.tables.xxxx 下的所有表
     */
    private Set<String> extractAllShardingTableNames(Map<String, Object> flatConfigMap) {
        Set<String> tableNames = new HashSet<>();
        for (String key : flatConfigMap.keySet()) {
            if (key.startsWith(SHARDING_TABLES_PREFIX) && key.contains(".actual-data-nodes")) {
                // 示例：key = sharding.tables.t_order.actual-data-nodes → 提取 t_order
                String tableName = key.substring(SHARDING_TABLES_PREFIX.length(), key.indexOf(".actual-data-nodes"));
                tableNames.add(tableName);
            }
        }
        return tableNames;
    }

    /**
     * XXX 分片规则，分库规则，主键生成策略
     * 为单个表构建分片规则：动态读取 actual-data-nodes、分库/分表策略
     */
    private TableRuleConfiguration buildTableRuleConfig(Map<String, Object> flatConfigMap, String tableName) {
        // 1. 读取 actual-data-nodes（必选）
        String actualDataNodesKey = SHARDING_TABLES_PREFIX + tableName + ".actual-data-nodes";
        // XXX 分片规则
        String actualDataNodes = getStringConfig(flatConfigMap, actualDataNodesKey);
        if (StringUtils.isBlank(actualDataNodes)) {
            log.warn("表 {} 未配置 actual-data-nodes，跳过该表分片规则构建", tableName);
            return null;
        }

        TableRuleConfiguration tableRuleConfig = new TableRuleConfiguration(tableName, actualDataNodes);

        // 2. 动态读取分库策略（可选）
        // TODO 这里分库规则不止inline吗
        String dbShardingColumnKey = SHARDING_TABLES_PREFIX + tableName + ".database-strategy.inline.sharding-column";
        String dbAlgorithmExprKey = SHARDING_TABLES_PREFIX + tableName + ".database-strategy.inline.algorithm-expression";
        String dbShardingColumn = getStringConfig(flatConfigMap, dbShardingColumnKey);
        String dbAlgorithmExpr = getStringConfig(flatConfigMap, dbAlgorithmExprKey);
        if (StringUtils.isNotBlank(dbShardingColumn) && StringUtils.isNotBlank(dbAlgorithmExpr)) {
            tableRuleConfig.setDatabaseShardingStrategyConfig(
                    new InlineShardingStrategyConfiguration(dbShardingColumn, dbAlgorithmExpr)
            );
            log.info("表 {} 分库策略：分片列={}，算法表达式={}", tableName, dbShardingColumn, dbAlgorithmExpr);
        }

        // 3. 动态读取分表策略（可选）
        // TODO 这里分库规则不止inline吗
        String tableShardingColumnKey = SHARDING_TABLES_PREFIX + tableName + ".table-strategy.inline.sharding-column";
        String tableAlgorithmExprKey = SHARDING_TABLES_PREFIX + tableName + ".table-strategy.inline.algorithm-expression";
        String tableShardingColumn = getStringConfig(flatConfigMap, tableShardingColumnKey);
        String tableAlgorithmExpr = getStringConfig(flatConfigMap, tableAlgorithmExprKey);
        if (StringUtils.isNotBlank(tableShardingColumn) && StringUtils.isNotBlank(tableAlgorithmExpr)) {
            tableRuleConfig.setTableShardingStrategyConfig(
                    new InlineShardingStrategyConfiguration(tableShardingColumn, tableAlgorithmExpr)
            );
            log.info("表 {} 分表策略：分片列={}，算法表达式={}", tableName, tableShardingColumn, tableAlgorithmExpr);
        }

        // 4. 可扩展：动态读取主键生成策略、行表达式替换等（按需添加）
        String keyGeneratorColumn = getStringConfig(flatConfigMap, SHARDING_TABLES_PREFIX + tableName + ".key-generator.column");
        String keyGeneratorType = getStringConfig(flatConfigMap, SHARDING_TABLES_PREFIX + tableName + ".key-generator.type");
        if (StringUtils.isNotBlank(keyGeneratorColumn) && StringUtils.isNotBlank(keyGeneratorType)) {
            KeyGeneratorConfiguration keyGeneratorConfig = new KeyGeneratorConfiguration(keyGeneratorType, keyGeneratorColumn);
            tableRuleConfig.setKeyGeneratorConfig(keyGeneratorConfig);
            log.info("表 {} 主键生成策略：列={}，类型={}", tableName, keyGeneratorColumn, keyGeneratorType);
        }

        return tableRuleConfig;
    }

    /**
     * 构建默认分片策略（全局）：适配 sharding.default-database-strategy 等配置
     */
    private void buildDefaultShardingStrategy(Map<String, Object> flatConfigMap, ShardingRuleConfiguration ruleConfig) {
        // 默认分库策略
        String defaultDbColumn = getStringConfig(flatConfigMap, "spring.shardingsphere.sharding.default-database-strategy.inline.sharding-column");
        String defaultDbExpr = getStringConfig(flatConfigMap, "spring.shardingsphere.sharding.default-table-strategy.inline.algorithm-expression");
        if (StringUtils.isNotBlank(defaultDbColumn) && StringUtils.isNotBlank(defaultDbExpr)) {
            ruleConfig.setDefaultDatabaseShardingStrategyConfig(
                    new InlineShardingStrategyConfiguration(defaultDbColumn, defaultDbExpr)
            );
            log.info("默认分库策略：分片列={}，算法表达式={}", defaultDbColumn, defaultDbExpr);
        }

        // 默认分表策略
        String defaultTableColumn = getStringConfig(flatConfigMap, "spring.shardingsphere.sharding.default-table-strategy.inline.sharding-column");
        String defaultTableExpr = getStringConfig(flatConfigMap, "spring.shardingsphere.sharding.default-table-strategy.inline.algorithm-expression");
        if (StringUtils.isNotBlank(defaultTableColumn) && StringUtils.isNotBlank(defaultTableExpr)) {
            ruleConfig.setDefaultTableShardingStrategyConfig(
                    new InlineShardingStrategyConfiguration(defaultTableColumn, defaultTableExpr)
            );
            log.info("默认分表策略：分片列={}，算法表达式={}", defaultTableColumn, defaultTableExpr);
        }
    }

    /**
     * 通用化构建数据源：动态识别所有 dataSources 下的数据源（ds_0/ds_1/ds_2...）
     */
    private Map<String, DataSource> buildUniversalDataSources(Map<String, Object> flatConfigMap) {
        Map<String, DataSource> dataSourceMap = new HashMap<>();

        // 步骤1：提取所有数据源名称（如 ds_0、ds_1、master、slave 等）
        Set<String> dsNames = extractAllDataSourceNames(flatConfigMap);
        log.info("识别到数据源数量：{}，数据源名称：{}", dsNames.size(), dsNames);

        // 步骤2：为每个数据源构建连接池
        for (String dsName : dsNames) {
            try {
                DataSource ds = createBasicDataSource(flatConfigMap, dsName);
                dataSourceMap.put(dsName, ds);
                log.info("数据源 {} 构建成功，URL：{}", dsName, flatConfigMap.get(DATA_SOURCES_PREFIX + dsName + ".url"));
            } catch (Exception e) {
                log.error("构建数据源 {} 失败", dsName, e);
                throw new RuntimeException("构建数据源 " + dsName + " 失败", e);
            }
        }

        return dataSourceMap;
    }

    /**
     * 提取所有数据源名称：从 spring.shardingsphere.datasource.names 获得所有数据源信息
     */
    private Set<String> extractAllDataSourceNames(Map<String, Object> flatConfigMap) {
        String namesKey = DATA_SOURCES_PREFIX + "names";
        String names = getStringConfig(flatConfigMap, namesKey);
        
        log.debug("查找数据源名称配置，key: {}, value: {}", namesKey, names);
        
        if (StringUtils.isBlank(names)) {
            log.warn("未找到数据源名称配置（key: {}），请检查配置", namesKey);
            // 尝试查找所有包含 datasource 的配置键用于调试
            Set<String> datasourceKeys = flatConfigMap.keySet().stream()
                    .filter(key -> key.toLowerCase().contains("datasource"))
                    .collect(Collectors.toSet());
            if (!datasourceKeys.isEmpty()) {
                log.debug("找到包含 'datasource' 的配置键: {}", datasourceKeys);
            }
            return Sets.newHashSet();
        }
        
        Set<String> dsNames = Arrays.stream(names.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        
        log.info("提取到数据源名称: {}", dsNames);
        return dsNames;
    }

    /**
     * 通用化创建基础数据源：适配任意数据源名称，动态读取连接池配置
     */
    private DataSource createBasicDataSource(Map<String, Object> flatConfigMap, String dsName) {
        HikariConfig hikariConfig = new HikariConfig();

        // 1. 基础连接配置（必选）
        String urlKey = DATA_SOURCES_PREFIX + dsName + ".jdbc-url";
        String usernameKey = DATA_SOURCES_PREFIX + dsName + ".username";
        String passwordKey = DATA_SOURCES_PREFIX + dsName + ".password";
        String driverKey = DATA_SOURCES_PREFIX + dsName + ".driver-class-name";

        // 使用安全的类型转换，将配置值转换为 String
        String url = getStringConfig(flatConfigMap, urlKey);
        String username = getStringConfig(flatConfigMap, usernameKey);
        String password = getStringConfig(flatConfigMap, passwordKey);
        String driverClassName = getStringConfig(flatConfigMap, driverKey);

        if (StringUtils.isBlank(url)) {
            throw new IllegalArgumentException("数据源 " + dsName + " 未配置 url（key：" + urlKey + "）");
        }
        hikariConfig.setJdbcUrl(url);
        hikariConfig.setUsername(StringUtils.isBlank(username) ? "" : username);
        hikariConfig.setPassword(StringUtils.isBlank(password) ? "" : password);
        hikariConfig.setDriverClassName(StringUtils.isBlank(driverClassName) ? "com.mysql.cj.jdbc.Driver" : driverClassName);

        // 2. 设置唯一的连接池名称，避免新旧数据源冲突
        // 使用时间戳确保每次刷新时创建的数据源都有唯一名称
        String uniquePoolName = dsName + "-" + System.currentTimeMillis();
        hikariConfig.setPoolName(uniquePoolName);
        log.debug("数据源 {} 使用连接池名称: {}", dsName, uniquePoolName);

        // 3. 连接池配置（可选，无则用默认值）
        setPoolConfig(flatConfigMap, dsName, hikariConfig);

        return new HikariDataSource(hikariConfig);
    }

    /**
     * 通用化设置连接池参数：动态读取配置，兼容任意连接池参数
     */
    private void setPoolConfig(Map<String, Object> flatConfigMap, String dsName, HikariConfig hikariConfig) {
        String poolPrefix = DATA_SOURCES_PREFIX + dsName + ".hikari.";

        // 通用连接池参数：支持自定义配置，无则用默认值
        Integer maxPoolSize = getIntConfig(flatConfigMap, poolPrefix + "maximum-pool-size", 10);
        Integer minIdle = getIntConfig(flatConfigMap, poolPrefix + "minimum-idle", 2);
        Long connectionTimeout = getLongConfig(flatConfigMap, poolPrefix + "connection-timeout", 30000L);
        Long idleTimeout = getLongConfig(flatConfigMap, poolPrefix + "idle-timeout", 600000L);
        Long maxLifetime = getLongConfig(flatConfigMap, poolPrefix + "max-lifetime", 1800000L);

        hikariConfig.setMaximumPoolSize(maxPoolSize);
        hikariConfig.setMinimumIdle(minIdle);
        hikariConfig.setConnectionTimeout(connectionTimeout);
        hikariConfig.setIdleTimeout(idleTimeout);
        hikariConfig.setMaxLifetime(maxLifetime);
    }

    /**
     * 通用化读取 String 类型配置：避免类型转换异常
     */
    private String getStringConfig(Map<String, Object> map, String key) {
        return getStringConfig(map, key, null);
    }

    /**
     * 通用化读取 String 类型配置：避免类型转换异常
     */
    private String getStringConfig(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        // 安全转换为字符串：无论原始类型是什么，都转换为字符串
        return value.toString();
    }

    /**
     * 通用化读取 Integer 类型配置：避免类型转换异常
     */
    private Integer getIntConfig(Map<String, Object> map, String key, Integer defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            // 如果已经是 Integer，直接返回
            if (value instanceof Integer) {
                return (Integer) value;
            }
            // 否则转换为字符串后解析
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            log.warn("配置项 {} 的值 {} 不是有效整数，使用默认值 {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    /**
     * 通用化读取 Long 类型配置：避免类型转换异常
     */
    private Long getLongConfig(Map<String, Object> map, String key, Long defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            log.warn("配置项 {} 的值 {} 不是有效长整数，使用默认值 {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    /**
     * 获取旧的数据源引用（在注册新数据源之前调用）
     */
    private DataSource getOldShardingDataSource() {
        if (!(applicationContext instanceof ConfigurableApplicationContext)) {
            return null;
        }

        ConfigurableApplicationContext configurableContext = (ConfigurableApplicationContext) applicationContext;
        DefaultListableBeanFactory beanFactory = (DefaultListableBeanFactory) configurableContext.getBeanFactory();

        if (beanFactory.containsBean(SHARDING_DATASOURCE_BEAN_NAME)) {
            try {
                return beanFactory.getBean(SHARDING_DATASOURCE_BEAN_NAME, DataSource.class);
            } catch (Exception e) {
                log.debug("获取旧数据源 Bean 失败: {}", e.getMessage());
                return null;
            }
        }
        return null;
    }

    /**
     * 调度关闭旧数据源：延迟关闭，等待正在进行的操作完成
     * 
     * 注意：为了安全起见，我们采用更保守的策略：
     * 1. 不立即关闭旧数据源，避免影响正在进行的操作
     * 2. 等待足够长的时间（30秒），让所有正在进行的操作完成
     * 3. 检查数据源是否还有活跃连接，如果没有再关闭
     * 4. 如果数据源已经被关闭（可能被其他地方关闭），则跳过
     */
    private void scheduleOldDataSourceCleanup(DataSource oldDataSource) {
        if (oldDataSource == null) {
            log.debug("没有旧数据源需要关闭");
            return;
        }

        // 使用后台线程延迟关闭旧数据源，避免影响正在进行的操作
        new Thread(() -> {
            try {
                // 等待30秒，让所有正在进行的操作完成（包括事务等长时间操作）
                log.info("延迟关闭旧数据源，等待5秒让所有正在进行的操作完成...");
                Thread.sleep(5000);
                
                // 检查数据源是否已经被关闭
                if (isDataSourceClosed(oldDataSource)) {
                    log.info("旧数据源已经被关闭，跳过关闭操作");
                    return;
                }
                
                // 检查是否还有活跃连接（对于 HikariCP）
                if (hasActiveConnections(oldDataSource)) {
                    log.warn("旧数据源仍有活跃连接，继续等待...");
                    // 再等待30秒
                    Thread.sleep(5000);
                }
                
                // 再次检查是否已经被关闭
                if (isDataSourceClosed(oldDataSource)) {
                    log.info("旧数据源已经被关闭，跳过关闭操作");
                    return;
                }
                
                // 关闭旧数据源
                closeDataSource(oldDataSource);
                
                // XXX 二次移除
                removeOldShardingDataSourceBean();
                
                log.info("旧数据源已成功关闭");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("关闭旧数据源线程被中断", e);
            } catch (Exception e) {
                log.error("延迟关闭旧数据源失败", e);
            }
        }, "sharding-datasource-cleanup-thread").start();
    }

    /**
     * 检查数据源是否已经被关闭
     */
    private boolean isDataSourceClosed(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource) {
            return ((HikariDataSource) dataSource).isClosed();
        } else if (dataSource instanceof ShardingDataSource) {
            // 对于 ShardingDataSource，检查其内部数据源是否都已关闭
            try {
                ShardingDataSource shardingDs = (ShardingDataSource) dataSource;
                Map<String, DataSource> dataSourceMap = shardingDs.getDataSourceMap();
                // ShardingDataSource 的 所有管理的DataSource都被关闭
                if (dataSourceMap != null) {
                    for (DataSource ds : dataSourceMap.values()) {
                        if (ds instanceof HikariDataSource && !((HikariDataSource) ds).isClosed()) {
                            return false;
                        }
                        // TODO 扩展其他数据库连接池
                        if(ds instanceof DruidDataSource && !((DruidDataSource) ds).isClosed()){
                            return false;
                        }
                    }
                }
                return true;
            } catch (Exception e) {
                log.debug("检查 ShardingDataSource 关闭状态失败: {}", e.getMessage());
                return false;
            }
        }
        return false;
    }

    /**
     * 检查数据源是否还有活跃连接
     */
    private boolean hasActiveConnections(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource) {
            HikariDataSource hikariDs = (HikariDataSource) dataSource;
            if (hikariDs.isClosed()) {
                return false;
            }
            try {
                // 获取活跃连接数
                int activeConnections = hikariDs.getHikariPoolMXBean().getActiveConnections();
                log.debug("数据源 {} 当前活跃连接数: {}", hikariDs.getPoolName(), activeConnections);
                return activeConnections > 0;
            } catch (Exception e) {
                log.debug("获取活跃连接数失败: {}", e.getMessage());
                return false;
            }
        } else if (dataSource instanceof ShardingDataSource) {
            // 对于 ShardingDataSource，检查其内部所有数据源
            try {
                ShardingDataSource shardingDs = (ShardingDataSource) dataSource;
                Map<String, DataSource> dataSourceMap = shardingDs.getDataSourceMap();
                if (dataSourceMap != null) {
                    for (DataSource ds : dataSourceMap.values()) {
                        if (hasActiveConnections(ds)) {
                            return true;
                        }
                    }
                }
                return false;
            } catch (Exception e) {
                log.debug("检查 ShardingDataSource 活跃连接失败: {}", e.getMessage());
                return false;
            }
        }
        return false;
    }

    /**
     * 在注册新数据源之前，移除旧的 ShardingDataSource Bean（移除 Bean 定义和单例注册，但不关闭数据源对象）
     * 这个方法在注册新数据源之前调用，确保新数据源可以成功注册
     */
    private void removeOldShardingDataSourceBeanBeforeRegister(DataSource oldDataSource) {
        if (oldDataSource == null) {
            log.debug("没有旧数据源需要移除");
            return;
        }
        
        if (!(applicationContext instanceof ConfigurableApplicationContext)) {
            log.warn("ApplicationContext 不是 ConfigurableApplicationContext 类型，无法移除旧数据源 Bean");
            return;
        }

        ConfigurableApplicationContext configurableContext = (ConfigurableApplicationContext) applicationContext;
        DefaultListableBeanFactory beanFactory = (DefaultListableBeanFactory) configurableContext.getBeanFactory();

        try {
            // 1. 移除 Bean 定义（如果存在）
            if (beanFactory.containsBeanDefinition(SHARDING_DATASOURCE_BEAN_NAME)) {
                beanFactory.removeBeanDefinition(SHARDING_DATASOURCE_BEAN_NAME);
                log.debug("已移除旧数据源 Bean 定义");
            }
            
            // 2. 移除单例注册（如果存在），这样新数据源才能成功注册
            // 注意：这里只是从容器中移除引用，数据源对象本身不会被关闭
            if (beanFactory.containsSingleton(SHARDING_DATASOURCE_BEAN_NAME)) {
                // 使用反射直接操作单例注册表，避免触发 destroySingleton 的销毁回调
                // destroySingleton 会触发 Bean 的销毁回调，导致数据源被关闭
                try {
                    // 使用反射直接移除单例，避免触发销毁回调
                    Method removeSingletonMethod =
                        DefaultSingletonBeanRegistry.class.getDeclaredMethod("removeSingleton", String.class);
                    removeSingletonMethod.setAccessible(true);
                    removeSingletonMethod.invoke(beanFactory, SHARDING_DATASOURCE_BEAN_NAME);
                    log.debug("已移除旧数据源单例 Bean 注册（使用反射，避免触发销毁回调）");
                } catch (Exception e) {
                    // 如果反射失败，不使用 destroySingleton（避免触发销毁回调）
                    log.error("使用反射移除单例失败，无法安全移除旧 Bean: {}", e.getMessage());
                    log.warn("旧 Bean 仍存在于容器中，新 Bean 可能无法注册。建议检查反射调用是否成功。");
                    // 不再回退到 destroySingleton，避免触发数据源关闭
                    // 如果反射真的失败，可能需要手动处理 Bean 冲突
                    throw new RuntimeException("无法安全移除旧数据源 Bean，反射调用失败: " + e.getMessage(), e);
                }
            }
            
            log.info("旧数据源 Bean 已从 Spring 容器中移除（数据源对象未关闭，将在后续延迟关闭）");
        } catch (Exception e) {
            log.warn("移除旧数据源 Bean 失败: {}", e.getMessage());
            // 不抛出异常，继续执行
        }
    }

    /**
     * 移除旧的 ShardingDataSource Bean（仅移除 Bean 定义，不关闭数据源）
     * 这个方法在延迟关闭线程中调用，用于清理可能残留的 Bean 定义
     */
    private void removeOldShardingDataSourceBean() {
        if (!(applicationContext instanceof ConfigurableApplicationContext)) {
            return;
        }

        ConfigurableApplicationContext configurableContext = (ConfigurableApplicationContext) applicationContext;
        DefaultListableBeanFactory beanFactory = (DefaultListableBeanFactory) configurableContext.getBeanFactory();

        try {
            // 尝试移除 Bean 定义（如果还存在）
            if (beanFactory.containsBeanDefinition(SHARDING_DATASOURCE_BEAN_NAME)) {
                beanFactory.removeBeanDefinition(SHARDING_DATASOURCE_BEAN_NAME);
                log.debug("已移除旧数据源 Bean 定义");
            }
            
            // 注意：不在这里销毁单例 Bean，因为新数据源可能已经使用相同的名称注册
        } catch (Exception e) {
            log.debug("移除旧数据源 Bean 定义失败: {}", e.getMessage());
        }
    }

    /**
     * 销毁旧的 ShardingDataSource Bean：通用化逻辑，兼容任意数据源类型
     * 注意：此方法应该在注册新数据源后调用，并考虑延迟执行以避免影响正在进行的操作
     */
    private void destroyOldShardingDataSource() {
        if (!(applicationContext instanceof ConfigurableApplicationContext)) {
            log.warn("ApplicationContext 不是 ConfigurableApplicationContext 类型，跳过旧数据源销毁");
            return;
        }

        ConfigurableApplicationContext configurableContext = (ConfigurableApplicationContext) applicationContext;
        DefaultListableBeanFactory beanFactory = (DefaultListableBeanFactory) configurableContext.getBeanFactory();

        if (beanFactory.containsBean(SHARDING_DATASOURCE_BEAN_NAME)) {
            try {
                // 先移除 Bean 定义，避免在关闭数据源时触发 Bean 创建
                if (beanFactory.containsBeanDefinition(SHARDING_DATASOURCE_BEAN_NAME)) {
                    beanFactory.removeBeanDefinition(SHARDING_DATASOURCE_BEAN_NAME);
                }
                
                // 尝试获取并关闭数据源（如果 Bean 还存在）
                try {
                    DataSource oldDataSource = beanFactory.getBean(SHARDING_DATASOURCE_BEAN_NAME, DataSource.class);
                    if (oldDataSource != null) {
                        // 通用化关闭数据源：兼容 ShardingDataSource/普通 DataSource
                        closeDataSource(oldDataSource);
                    }
                } catch (Exception e) {
                    // Bean 可能已经被销毁或关闭，忽略此错误
                    log.debug("获取旧数据源 Bean 失败（可能已被销毁）: {}", e.getMessage());
                }
                
                // 移除单例 Bean
                if (beanFactory.containsSingleton(SHARDING_DATASOURCE_BEAN_NAME)) {
                    beanFactory.destroySingleton(SHARDING_DATASOURCE_BEAN_NAME);
                }
                
                log.info("旧的 ShardingDataSource Bean 已销毁");
            } catch (Exception e) {
                log.error("销毁旧 ShardingDataSource Bean 失败", e);
                // 不抛出异常，继续执行后续逻辑
            }
        }
    }

    /**
     * 通用化关闭数据源：递归关闭 ShardingDataSource 内的所有实际数据源
     */
    private void closeDataSource(DataSource dataSource) {
        if (dataSource == null) {
            return;
        }
        
        try {
            if (dataSource instanceof HikariDataSource) {
                HikariDataSource hikariDs = (HikariDataSource) dataSource;
                // 检查数据源是否已经关闭
                if (!hikariDs.isClosed()) {
                    hikariDs.close();
                    log.debug("关闭 Hikari 数据源：{}", dataSource);
                } else {
                    log.debug("Hikari 数据源已经关闭，跳过");
                }
            } else if (dataSource instanceof ShardingDataSource) {
                ShardingDataSource shardingDs = (ShardingDataSource) dataSource;
                // 递归关闭所有实际数据源
                try {
                    Map<String, DataSource> dataSourceMap = shardingDs.getDataSourceMap();
                    if (dataSourceMap != null) {
                        dataSourceMap.values().forEach(this::closeDataSource);
                    }
                } catch (Exception e) {
                    log.warn("获取 ShardingDataSource 子数据源失败: {}", e.getMessage());
                }
                log.debug("关闭 ShardingDataSource 及所有子数据源");
            } else {
                log.warn("不支持的数据源类型 {}，无法自动关闭", dataSource.getClass().getName());
            }
        } catch (Exception e) {
            log.warn("关闭数据源时发生错误: {}", e.getMessage());
            // 不抛出异常，继续执行
        }
    }

    /**
     * 创建新的 ShardingDataSource：通用化逻辑，兼容任意分片规则
     */
    private DataSource createNewShardingDataSource(Map<String, Object> flatConfigMap, Map<String, DataSource> actualDataSources,
                                                   ShardingRuleConfiguration shardingRuleConfig) throws Exception {
        // 检查数据源是否为空
        if (actualDataSources == null || actualDataSources.isEmpty()) {
            throw new IllegalArgumentException("数据源不能为空，无法创建 ShardingDataSource");
        }
        
        // 检查分片规则配置
        if (shardingRuleConfig == null) {
            throw new IllegalArgumentException("分片规则配置不能为空");
        }
        
        log.info("开始创建 ShardingDataSource，数据源数量: {}, 分片表数量: {}", 
                actualDataSources.size(), shardingRuleConfig.getTableRuleConfigs().size());
        
        // 通用化属性配置：支持从 flatConfigMap 读取全局属性（如 sql.show）
        Properties props = new Properties();
        String sqlShow = getStringConfig(flatConfigMap, "spring.shardingsphere.props.sql-show");
        if (StringUtils.isNotBlank(sqlShow)) {
            props.setProperty("sql.show", sqlShow);
            log.debug("设置 sql.show 属性: {}", sqlShow);
        }
        // 可扩展：添加更多全局属性（如 executor.size 等）

        DataSource shardingDataSource = ShardingDataSourceFactory.createDataSource(actualDataSources, shardingRuleConfig, props);
        log.info("ShardingDataSource 创建成功");
        return shardingDataSource;
    }

    /**
     * 注册新的 ShardingDataSource 到 Spring 容器：通用化逻辑
     */
    private void registerShardingDataSourceBean(DataSource newShardingDataSource) {
        if (!(applicationContext instanceof ConfigurableApplicationContext)) {
            log.error("ApplicationContext 不是 ConfigurableApplicationContext 类型，无法注册新数据源");
            throw new RuntimeException("无法获取 ConfigurableApplicationContext，注册数据源失败");
        }

        ConfigurableApplicationContext configurableContext = (ConfigurableApplicationContext) applicationContext;
        DefaultListableBeanFactory beanFactory = (DefaultListableBeanFactory) configurableContext.getBeanFactory();

        // 注册单例 Bean：覆盖旧 Bean
        beanFactory.registerSingleton(SHARDING_DATASOURCE_BEAN_NAME, newShardingDataSource);
        log.info("新的通用 ShardingDataSource Bean 已注册，包含 {} 个实际数据源",
                ((org.apache.shardingsphere.shardingjdbc.jdbc.core.datasource.ShardingDataSource) newShardingDataSource)
                        .getDataSourceMap().size());
    }

    /**
     * 刷新 MyBatis 相关组件，使其使用新的数据源
     * 
     * 原因：MyBatis 的 SqlSessionFactory 和 SqlSessionTemplate 在初始化时绑定了数据源引用
     * 即使替换了 Spring 容器中的数据源 Bean，这些组件仍可能使用旧引用，导致分片策略不生效
     * 
     * 解决方案：
     * 1. 尝试使用反射更新 SqlSessionFactory 中的数据源引用
     * 2. 或者使用 RefreshScope 机制（需要额外配置）
     * 3. 或者重新创建 SqlSessionFactory 和 SqlSessionTemplate（最可靠但最复杂）
     */
    private void refreshMyBatisComponents(DataSource newDataSource) {
        try {
            // 尝试获取 SqlSessionFactory Bean
            try {
                org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory = 
                    applicationContext.getBean(org.apache.ibatis.session.SqlSessionFactory.class);
                
                if (sqlSessionFactory != null) {
                    // 使用反射更新 SqlSessionFactory 中的数据源引用
                    try {
                        java.lang.reflect.Field dataSourceField = 
                            org.apache.ibatis.session.SqlSessionFactory.class.getDeclaredField("dataSource");
                        dataSourceField.setAccessible(true);
                        dataSourceField.set(sqlSessionFactory, newDataSource);
                        log.info("SqlSessionFactory 数据源引用已更新");
                    } catch (NoSuchFieldException e) {
                        // SqlSessionFactory 可能不是直接持有数据源引用
                        // MyBatis 的 DefaultSqlSessionFactory 使用 Configuration 持有数据源
                        log.debug("SqlSessionFactory 没有直接的 dataSource 字段: {}", e.getMessage());
                        // 尝试更新 Configuration 中的数据源
                        updateSqlSessionFactoryDataSource(sqlSessionFactory, newDataSource);
                    } catch (Exception e) {
                        log.warn("更新 SqlSessionFactory 数据源引用失败: {}", e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.debug("未找到 SqlSessionFactory Bean 或无法更新: {}", e.getMessage());
            }

            // 尝试获取 SqlSessionTemplate Bean
            try {
                org.mybatis.spring.SqlSessionTemplate sqlSessionTemplate = 
                    applicationContext.getBean(org.mybatis.spring.SqlSessionTemplate.class);
                
                if (sqlSessionTemplate != null) {
                    // SqlSessionTemplate 内部持有 SqlSessionFactory 引用
                    // 如果 SqlSessionFactory 已更新，SqlSessionTemplate 会自动使用新数据源
                    // 但为了安全起见，我们可以检查是否需要刷新
                    log.debug("SqlSessionTemplate 已检查（依赖 SqlSessionFactory）");
                }
            } catch (Exception e) {
                log.debug("未找到 SqlSessionTemplate Bean: {}", e.getMessage());
            }

            log.info("MyBatis 组件刷新完成（数据源引用已更新）");
        } catch (Exception e) {
            log.warn("刷新 MyBatis 组件时发生错误: {}", e.getMessage());
            // 不抛出异常，避免影响数据源注册
        }
    }

    /**
     * 更新 SqlSessionFactory 中的数据源引用（通过 Configuration）
     */
    private void updateSqlSessionFactoryDataSource(org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory, DataSource newDataSource) {
        try {
            // 获取 SqlSessionFactory 的 Configuration
            org.apache.ibatis.session.Configuration configuration = sqlSessionFactory.getConfiguration();
            
            // 尝试通过反射更新 Environment 中的数据源
            org.apache.ibatis.mapping.Environment environment = configuration.getEnvironment();
            if (environment != null) {
                // Environment 是 final 的，无法直接替换
                // 但我们可以创建一个新的 Environment 并使用反射替换
                org.apache.ibatis.transaction.TransactionFactory transactionFactory = environment.getTransactionFactory();
                org.apache.ibatis.mapping.Environment newEnvironment = 
                    new org.apache.ibatis.mapping.Environment(environment.getId(), transactionFactory, newDataSource);
                
                // 使用反射更新 Configuration 中的 Environment
                java.lang.reflect.Field environmentField = 
                    org.apache.ibatis.session.Configuration.class.getDeclaredField("environment");
                environmentField.setAccessible(true);
                environmentField.set(configuration, newEnvironment);
                
                log.info("SqlSessionFactory Configuration 的数据源引用已更新");
            }
        } catch (Exception e) {
            log.warn("更新 SqlSessionFactory Configuration 数据源引用失败: {}", e.getMessage());
            // 这种情况下，可能需要重新创建 SqlSessionFactory
            log.warn("建议：如果分片策略仍未生效，可能需要重新创建 SqlSessionFactory 和 SqlSessionTemplate");
        }
    }
}
