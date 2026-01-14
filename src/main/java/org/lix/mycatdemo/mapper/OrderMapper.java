package org.lix.mycatdemo.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.lix.mycatdemo.dao.OrderDO;
import tk.mybatis.mapper.common.Mapper;
import tk.mybatis.mapper.common.MySqlMapper;

import java.util.List;

public interface OrderMapper extends Mapper<OrderDO>, MySqlMapper<OrderDO> {

    @Insert("insert into t_order(price, user_id, status) values (#{order.price}, #{order.userId}, #{order.status})")
    Integer add(@Param("order") OrderDO order);

    List<OrderDO> selectByUserId(@Param("userId") List<Long> userId);
}
