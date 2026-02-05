package org.lix.provider.facade;

/**
 * 用户服务接口（Dubbo 服务接口）
 * 此接口定义在 facade 模块中，供 provider 和 consumer 共同使用
 */
public interface UserService {
    
    /**
     * 根据用户ID获取用户名
     * @param userId 用户ID
     * @return 用户名
     */
    String getUserName(Long userId);
    
    /**
     * 获取用户信息
     * @param userId 用户ID
     * @return 用户信息
     */
    UserDTO getUserInfo(Long userId);
}

