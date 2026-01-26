package org.lix.mycatdemo.filter;

import com.alibaba.nacos.common.utils.StringUtils;

import java.util.HashMap;
import java.util.Map;

public class ReturnCode {
    public static final ReturnCode SUCCESS = new ReturnCode(0, "成功");

    protected int code;

    protected String message;

    public ReturnCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public ReturnCode customMsg(String customMessage) {
        return new ReturnCode(code, customMessage);
    }

    public MessageFormat custom() {
        return new MessageFormat(this);
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public static class MessageFormat {
        private ReturnCode srcCode;

        private Map<String, String> params = new HashMap<String, String>(4);

        public MessageFormat(ReturnCode returnCode) {
            this.srcCode = returnCode;
        }

        public MessageFormat withParam(String name, String value) {
            this.params.put(name, value);
            return this;
        }

        public MessageFormat withParams(Map<String, String> nvs) {
            this.params.putAll(nvs);
            return this;
        }

        public ReturnCode format() {
            ReturnCode newCode = new ReturnCode(srcCode.code, srcCode.message);
            for (Map.Entry<String, String> entry : this.params.entrySet()) {
                newCode.message = StringUtils.replace(newCode.message, "${" + entry.getKey() + "}", entry.getValue());
            }
            return newCode;
        }
    }

    public static class MsgBuilder {
        private String msg;

        public MsgBuilder(String msg) {
            this.msg = msg;
        }

        public MsgBuilder replace(String key, String val) {
            this.msg = StringUtils.replace(this.msg, "${" + key + "}", val);
            return this;
        }

        public String build() {
            return this.msg;
        }
    }
}
