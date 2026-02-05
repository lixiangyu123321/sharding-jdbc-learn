package org.lix.provider.facade.service;

/**
 * 测试服务接口（Dubbo 服务接口）
 * 此接口定义在 facade 模块中，供 provider 和 consumer 共同使用
 */
public interface TestService {

    /**
     * 猜猜我是谁
     * @param name 用户名
     * @return yes or no
     */
    String guessWhoAmI(String name);
}
