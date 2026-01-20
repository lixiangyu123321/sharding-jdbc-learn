package org.lix.mycatdemo.log;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * 日志服务
 * 提供统一的日志记录方法，支持上下文信息记录
 */
@Slf4j
@Service
public class LogService {

    /**
     * 记录业务操作日志
     */
    public void logBusinessOperation(String operation, String userId, Object... params) {
        MDC.put("operation", operation);
        MDC.put("userId", userId);
        log.info("业务操作 - 操作: {}, 用户: {}, 参数: {}", operation, userId, params);
        MDC.remove("operation");
        MDC.remove("userId");
    }

    /**
     * 记录性能日志
     */
    public void logPerformance(String method, long duration, boolean success) {
        MDC.put("method", method);
        MDC.put("duration", String.valueOf(duration));
        MDC.put("success", String.valueOf(success));
        
        if (success) {
            log.info("性能监控 - 方法: {}, 耗时: {}ms", method, duration);
        } else {
            log.warn("性能告警 - 方法: {}, 耗时: {}ms (超时)", method, duration);
        }
        
        MDC.remove("method");
        MDC.remove("duration");
        MDC.remove("success");
    }

    /**
     * 记录审计日志
     */
    public void logAudit(String action, String resource, String userId, String result) {
        MDC.put("audit_action", action);
        MDC.put("audit_resource", resource);
        MDC.put("audit_user", userId);
        MDC.put("audit_result", result);
        
        log.info("审计日志 - 操作: {}, 资源: {}, 用户: {}, 结果: {}", 
            action, resource, userId, result);
        
        MDC.remove("audit_action");
        MDC.remove("audit_resource");
        MDC.remove("audit_user");
        MDC.remove("audit_result");
    }

    /**
     * 设置请求追踪ID
     */
    public String setTraceId() {
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        return traceId;
    }

    /**
     * 设置请求追踪ID（使用已有ID）
     */
    public void setTraceId(String traceId) {
        MDC.put("traceId", traceId);
    }

    /**
     * 清除追踪ID
     */
    public void clearTraceId() {
        MDC.remove("traceId");
    }

    /**
     * 设置上下文信息
     */
    public void setContext(String key, String value) {
        MDC.put(key, value);
    }

    /**
     * 清除上下文信息
     */
    public void clearContext(String key) {
        MDC.remove(key);
    }

    /**
     * 清除所有上下文信息
     */
    public void clearAllContext() {
        MDC.clear();
    }

    /**
     * 获取当前上下文信息
     */
    public Map<String, String> getContext() {
        return MDC.getCopyOfContextMap();
    }
}

