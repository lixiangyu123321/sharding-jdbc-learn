package org.lix.mycatdemo.dao;

import lombok.Data;

import javax.persistence.Id;
import javax.persistence.Table;
import java.math.BigDecimal;

@Data
@Table(name = "t_order")
public class OrderDO {

    @Id
    private Long orderId;

    private BigDecimal price;

    private Long userId;

    private String status;
}
