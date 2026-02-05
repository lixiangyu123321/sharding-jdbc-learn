package org.lix.provider.dao.entity;

import lombok.Data;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * 用户实体类
 */
@Data
@Table(name = "t_user")
public class User {
    
    @Id
    private Long id;
    
    private String userName;
    
    private String email;
    
    private Integer age;
}

