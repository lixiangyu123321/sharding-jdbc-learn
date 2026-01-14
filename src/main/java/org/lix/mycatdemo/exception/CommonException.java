package org.lix.mycatdemo.exception;

/**
 * 通用异常
 */
public class CommonException extends RuntimeException {

    private Integer code;

    public CommonException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public CommonException() {super();}

    public CommonException(String message) {super(message);}

    public CommonException(String message, Throwable cause) {super(message, cause);}

    public CommonException(Throwable cause) {super(cause);}
}
