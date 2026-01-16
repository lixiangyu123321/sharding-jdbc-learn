package org.lix.mycatdemo.parser;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 配置解析统一接口
 */
public interface ConfigParser {


    /**
     * 支持类型
     * @param type 文件类型
     * @return true/false
     */
    boolean supports(ConfigFileTypeEnum type);

    /**
     * 支持类型数组
     * @return 类型数组
     */
    List<ConfigFileTypeEnum> types();

    /**
     * 解析配置文件
     * @param content 配置文件内容
     * @return 扁平化的键值对
     * @throws IOException 文件读取异常
     */
    Map<String, Object> doParse(String content);

    /**
     * 解析配置文件
     * @param content 配置文件内容
     * @return 扁平化的键值对
     * @throws IOException 文件读取异常
     */
    Map<String, Object> doParse(String content, String prefix);
}
