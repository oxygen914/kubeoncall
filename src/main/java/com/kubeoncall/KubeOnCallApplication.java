package com.kubeoncall;

import com.kubeoncall.common.config.KubeOnCallProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(KubeOnCallProperties.class)
@EnableAsync
@EnableScheduling
public class KubeOnCallApplication {

    public static void main(String[] args) {
        SpringApplication.run(KubeOnCallApplication.class, args);
    }
}
