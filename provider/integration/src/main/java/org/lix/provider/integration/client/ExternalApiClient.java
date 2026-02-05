package org.lix.provider.integration.client;

import org.springframework.stereotype.Component;

/**
 * 外部 API 客户端
 * 用于调用第三方服务
 */
@Component
public class ExternalApiClient {
    
    /**
     * 调用外部服务
     * @param param 参数
     * @return 结果
     */
    public String callExternalService(String param) {
        // TODO: 实现外部服务调用逻辑
        return "External service result: " + param;
    }
}

