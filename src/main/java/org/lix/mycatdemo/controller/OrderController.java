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

import java.math.BigDecimal;
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
}
