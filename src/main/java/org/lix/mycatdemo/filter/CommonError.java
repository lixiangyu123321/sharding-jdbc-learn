package org.lix.mycatdemo.filter;

public class CommonError extends ReturnCode {

    public static final CommonError GATEWAY_PROCESS_FAILED = new CommonError(10000, "服务器内部错误");
    public static final CommonError GATEWAY_PROCESS_RATE_LIMIT = new CommonError(10001, "服务器压力过大，请稍候再试!");
    public static final CommonError GATEWAY_PROCESS_BLOCKED = new CommonError(10002, "服务器异常，请稍候再试!");
    public static final CommonError INVALID_PARAM_USER_NOT_LOGIN = new CommonError(20001, "用户未登录!");
    public static final CommonError USER_FORBID = new CommonError(20002, "你的账号存在风险，请稍候登录!");
    public static final CommonError CSRF_CHECK_FAILED = new CommonError(20003, "CSRF检验失败!");
    public static final CommonError INVALID_URL = new CommonError(20004, "访问的URL有误!");
    public static final CommonError TASK_QUEUE_BUSY = new CommonError(20005, "当前任务排队人数太多了，请稍后再试。");
    public static final CommonError INVALID_TASKID = new CommonError(20006, "没有找到该任务。");
    public static final CommonError REQUEST_PARAM_ERROR = new CommonError(20007, "请求相关信息异常！");
    public static final CommonError LOGIN_STATE_EXPIRED = new CommonError(30005, "登录信息过期，请重新登录");
    public static final CommonError REQUEST_STATE_EXPIRED = new CommonError(30006, "本次请求为过期请求!");
    public static final CommonError REQUEST_STATE_REPEATED = new CommonError(30007, "本次请求为重复请求!");

    public CommonError(int code, String message) {
        super(code, message);
    }
}
