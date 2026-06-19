package cn.jualn.miniapp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("cn.jualn.miniapp.module.**.mapper")
public class MiniAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiniAppApplication.class, args);
    }

}
