package org.lix.mycatdemo.parser;

import com.alibaba.nacos.api.utils.StringUtils;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 配置文件类型枚举
 */
@Getter
@AllArgsConstructor
public enum ConfigFileTypeEnum {

    PROPERTIES("properties"),
    XML("xml"),
    JSON("json"),
    YAML("yaml"),
    YML("yml"),
    TXT("txt");


    private final String value;

    public static ConfigFileTypeEnum of(String value) {
        if(StringUtils.isBlank(value)){
            return PROPERTIES;
        }
        value = value.toLowerCase();
        for(ConfigFileTypeEnum e : ConfigFileTypeEnum.values()) {
            if(e.value.equals(value)) {
                return e;
            }
        }
        return PROPERTIES;
    }
}
