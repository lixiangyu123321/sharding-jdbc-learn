package org.lix.mycatdemo.nacos.service;

import lombok.extern.slf4j.Slf4j;
import org.lix.mycatdemo.dao.OrderDO;
import org.lix.mycatdemo.mapper.OrderMapper;
import org.lix.mycatdemo.nacos.listener.ShardingJDBCListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class ScheduleTaskService {

    @Resource
    private OrderMapper orderMapper;

    @Resource
    private ShardingJDBCListener shardingJDBCListener;

    private ThreadPoolTaskScheduler taskScheduler;

    /**
     * 高并发线程池
     */
    private ExecutorService highConcurrencyExecutor;

    /**
     * 一系列的统计信息
     */
    private final AtomicLong writeCount = new AtomicLong(0);
    private final AtomicLong readCount = new AtomicLong(0);
    private final AtomicLong writeErrorCount = new AtomicLong(0);
    private final AtomicLong readErrorCount = new AtomicLong(0);

    /**
     * 控制任务执行
     */
    private volatile boolean running = true;

    /**
     * 配置刷新间隔
     */
    private static final int CONFIG_REFRESH_INTERVAL = 30;

    /**
     * 高并发线程数
     */
    private static final int HIGH_CONCURRENCY_THREADS = 20;

    /**
     * 读写操作比例（写:读 = 1:3）
     */
    private static final int READ_WRITE_RATIO = 3;

    @PostConstruct
    public void init() {
        taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(10);
        taskScheduler.setThreadNamePrefix("ScheduleTaskService");
        taskScheduler.initialize();
        
        // 创建高并发线程池
        highConcurrencyExecutor = new ThreadPoolExecutor(
            HIGH_CONCURRENCY_THREADS,
            HIGH_CONCURRENCY_THREADS,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(1000),
            // XXX 线程工厂写法
            r -> {
                Thread t = new Thread(r, "HighConcurrency-Worker");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        // 启动高并发读写任务
        startHighConcurrencyReadWriteTasks();
        
        // 启动配置动态切换模拟任务
        // XXX 配置切换我自己来就行
        // startConfigDynamicSwitchTask();
        
        // 启动统计信息打印任务
        startStatisticsTask();
        
        // 注册 JVM 关闭钩子，在 JVM 终止时打印统计信息
        registerShutdownHook();
    }

    /**
     * 注册 JVM 关闭钩子
     * 在 JVM 终止时打印最终的统计信息
     */
    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("========== JVM 正在关闭，打印最终统计信息 ==========");
            printFinalStatistics();
            log.info("==================================================");
        }, "ScheduleTaskService-ShutdownHook"));
        log.info("已注册 JVM 关闭钩子，将在 JVM 终止时打印统计信息");
    }

    /**
     * 打印最终统计信息
     */
    private void printFinalStatistics() {
        long totalWrite = writeCount.get();
        long totalRead = readCount.get();
        long totalWriteError = writeErrorCount.get();
        long totalReadError = readErrorCount.get();
        long totalOps = totalWrite + totalRead;
        
        if (totalOps > 0) {
            double writeSuccessRate = totalWrite > 0 ? 
                (1.0 - (double) totalWriteError / totalWrite) * 100 : 0;
            double readSuccessRate = totalRead > 0 ? 
                (1.0 - (double) totalReadError / totalRead) * 100 : 0;
            double readWriteRatio = totalRead > 0 && totalWrite > 0 ? 
                (double) totalRead / totalWrite : 0;
            
            log.info("========== 最终统计信息 ==========");
            log.info("总操作数: {}", totalOps);
            log.info("写操作: 成功={}, 失败={}, 成功率={}%",
                totalWrite, totalWriteError, String.format("%.2f", writeSuccessRate));
            log.info("读操作: 成功={}, 失败={}, 成功率={}%",
                totalRead, totalReadError, String.format("%.2f", readSuccessRate));
            log.info("读写比例: {}:1", String.format("%.2f", readWriteRatio));
            log.info("==================================");
        } else {
            log.info("========== 最终统计信息 ==========");
            log.info("未执行任何操作");
            log.info("==================================");
        }
    }

    /**
     * 启动高并发读写任务
     * 模拟多个线程同时进行读写操作
     */
    private void startHighConcurrencyReadWriteTasks() {
        log.info("========== 启动高并发读写任务 ==========");
        log.info("并发线程数: {}, 读写比例: 1:{}", HIGH_CONCURRENCY_THREADS, READ_WRITE_RATIO);
        
        for (int i = 0; i < HIGH_CONCURRENCY_THREADS; i++) {
            final int threadIndex = i;
            highConcurrencyExecutor.submit(() -> {
                Random random = new Random();
                String threadName = Thread.currentThread().getName() + "-" + threadIndex;
                log.info("线程 {} 启动", threadName);
                
                while (running) {
                    try {
                        // 根据读写比例决定操作类型
                        boolean isWrite = (random.nextInt(READ_WRITE_RATIO + 1) == 0);
                        
                        if (isWrite) {
                            // 写操作：插入数据
                            performWriteOperation(threadName, random);
                        } else {
                            // 读操作：查询数据
                            performReadOperation(threadName, random);
                        }
                        
                        // 随机等待时间，模拟真实业务场景
                        int sleepTime = random.nextInt(500) + 100; // 100-600ms
                        Thread.sleep(sleepTime);
                        
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("线程 {} 被中断", threadName);
                        break;
                    } catch (Exception e) {
                        log.error("线程 {} 执行异常", threadName, e);
                        // 发生异常时短暂等待后继续
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                log.info("线程 {} 退出", threadName);
            });
        }
    }

    /**
     * 执行写操作
     */
    private void performWriteOperation(String threadName, Random random) {
        try {
            OrderDO orderDO = new OrderDO();
            orderDO.setPrice(new BigDecimal(random.nextDouble() * 1000 + 0.1));
            orderDO.setStatus("test-" + System.currentTimeMillis());
            orderDO.setUserId((long) (random.nextInt(10) + 1));
            
            long startTime = System.currentTimeMillis();
            orderMapper.add(orderDO);
            long duration = System.currentTimeMillis() - startTime;
            
            writeCount.incrementAndGet();
            log.debug("线程 {} 写操作成功, userId={}, price={}, 耗时={}ms", 
                threadName, orderDO.getUserId(), orderDO.getPrice(), duration);
        } catch (Exception e) {
            writeErrorCount.incrementAndGet();
            log.error("线程 {} 写操作失败", threadName, e);
        }
    }

    /**
     * 执行读操作
     */
    private void performReadOperation(String threadName, Random random) {
        try {
            // 随机选择1-3个userId进行查询
            int userIdCount = random.nextInt(3) + 1;
            List<Long> userIds = new ArrayList<>();
            for (int i = 0; i < userIdCount; i++) {
                userIds.add((long) (random.nextInt(10) + 1));
            }
            
            long startTime = System.currentTimeMillis();
            List<OrderDO> orders = orderMapper.selectByUserId(userIds);
            long duration = System.currentTimeMillis() - startTime;
            
            readCount.incrementAndGet();
            log.debug("线程 {} 读操作成功, userIds={}, 查询结果数={}, 耗时={}ms", 
                threadName, userIds, orders != null ? orders.size() : 0, duration);
        } catch (Exception e) {
            readErrorCount.incrementAndGet();
            log.error("线程 {} 读操作失败", threadName, e);
        }
    }

    /**
     * 启动配置动态切换模拟任务
     * 定期触发配置刷新，模拟读写分离配置的动态切换
     */
    private void startConfigDynamicSwitchTask() {
        log.info("========== 启动配置动态切换模拟任务 ==========");
        log.info("配置刷新间隔: {} 秒", CONFIG_REFRESH_INTERVAL);
        
        taskScheduler.scheduleWithFixedDelay(() -> {
            if (!running) {
                return;
            }
            
            try {
                log.info("========== 模拟配置动态切换 ==========");
                log.info("当前统计: 写操作={}, 读操作={}, 写错误={}, 读错误={}", 
                    writeCount.get(), readCount.get(), writeErrorCount.get(), readErrorCount.get());
                
                // 这里可以触发配置刷新
                // 方式1: 通过反射调用 ShardingJDBCListener 的刷新方法（如果存在）
                // 方式2: 通过 Nacos API 更新配置（需要 ConfigService）
                // 方式3: 直接记录日志，表示配置切换时机
                
                log.info("配置切换时机: 在持续的高并发读写压力下，配置可能会动态刷新");
                log.info("建议: 在 Nacos 控制台手动修改配置以触发动态刷新");
                
            } catch (Exception e) {
                log.error("配置动态切换模拟任务执行异常", e);
            }
        }, Duration.ofSeconds(CONFIG_REFRESH_INTERVAL));
    }

    /**
     * 启动统计信息打印任务
     * 定期打印读写操作的统计信息
     */
    private void startStatisticsTask() {
        log.info("========== 启动统计信息打印任务 ==========");
        
        taskScheduler.scheduleWithFixedDelay(() -> {
            if (!running) {
                return;
            }
            
            long totalWrite = writeCount.get();
            long totalRead = readCount.get();
            long totalWriteError = writeErrorCount.get();
            long totalReadError = readErrorCount.get();
            long totalOps = totalWrite + totalRead;
            
            if (totalOps > 0) {
                double writeSuccessRate = totalWrite > 0 ? 
                    (1.0 - (double) totalWriteError / totalWrite) * 100 : 0;
                double readSuccessRate = totalRead > 0 ? 
                    (1.0 - (double) totalReadError / totalRead) * 100 : 0;
                double readWriteRatio = totalRead > 0 && totalWrite > 0 ? 
                    (double) totalRead / totalWrite : 0;
                
                log.info("========== 高并发读写统计信息 ==========");
                log.info("总操作数: {}", totalOps);
                log.info("写操作: 成功={}, 失败={}, 成功率={}%", 
                    totalWrite, totalWriteError, String.format("%.2f", writeSuccessRate));
                log.info("读操作: 成功={}, 失败={}, 成功率={}%", 
                    totalRead, totalReadError, String.format("%.2f", readSuccessRate));
                log.info("读写比例: {}:1", String.format("%.2f", readWriteRatio));
                log.info("========================================");
            }
        }, Duration.ofSeconds(10));
    }

    /**
     * 停止所有任务
     */
    public void stop() {
        log.info("========== 停止高并发读写任务 ==========");
        running = false;
        
        if (highConcurrencyExecutor != null) {
            highConcurrencyExecutor.shutdown();
            try {
                if (!highConcurrencyExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    highConcurrencyExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                highConcurrencyExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        if (taskScheduler != null) {
            taskScheduler.shutdown();
        }
        
        log.info("所有任务已停止");
    }

    /**
     * 获取统计信息
     */
    public String getStatistics() {
        long totalWrite = writeCount.get();
        long totalRead = readCount.get();
        long totalWriteError = writeErrorCount.get();
        long totalReadError = readErrorCount.get();
        
        return String.format(
            "高并发读写统计 - 写操作: %d (失败: %d), 读操作: %d (失败: %d)",
            totalWrite, totalWriteError, totalRead, totalReadError
        );
    }
}
