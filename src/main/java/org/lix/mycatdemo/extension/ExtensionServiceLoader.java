package org.lix.mycatdemo.extension;

import org.apache.commons.collections4.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

public class ExtensionServiceLoader {

    private static final Map<Class<?>, List<?>> EXTENSION_MAP = new ConcurrentHashMap<>();

    private ExtensionServiceLoader() { }

    /**
     * 该注解处理未检查的类型转换
     * @param clazz 类
     * @return 实际的类
     * @param <T> 实际的类
     */
    @SuppressWarnings("unchecked")
    public static <T> List<T> get(Class<T> clazz) {
        List<T> services = (List<T>) EXTENSION_MAP.get(clazz);
        if (CollectionUtils.isEmpty(services)) {
            services = load(clazz);
            if (CollectionUtils.isNotEmpty(services)) {
                EXTENSION_MAP.put(clazz, services);
            }
        }
        return services;
    }

    /**
     * 获得第一个扩展类
     * @param clazz
     * @return
     * @param <T>
     */
    public static <T> T getFirst(Class<T> clazz) {
        List<T> services = get(clazz);
        return CollectionUtils.isEmpty(services) ? null : services.get(0);
    }

    /**
     * XXX 基于SPI机制加载扩展类
     * @param clazz
     * @return
     * @param <T>
     */
    private static <T> List<T> load(Class<T> clazz) {
        ServiceLoader<T> loader = ServiceLoader.load(clazz);
        List<T> services = new ArrayList<>();
        for (T service : loader) {
            services.add(service);
        }
        return services;
    }
}
