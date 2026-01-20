package org.lix.mycatdemo.exception;

import lombok.extern.slf4j.Slf4j;
import org.lix.mycatdemo.web.RestResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.util.UUID;

/**
 * 全局异常处理器
 * 统一处理异常并记录日志，便于日志中心收集和分析
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常
     */
    @ExceptionHandler(BusinessException.class)
    public RestResponse<String> handleBusinessException(BusinessException e) {
        String requestId = getRequestId();
        HttpServletRequest request = getRequest();
        
        log.warn("业务异常 - 请求ID: {}, 错误码: {}, 错误信息: {}, 请求路径: {}, 请求方法: {}", 
            requestId, e.getCode(), e.getMessage(), 
            request != null ? request.getRequestURI() : "unknown",
            request != null ? request.getMethod() : "unknown");
        
        return RestResponse.fail(e.getCode(), e.getMessage());
    }

    /**
     * 处理通用异常
     */
    @ExceptionHandler(CommonException.class)
    public RestResponse<String> handleCommonException(CommonException e) {
        String requestId = getRequestId();
        HttpServletRequest request = getRequest();
        
        log.warn("通用异常 - 请求ID: {}, 错误信息: {}, 请求路径: {}, 请求方法: {}", 
            requestId, e.getMessage(),
            request != null ? request.getRequestURI() : "unknown",
            request != null ? request.getMethod() : "unknown");
        
        return RestResponse.fail(e.getMessage());
    }

    /**
     * 处理系统异常
     */
    @ExceptionHandler(Exception.class)
    public RestResponse<String> handleException(Exception e) {
        String requestId = getRequestId();
        HttpServletRequest request = getRequest();
        
        log.error("系统异常 - 请求ID: {}, 异常类型: {}, 异常信息: {}, 请求路径: {}, 请求方法: {}", 
            requestId, e.getClass().getName(), e.getMessage(),
            request != null ? request.getRequestURI() : "unknown",
            request != null ? request.getMethod() : "unknown", e);
        
        return RestResponse.fail(500, "服务器内部错误，请联系管理员");
    }

    /**
     * 获取请求对象
     */
    private HttpServletRequest getRequest() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attributes != null ? attributes.getRequest() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取或生成请求ID（用于日志追踪）
     */
    private String getRequestId() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                String requestId = request.getHeader("X-Request-Id");
                if (requestId == null || requestId.isEmpty()) {
                    requestId = UUID.randomUUID().toString();
                    request.setAttribute("X-Request-Id", requestId);
                }
                return requestId;
            }
        } catch (Exception e) {
            // 忽略异常
        }
        return UUID.randomUUID().toString();
    }
}
