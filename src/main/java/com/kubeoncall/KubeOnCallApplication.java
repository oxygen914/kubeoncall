package com.kubeoncall;

import com.kubeoncall.common.config.KubeOnCallProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(KubeOnCallProperties.class)
public class KubeOnCallApplication {

    public static void main(String[] args) {
        SpringApplication.run(KubeOnCallApplication.class, args);
    }
}
