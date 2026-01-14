show databases ;

create database `order_db` ;

use order_db;

create table `t_order_1` (
                             `order_id` bigint(20) not null comment '订单id',
                             `price` decimal(10, 2) not null comment '订单价格',
                             `user_id` bigint(20) not null comment '下单用户id',
                             `status` varchar(20) not null comment '状态',
                             primary key (`order_id`)
)engine = InnoDB comment '订单表1';

create table `t_order_2` (
                             `order_id` bigint(20) not null comment '订单id',
                             `price` decimal(10, 2) not null comment '订单价格',
                             `user_id` bigint(20) not null comment '下单用户id',
                             `status` varchar(20) not null comment '状态',
                             primary key (`order_id`)
)engine = InnoDB comment '订单表2';

create database `order_db_1` ;

use order_db_1;

create table `t_order_1` (
                             `order_id` bigint(20) not null comment '订单id',
                             `price` decimal(10, 2) not null comment '订单价格',
                             `user_id` bigint(20) not null comment '下单用户id',
                             `status` varchar(20) not null comment '状态',
                             primary key (`order_id`)
)engine = InnoDB comment '订单表1';

create table `t_order_2` (
                             `order_id` bigint(20) not null comment '订单id',
                             `price` decimal(10, 2) not null comment '订单价格',
                             `user_id` bigint(20) not null comment '下单用户id',
                             `status` varchar(20) not null comment '状态',
                             primary key (`order_id`)
)engine = InnoDB comment '订单表2';