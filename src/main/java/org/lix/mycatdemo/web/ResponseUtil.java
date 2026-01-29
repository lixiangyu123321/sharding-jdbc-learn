package org.lix.mycatdemo.web;

import java.util.HashMap;
import java.util.Map;

public class ResponseUtil {

    private static Map<String, Object> buildResult(boolean success, String msg, Map<String, String> errors) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("message", msg);
        result.put("errors", errors);
        return result;
    }
}
