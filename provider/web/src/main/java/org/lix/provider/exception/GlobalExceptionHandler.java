package org.lix.provider.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<?> handleBusinessException(BusinessException e) {
        // XXX 日志应该在这里打印哪
        // XXX 而业务中应该封装异常为业务异常，然后由controller层统一封装
        log.error(e.getMessage());
        return ResponseEntity.internalServerError().body(e.getMessage());
    }

    @ExceptionHandler(CommonException.class)
    public ResponseEntity<?> handleCommonException(CommonException e) {
        log.error(e.getMessage());
        return ResponseEntity.internalServerError().body(e.getMessage());
    }


    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleException(Exception e) {
        log.error("系统异常", e);
        return ResponseEntity.internalServerError().body("系统异常");
    }
}
