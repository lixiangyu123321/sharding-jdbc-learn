package org.lix.consumer.controller;

import org.apache.dubbo.config.annotation.DubboReference;
import org.lix.provider.facade.UserDTO;
import org.lix.provider.facade.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户控制器
 * 通过 @DubboReference 注入远程服务
 */
@RestController
@RequestMapping("/api/user")
public class UserController {
    
    @DubboReference(version = "1.0.0", interfaceClass = UserService.class)
    private UserService userService;
    
    @GetMapping("/name/{userId}")
    public String getUserName(@PathVariable Long userId) {
        return userService.getUserName(userId);
    }
    
    @GetMapping("/info/{userId}")
    public UserDTO getUserInfo(@PathVariable Long userId) {
        return userService.getUserInfo(userId);
    }
}

