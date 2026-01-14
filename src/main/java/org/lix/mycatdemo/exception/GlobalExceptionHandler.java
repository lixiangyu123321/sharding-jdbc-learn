package org.lix.mycatdemo.exception;

import lombok.extern.slf4j.Slf4j;
import org.lix.mycatdemo.web.RestResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public RestResponse<String> handleBusinessException(BusinessException e) {
        // XXX 日志应该在这里打印哪
        // XXX 而业务中应该封装异常为业务异常，然后由controller层统一封装
        log.error(e.getMessage());
        return RestResponse.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(CommonException.class)
    public RestResponse<String> handleCommonException(CommonException e) {
        log.error(e.getMessage());
        return RestResponse.fail(e.getMessage());
    }


    @ExceptionHandler(Exception.class)
    public RestResponse<String> handleException(Exception e) {
        log.error("系统异常", e);
        return RestResponse.fail(500, "服务器内部错误，请联系管理员");
    }
}
