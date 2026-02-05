package org.lix.mycatdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import tk.mybatis.spring.annotation.MapperScan;

@SpringBootApplication(
        exclude = {
                // 排除ShardingJDBC自动装配
                org.apache.shardingsphere.shardingjdbc.spring.boot.SpringBootConfiguration.class,
                // 可选：排除Spring Boot默认的数据源自动装配（Druid Starter已自定义，可加可不加）
                org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class
        }
)
@MapperScan(basePackages = "org.lix.mycatdemo.mapper",markerInterface = tk.mybatis.mapper.common.Mapper.class)

public class MycatDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(MycatDemoApplication.class, args);
    }

}
