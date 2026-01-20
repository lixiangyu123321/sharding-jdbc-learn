package org.lix.mycatdemo.log;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 日志统计服务
 * 统计应用运行期间的日志情况
 */
@Slf4j
@Component
public class LogStatistics {

    private final AtomicLong infoCount = new AtomicLong(0);
    private final AtomicLong warnCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    private final AtomicLong debugCount = new AtomicLong(0);

    /**
     * 增加INFO日志计数
     */
    public void incrementInfo() {
        infoCount.incrementAndGet();
    }

    /**
     * 增加WARN日志计数
     */
    public void incrementWarn() {
        warnCount.incrementAndGet();
    }

    /**
     * 增加ERROR日志计数
     */
    public void incrementError() {
        errorCount.incrementAndGet();
    }

    /**
     * 增加DEBUG日志计数
     */
    public void incrementDebug() {
        debugCount.incrementAndGet();
    }

    /**
     * 获取统计信息
     */
    public Statistics getStatistics() {
        return new Statistics(
            infoCount.get(),
            warnCount.get(),
            errorCount.get(),
            debugCount.get()
        );
    }

    /**
     * 定期打印统计信息（每5分钟）
     */
    @Scheduled(fixedRate = 300000)
    public void printStatistics() {
        Statistics stats = getStatistics();
        long total = stats.getTotal();
        
        if (total > 0) {
            log.info("========== 日志统计信息 ==========");
            log.info("总日志数: {}", total);
            log.info("INFO: {} ({}%)", 
                stats.getInfoCount(), 
                String.format("%.2f", stats.getInfoCount() * 100.0 / total));
            log.info("WARN: {} ({}%)", 
                stats.getWarnCount(), 
                String.format("%.2f", stats.getWarnCount() * 100.0 / total));
            log.info("ERROR: {} ({}%)", 
                stats.getErrorCount(), 
                String.format("%.2f", stats.getErrorCount() * 100.0 / total));
            log.info("DEBUG: {} ({}%)", 
                stats.getDebugCount(), 
                String.format("%.2f", stats.getDebugCount() * 100.0 / total));
            log.info("==================================");
        }
    }

    /**
     * 统计信息数据类
     */
    public static class Statistics {
        private final long infoCount;
        private final long warnCount;
        private final long errorCount;
        private final long debugCount;

        public Statistics(long infoCount, long warnCount, long errorCount, long debugCount) {
            this.infoCount = infoCount;
            this.warnCount = warnCount;
            this.errorCount = errorCount;
            this.debugCount = debugCount;
        }

        public long getInfoCount() {
            return infoCount;
        }

        public long getWarnCount() {
            return warnCount;
        }

        public long getErrorCount() {
            return errorCount;
        }

        public long getDebugCount() {
            return debugCount;
        }

        public long getTotal() {
            return infoCount + warnCount + errorCount + debugCount;
        }
    }
}

