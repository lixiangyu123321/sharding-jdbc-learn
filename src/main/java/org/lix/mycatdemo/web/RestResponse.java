package org.lix.mycatdemo.web;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Spring RESTful 风格统一响应类
 */
@Data
@NoArgsConstructor
public class RestResponse<T> {
    /**
     * 业务状态码（非HTTP状态码）：
     * 200 = 成功，其他值 = 失败（可自定义，如400=参数错误，500=系统异常）
     */
    private int code;

    /**
     * 响应提示信息（用户友好提示）
     */
    private String message;

    /**
     * 响应数据（成功时返回业务数据，失败时可为null）
     */
    private T data;

    // ========== 静态构造方法（简化调用） ==========
    /**
     * 成功响应（无数据）
     */
    public static <T> RestResponse<T> success() {
        return new RestResponse<>(200, "操作成功", null);
    }

    /**
     * 成功响应（带数据）
     */
    public static <T> RestResponse<T> success(T data) {
        return new RestResponse<>(200, "操作成功", data);
    }

    /**
     * 成功响应（自定义提示+数据）
     */
    public static <T> RestResponse<T> success(String message, T data) {
        return new RestResponse<>(200, message, data);
    }

    /**
     * 失败响应（自定义业务码+提示）
     */
    public static <T> RestResponse<T> fail(int code, String message) {
        return new RestResponse<>(code, message, null);
    }

    /**
     * 失败响应（默认500码+自定义提示）
     */
    public static <T> RestResponse<T> fail(String message) {
        return new RestResponse<>(500, message, null);
    }

    // 私有化全参构造，强制使用静态方法创建实例
    private RestResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }
}
