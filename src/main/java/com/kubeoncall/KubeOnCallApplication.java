package com.kubeoncall;

import com.kubeoncall.common.config.KubeOnCallProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableConfigurationProperties(KubeOnCallProperties.class)
@EnableAsync
public class KubeOnCallApplication {

    public static void main(String[] args) {
        SpringApplication.run(KubeOnCallApplication.class, args);
    }
}
