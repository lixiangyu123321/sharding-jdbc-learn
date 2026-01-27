package org.lix.mycatdemo.interceptor;

import lombok.extern.slf4j.Slf4j;
import org.lix.mycatdemo.filter.ResponseWriter;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.lix.mycatdemo.filter.CommonError.GATEWAY_PROCESS_FAILED;

@Slf4j
@Component
public class SecurityInterceptor implements HandlerInterceptor {
    @Resource
    private SignAuthentication signAuthentication;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod)) {
            log.debug("跳过非HandlerMethod的请求: {}", request.getRequestURI());
            return true;
        }
        log.info("使用interceptor开始拦截");
        try{
            if(!signAuthentication.signAuthenticate(request, response)){
                return false;
            }
        } catch(Exception e){
            ResponseWriter.writeErrorMsg(response, GATEWAY_PROCESS_FAILED);
            return false;
        }
        log.info("放行");
        return true;
    }
}
