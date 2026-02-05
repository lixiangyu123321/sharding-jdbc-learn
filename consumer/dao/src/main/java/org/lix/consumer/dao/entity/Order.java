package org.lix.consumer.dao.entity;

import lombok.Data;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * 订单实体类
 */
@Data
@Table(name = "t_order")
public class Order {
    
    @Id
    private Long id;
    
    private Long userId;
    
    private String orderNo;
    
    private Double amount;
}

