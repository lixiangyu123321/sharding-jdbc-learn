package org.lix.provider.web.dto;

import lombok.Data;

/**
 * 用户请求 DTO
 */
@Data
public class UserRequestDTO {
    
    private String userName;
    private String email;
    private Integer age;
}

