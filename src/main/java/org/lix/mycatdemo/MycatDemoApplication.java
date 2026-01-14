package org.lix.mycatdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import tk.mybatis.spring.annotation.MapperScan;

@SpringBootApplication
@MapperScan(basePackages = "org.lix.mycatdemo.mapper",markerInterface = tk.mybatis.mapper.common.Mapper.class)
public class MycatDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(MycatDemoApplication.class, args);
    }

}
