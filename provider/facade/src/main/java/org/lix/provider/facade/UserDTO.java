package org.lix.provider.facade;

import lombok.Data;
import java.io.Serializable;

/**
 * 用户数据传输对象
 */
@Data
public class UserDTO implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 用户名
     */
    private String userName;
    
    /**
     * 用户邮箱
     */
    private String email;
    
    /**
     * 用户年龄
     */
    private Integer age;
}

