package org.lix.mycatdemo.exception;

import lombok.Getter;

@Getter
public enum ExceptionCode {

    UNKNOWN(100000, "位置异常");

    private Integer code;
    private String message;


    ExceptionCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
