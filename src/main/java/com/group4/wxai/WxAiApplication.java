package com.group4.wxai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class WxAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(WxAiApplication.class, args);
    }
}
