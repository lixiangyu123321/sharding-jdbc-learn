package org.lix.provider.service;

import org.apache.dubbo.config.annotation.DubboService;
import org.lix.provider.facade.UserDTO;
import org.lix.provider.facade.UserService;
import org.springframework.stereotype.Service;

/**
 * 用户服务实现类
 * 使用 @DubboService 注解暴露为 Dubbo 服务
 */
@Service
@DubboService(version = "1.0.0", interfaceClass = UserService.class)
public class UserServiceImpl implements UserService {
    
    @Override
    public String getUserName(Long userId) {
        // TODO: 实现具体的业务逻辑
        return "User-" + userId;
    }
    
    @Override
    public UserDTO getUserInfo(Long userId) {
        // TODO: 实现具体的业务逻辑
        UserDTO userDTO = new UserDTO();
        userDTO.setUserId(userId);
        userDTO.setUserName("User-" + userId);
        userDTO.setEmail("user" + userId + "@example.com");
        userDTO.setAge(25);
        return userDTO;
    }
}

