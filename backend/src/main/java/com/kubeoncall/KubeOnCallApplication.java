package com.kubeoncall;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

import com.kubeoncall.common.config.KubeOnCallProperties;

@SpringBootApplication
@EnableConfigurationProperties(KubeOnCallProperties.class)
@EnableAsync
public class KubeOnCallApplication {

    public static void main(String[] args) {
        SpringApplication.run(KubeOnCallApplication.class, args);
    }
}
