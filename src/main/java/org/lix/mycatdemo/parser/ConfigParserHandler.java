package org.lix.mycatdemo.parser;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.lix.mycatdemo.extension.ExtensionServiceLoader;

import java.util.List;
import java.util.Map;

@Slf4j
public class ConfigParserHandler {

    private static final List<ConfigParser> PARSERS = Lists.newArrayList();

    private ConfigParserHandler() {
        // 通过SPI机制获得配置文件解析器
        List<ConfigParser> loadedParses = ExtensionServiceLoader.get(ConfigParser.class);
        if (CollectionUtils.isNotEmpty(loadedParses)) {
            PARSERS.addAll(loadedParses);
        }
        PARSERS.add(new PropertiesConfigParser());
        PARSERS.add(new YamlConfigParser());
        PARSERS.add(new JsonConfigParser());
    }

    public Map<String, Object> parseConfig(String content, ConfigFileTypeEnum type){
        for(ConfigParser parser : PARSERS){
            if(parser.supports(type)){
                return parser.doParse(content);
            }
        }
        return Maps.newHashMap();
    }

    /**
     * 解析指定前缀的配置数据
     * @param content 配置文件内容
     * @param type 配置文件类型
     * @return
     */
    public Map<String, Object> parseConfig(String content, ConfigFileTypeEnum type, String prefix){
        for(ConfigParser parser : PARSERS){
            if(parser.supports(type)){
                return parser.doParse(content, prefix);
            }
        }
        return Maps.newHashMap();
    }


    public static ConfigParserHandler getInstance(){
        return ConfigParserHandlerHolder.INSTANCE;
    }

    public static class ConfigParserHandlerHolder{
        private static final ConfigParserHandler INSTANCE = new ConfigParserHandler();
    }
}
