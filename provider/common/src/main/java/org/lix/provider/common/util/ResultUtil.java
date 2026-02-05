package org.lix.provider.common.util;

import lombok.Data;

/**
 * 结果工具类
 */
public class ResultUtil {
    
    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMessage("success");
        result.setData(data);
        return result;
    }
    
    public static <T> Result<T> error(String message) {
        Result<T> result = new Result<>();
        result.setCode(500);
        result.setMessage(message);
        return result;
    }
    
    @Data
    public static class Result<T> {
        private Integer code;
        private String message;
        private T data;
    }
}

