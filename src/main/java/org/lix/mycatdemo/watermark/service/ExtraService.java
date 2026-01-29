package org.lix.mycatdemo.watermark.service;

import com.alibaba.nacos.common.executor.NameThreadFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.concurrent.*;

/**
 * 缩图业务的上游处理
 */
@Slf4j
@Service
public class ExtraService implements InitializingBean, DisposableBean {

    /**
     * 使用线程池处理任务
     */
    private ExecutorService executorService;

    /**
     * 线程池核心线程数
     */
    private static final int CORE_POOL_SIZE = 5;

    /**
     * 线程池最大线程数
     */
    private static final int MAX_POOL_SIZE = 10;

    /**
     * 线程池空闲线程存活时间（秒）
     */
    private static final long KEEP_ALIVE_TIME = 60L;

    /**
     * 线程池队列容量
     */
    private static final int QUEUE_CAPACITY = 100;

    @Resource
    private WatermarkService watermarkService;

    @Override
    public void destroy() throws Exception {
        // 初始化线程池
        initThreadPool();
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        // 关闭线程池
        shutdownThreadPool();
    }

    /**
     * 初始化线程池
     */
    private void initThreadPool() {
        executorService = new ThreadPoolExecutor(
                CORE_POOL_SIZE,
                MAX_POOL_SIZE,
                KEEP_ALIVE_TIME,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                new NameThreadFactory("watermark-thread"),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        log.info("水印服务线程池初始化成功 - 核心线程数: {}, 最大线程数: {}, 队列容量: {}",
                CORE_POOL_SIZE, MAX_POOL_SIZE, QUEUE_CAPACITY);
    }

    /**
     * 关闭线程池
     */
    private void shutdownThreadPool() {
        if (executorService != null && !executorService.isShutdown()) {
            log.info("开始关闭水印服务线程池...");
            executorService.shutdown();
            try {
                // 等待已提交的任务完成，最多等待30秒
                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("线程池在30秒内未能正常关闭，强制关闭...");
                    executorService.shutdownNow();
                    // 再等待5秒
                    if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                        log.error("线程池强制关闭后仍有任务未完成");
                    }
                }
                log.info("水印服务线程池已成功关闭");
            } catch (InterruptedException e) {
                log.warn("等待线程池关闭时被中断，强制关闭线程池", e);
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 基于文件系统做一下吧
     * @param path 绝对路径
     * @return
     * @throws Exception
     */
    public String watermarkAppend(String path, boolean useFFmpeg, boolean isScale, boolean useFFprobe) throws Exception {
        // 将加水印任务提交到线程池执行
        Future<String> future = executorService.submit(() -> {
            try {
                return watermarkService.executeWatermarkAppend(path, useFFmpeg, isScale, useFFprobe);
            } catch (Exception e) {
                log.error("执行加水印任务时发生异常，文件路径: {}", path, e);
                throw new RuntimeException("加水印任务执行失败: " + e.getMessage(), e);
            }
        });

        try {
            // 等待任务完成并返回结果（保持原有同步行为）
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            } else if (cause instanceof Exception) {
                throw (Exception) cause;
            } else {
                throw new Exception("加水印任务执行失败", cause);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("等待加水印任务完成时被中断，文件路径: {}", path, e);
            throw new Exception("加水印任务被中断", e);
        }
    }
}
