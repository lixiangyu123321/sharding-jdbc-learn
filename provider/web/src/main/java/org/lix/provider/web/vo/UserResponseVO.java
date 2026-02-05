package org.lix.provider.web.vo;

import lombok.Data;

/**
 * 用户响应 VO
 */
@Data
public class UserResponseVO {
    
    private Long userId;
    private String userName;
    private String email;
    private Integer age;
}

