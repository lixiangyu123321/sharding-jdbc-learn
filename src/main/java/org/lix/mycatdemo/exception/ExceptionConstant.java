package org.lix.mycatdemo.exception;

public enum ExceptionConstant {

    UNKNOWN("UNKNOWN", 0);

    private String message;

    private Integer code;

    ExceptionConstant(String message, Integer code) {
        this.message = message;
        this.code = code;
    }

    public String getMessage() {
        return message;
    }
    public Integer getCode() {
        return code;
    }
}
