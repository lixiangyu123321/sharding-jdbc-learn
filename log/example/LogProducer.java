package org.lix.mycatdemo.log.example;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * 日志生产者示例
 * 用于生成测试日志，模拟真实业务场景
 */
@Slf4j
@Component
public class LogProducer {

    private final Random random = new Random();
    private int requestCount = 0;

    /**
     * 模拟业务日志
     * 每 5 秒执行一次
     */
    @Scheduled(fixedRate = 5000)
    public void generateBusinessLogs() {
        requestCount++;
        
        // 模拟不同级别的日志
        int logType = random.nextInt(100);
        
        if (logType < 60) {
            // 60% 的信息日志
            log.info("业务请求处理成功 - 请求ID: {}, 处理时间: {}ms", 
                requestCount, random.nextInt(500) + 50);
        } else if (logType < 85) {
            // 25% 的调试日志
            log.debug("调试信息 - 请求ID: {}, 参数: {}", 
                requestCount, generateRandomParams());
        } else if (logType < 95) {
            // 10% 的警告日志
            log.warn("警告信息 - 请求ID: {}, 警告原因: {}", 
                requestCount, "资源使用率较高");
        } else {
            // 5% 的错误日志
            log.error("错误信息 - 请求ID: {}, 错误详情: {}", 
                requestCount, "模拟业务异常", 
                new RuntimeException("模拟异常堆栈"));
        }
    }

    /**
     * 模拟用户操作日志
     * 每 3 秒执行一次
     */
    @Scheduled(fixedRate = 3000)
    public void generateUserOperationLogs() {
        String[] operations = {"登录", "查询订单", "创建订单", "更新订单", "删除订单"};
        String[] users = {"user001", "user002", "user003", "admin", "guest"};
        
        String operation = operations[random.nextInt(operations.length)];
        String user = users[random.nextInt(users.length)];
        
        log.info("用户操作 - 用户: {}, 操作: {}, 时间: {}", 
            user, operation, System.currentTimeMillis());
    }

    /**
     * 模拟系统监控日志
     * 每 10 秒执行一次
     */
    @Scheduled(fixedRate = 10000)
    public void generateSystemMonitorLogs() {
        double cpuUsage = 20 + random.nextDouble() * 60; // 20-80%
        double memoryUsage = 30 + random.nextDouble() * 50; // 30-80%
        
        log.info("系统监控 - CPU使用率: {}%, 内存使用率: {}%, 活跃线程数: {}", 
            String.format("%.2f", cpuUsage), 
            String.format("%.2f", memoryUsage), 
            Thread.activeCount());
        
        // 如果资源使用率过高，记录警告
        if (cpuUsage > 70 || memoryUsage > 75) {
            log.warn("系统资源告警 - CPU: {}%, 内存: {}%", 
                String.format("%.2f", cpuUsage), 
                String.format("%.2f", memoryUsage));
        }
    }

    /**
     * 生成随机参数
     */
    private String generateRandomParams() {
        return String.format("userId=%d, orderId=%d, status=%s", 
            random.nextInt(1000) + 1,
            random.nextInt(10000) + 1,
            random.nextBoolean() ? "SUCCESS" : "PENDING");
    }
}

