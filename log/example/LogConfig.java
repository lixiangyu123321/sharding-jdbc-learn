package org.lix.mycatdemo.log.example;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 日志配置类
 * 启用定时任务，用于生成测试日志
 */
@Configuration
@EnableScheduling
public class LogConfig {
    // 配置类，用于启用定时任务功能
    // 如果项目中已有 @EnableScheduling，可以删除此类
}

