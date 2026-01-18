package org.lix.mycatdemo.parser;

import com.alibaba.fastjson2.JSONObject;
import com.alibaba.nacos.api.utils.StringUtils;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.collections4.MapUtils;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * fastjson 解析json配置文件
 */
public class JsonConfigParser extends AbstractConfigParser {

    private static final List<ConfigFileTypeEnum> CONFIG_TYPES = Lists.newArrayList(ConfigFileTypeEnum.JSON);

    @Override
    public List<ConfigFileTypeEnum> types() {
        return CONFIG_TYPES;
    }

    /**
     * 获取指定前缀的配置数据
     * @param content 配置文件内容
     * @param prefix 指定前缀
     * @return key + value
     */
    @Override
    public Map<String, Object> doParse(String content, String prefix){
        if(StringUtils.isBlank(content)){
            return Maps.newHashMap();
        }

        JSONObject jsonObject = JSONObject.parseObject(content);
        Map<String, Object> result = Maps.newHashMap();
        flatMap(jsonObject, result, "");
        if(StringUtils.isBlank(prefix)){
            return result;
        }
        Map<String, Object> finalResult = result;
        result = result.keySet()
                .stream()
                .filter(key -> key.startsWith(prefix))
                .collect(Collectors.toMap(Function.identity(), k -> finalResult.get(k)));
        return result;
    }

    /***
     * 扁平化Map
     * @param dataMap 源Map结构
     * @param result 扁平化Map
     * @param prefix 前缀
     */
    private void flatMap(Map<String, Object> dataMap, Map<String, Object> result, String prefix) {
        if (MapUtils.isEmpty(dataMap)) {
            return;
        }
        dataMap.forEach((k, v) -> {
            // 按照层级关系逐一构建键名
            String fullKey = genFullKey(prefix, k);
            if (v instanceof Map) {
                flatMap((Map<String, Object>) v, result, fullKey);
                return;
            } else if (v instanceof Collection) {
                int count = 0;
                for (Object obj : (Collection<Object>) v) {
                    String kk = "[" + (count++) + "]";
                    flatMap(Collections.singletonMap(kk, obj), result, fullKey);
                }
                return;
            }

            result.put(fullKey, v);
        });
    }

    /**
     * 处理 XX。XX.[1]的情况
     * @param prefix 前缀
     * @param key 值
     * @return 完整key
     */
    private String genFullKey(String prefix, String key) {
        if (StringUtils.isBlank(prefix)) {
            return key;
        }

        return key.startsWith("[") ? prefix.concat(key) : prefix.concat(".").concat(key);
    }
}
