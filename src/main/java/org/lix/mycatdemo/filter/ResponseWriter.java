package org.lix.mycatdemo.filter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public class ResponseWriter {

    public static JSONObject wrapErrorResult(ReturnCode error) {
        JSONObject json = new JSONObject();
        json.put("code", error.getCode());
        json.put("msg", error.getMessage());

        return json;
    }

    /**
     * 返回错误信息
     *
     * @param response
     * @param error
     * @throws IOException
     */
    public static void writeErrorMsg(HttpServletResponse response, ReturnCode error) throws IOException {
        if (response.getContentType() == null) {
            response.setContentType("application/json;charset=utf-8");
        }
        response.getWriter().write(JSON.toJSONString(wrapErrorResult(error)));
    }
}