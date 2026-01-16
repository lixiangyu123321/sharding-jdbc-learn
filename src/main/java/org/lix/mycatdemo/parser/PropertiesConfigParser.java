package org.lix.mycatdemo.parser;

import com.alibaba.nacos.api.utils.StringUtils;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class PropertiesConfigParser extends AbstractConfigParser {

    private static final List<ConfigFileTypeEnum> CONFIG_TYPES = Lists.newArrayList(ConfigFileTypeEnum.PROPERTIES);


    @Override
    public List<ConfigFileTypeEnum> types() {
        return CONFIG_TYPES;
    }

    @Override
    public Map<String, Object> doParse(String content, String prefix) {
        if(StringUtils.isBlank(content)){
            return Maps.newHashMap();
        }

        Properties properties = new Properties();
        try{
            properties.load(new StringReader(content));
        } catch(IOException e){
            return Maps.newHashMap();
        }

        Map<String, Object> result = Maps.newHashMap();
        properties.forEach((k, v) -> {
            if(k.toString().startsWith(prefix)){
                result.put(k.toString(), v);
            }
        });
        return result;
    }
}
