package org.lix.consumer.integration.client;

import org.springframework.stereotype.Component;

/**
 * 第三方服务客户端
 * 用于调用第三方服务
 */
@Component
public class ThirdPartyClient {
    
    /**
     * 调用第三方服务
     * @param param 参数
     * @return 结果
     */
    public String callThirdPartyService(String param) {
        // TODO: 实现第三方服务调用逻辑
        return "Third party service result: " + param;
    }
}

