package org.lix.mycatdemo.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lix.mycatdemo.dao.OrderDO;
import org.lix.mycatdemo.mapper.OrderMapper;
import org.lix.mycatdemo.web.RestResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private DataSource dataSource;

    @GetMapping("/test")
    public RestResponse<Integer> test(@RequestParam("count") int batchSize) {
        Integer count = 0;

        for(int i = 0; i < batchSize; i++) {
            OrderDO orderDO = new OrderDO();
            orderDO.setPrice(new BigDecimal("3.14"));
            orderDO.setUserId((long) i);
            orderDO.setStatus("init");
            count += orderMapper.add(orderDO);

        }
        return RestResponse.success(count);
    }

    @GetMapping("/select")
    public RestResponse<List<OrderDO>> select(@RequestParam("userId") String userId) {
        List<Long> orderDOs = Arrays.stream(userId.split(",")).map(String::trim).map(Long::parseLong).collect(Collectors.toList());
        return RestResponse.success(orderMapper.selectByUserId(orderDOs));
    }

    /**
     * 获得来源库的相关信息
     * TODO 更规范一些
     * @param userId
     * @return
     * @throws SQLException
     */
    @GetMapping("/sql")
    public RestResponse<List<OrderDO>> selectBySource(@RequestParam("userId") String userId) throws SQLException {
        List<Long> orderDOs = Arrays.stream(userId.split(",")).map(String::trim).map(Long::parseLong).collect(Collectors.toList());
        Connection conn = dataSource.getConnection();
        String sql = "SELECT " +
                "order_id, " +
                "@@hostname as db_host, " +
                "@@port as db_port, " +
                "@@server_id as server_id, " +
                "DATABASE() as db_name, " +
                "USER() as db_user, " +
                "CONNECTION_ID() as connection_id " +
                "FROM t_order WHERE order_id = 1218510636552552448";
        PreparedStatement pstmt = conn.prepareStatement(sql);
        ResultSet rs = pstmt.executeQuery();
        while(rs.next()) {
            System.out.println("[来源数据库信息]");
            System.out.printf("  Host: %s:%d\n",
                    rs.getString("db_host"),
                    rs.getInt("db_port"));
            System.out.println("  Server ID: " + rs.getString("server_id"));
            System.out.println("  Database: " + rs.getString("db_name"));
            System.out.println("  User: " + rs.getString("db_user"));
            System.out.println("  Connection ID: " + rs.getLong("connection_id"));
        }
        return RestResponse.success(null);
    }
}
