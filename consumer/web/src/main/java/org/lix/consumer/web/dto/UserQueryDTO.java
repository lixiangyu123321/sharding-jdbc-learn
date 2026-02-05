package org.lix.consumer.web.dto;

import lombok.Data;

/**
 * 用户查询 DTO
 */
@Data
public class UserQueryDTO {
    
    private Long userId;
    private String userName;
}

