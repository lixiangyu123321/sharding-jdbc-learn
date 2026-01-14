package org.lix.mycatdemo.exception;


/**
 * 业务异常
 */
public class BusinessException extends RuntimeException{

    private static final long serialVersionUID = -7053112606620156755L;

    private Integer code;

    public Integer getCode(){
        return this.code;
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(){
        super();
    }

    public BusinessException(String message){
        super(message);
    }

    public BusinessException(String message, Throwable cause){
        super(message, cause);
    }

    public BusinessException(Throwable cause){
        super(cause);
    }
}
