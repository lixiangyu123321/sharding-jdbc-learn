package org.lix.mycatdemo.parser;

import com.alibaba.nacos.api.utils.StringUtils;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.collections4.MapUtils;
import org.yaml.snakeyaml.Yaml;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class YamlConfigParser extends AbstractConfigParser{

    private static final List<ConfigFileTypeEnum> CONFIG_TYPES = Lists.newArrayList(ConfigFileTypeEnum.YAML, ConfigFileTypeEnum.YML);

    @Override
    public List<ConfigFileTypeEnum> types() {
        return CONFIG_TYPES;
    }

    @Override
    public Map<String, Object> doParse(String content, String prefix) {
        if(StringUtils.isBlank(content)){
            return Maps.newHashMap();
        }

        Yaml yaml = new Yaml();
        Map<String, Object> map = yaml.loadAs(content, Map.class);
        Map<String, Object> result = Maps.newHashMap();
        flatMap(map, result, "");
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
                flatMap(result, (Map<String, Object>) v, fullKey);
                return;
            } else if (v instanceof Collection) {
                int count = 0;
                for (Object obj : (Collection<Object>) v) {
                    String kk = "[" + (count++) + "]";
                    flatMap(result, Collections.singletonMap(kk, obj), fullKey);
                }
                return;
            }

            result.put(fullKey, v);
        });
    }

    private String genFullKey(String prefix, String key) {
        if (StringUtils.isBlank(prefix)) {
            return key;
        }

        return key.startsWith("[") ? prefix.concat(key) : prefix.concat(".").concat(key);
    }
}
