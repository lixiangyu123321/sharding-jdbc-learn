package org.lix.mycatdemo.log;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.UUID;

/**
 * 日志拦截器
 * 在请求处理前后记录日志，设置追踪ID
 */
@Slf4j
@Component
public class LogInterceptor implements HandlerInterceptor {

    @Autowired
    private LogService logService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 获取或生成请求追踪ID
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isEmpty()) {
            requestId = UUID.randomUUID().toString();
        }
        
        // 设置追踪ID到MDC和请求属性
        logService.setTraceId(requestId);
        request.setAttribute("X-Request-Id", requestId);
        response.setHeader("X-Request-Id", requestId);
        
        // 记录请求开始日志
        long startTime = System.currentTimeMillis();
        request.setAttribute("startTime", startTime);
        
        log.debug("请求开始 - 请求ID: {}, 路径: {}, 方法: {}, IP: {}", 
            requestId, request.getRequestURI(), request.getMethod(), getClientIp(request));
        
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, 
                                Object handler, Exception ex) {
        // 计算请求耗时
        Long startTime = (Long) request.getAttribute("startTime");
        long duration = startTime != null ? System.currentTimeMillis() - startTime : 0;
        
        String requestId = (String) request.getAttribute("X-Request-Id");
        
        // 记录请求完成日志
        if (ex != null) {
            log.error("请求异常 - 请求ID: {}, 路径: {}, 方法: {}, 耗时: {}ms", 
                requestId, request.getRequestURI(), request.getMethod(), duration, ex);
        } else {
            log.debug("请求完成 - 请求ID: {}, 路径: {}, 方法: {}, 状态: {}, 耗时: {}ms", 
                requestId, request.getRequestURI(), request.getMethod(), 
                response.getStatus(), duration);
        }
        
        // 记录性能日志
        logService.logPerformance(
            request.getRequestURI(), 
            duration, 
            ex == null && response.getStatus() < 400
        );
        
        // 清除MDC上下文
        logService.clearAllContext();
    }

    /**
     * 获取客户端IP地址
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}

