package org.lix.mycatdemo.parser;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Map;


/**
 * 抽象类
 */
@Slf4j
public abstract class AbstractConfigParser implements ConfigParser {

    @Override
    public boolean supports(ConfigFileTypeEnum type){
        return this.types().contains(type);
    }

    @Override
    public Map<String, Object> doParse(String content){
        return doParse(content, null);
    }
}
