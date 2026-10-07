package cn.jualn.miniapp;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.annotation.MapperScans;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScans({
        @MapperScan("cn.jualn.miniapp.module.**.mapper"),
        @MapperScan(basePackages = "cn.jualn.miniapp.infrastructure.async", annotationClass = Mapper.class)
})
public class MiniAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiniAppApplication.class, args);
    }

}
