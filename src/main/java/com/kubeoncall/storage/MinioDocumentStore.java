package com.kubeoncall.storage;

import com.kubeoncall.common.config.KubeOnCallProperties;
import io.minio.MinioClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioDocumentStore {

    @Bean
    public MinioClient minioClient(KubeOnCallProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.getStorage().getMinio().getEndpoint())
                .credentials(properties.getStorage().getMinio().getAccessKey(), properties.getStorage().getMinio().getSecretKey())
                .build();
    }
}
