package com.yizhaoqi.rag.config;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.MakeBucketArgs;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import jakarta.annotation.PostConstruct;

@Getter
@Configuration
public class MinioConfig {

    private static final Logger logger = LoggerFactory.getLogger(MinioConfig.class);

    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.accessKey}")
    private String accessKey;

    @Value("${minio.secretKey}")
    private String secretKey;

    @Value("${minio.bucketName}")
    private String bucketName;

    @Value("${minio.publicUrl}")
    private String publicUrl;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    @PostConstruct
    public void ensureBucketExists() {
        try {
            if (bucketName == null || bucketName.isBlank()) {
                throw new IllegalStateException("minio.bucketName must be configured");
            }
            MinioClient bucketCheckClient = MinioClient.builder()
                    .endpoint(endpoint)
                    .credentials(accessKey, secretKey)
                    .build();
            boolean exists = bucketCheckClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucketName).build()
            );
            if (!exists) {
                logger.info("MinIO bucket '{}' 不存在，正在创建", bucketName);
                bucketCheckClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                logger.info("MinIO bucket '{}' 已创建", bucketName);
            } else {
                logger.info("MinIO bucket '{}' 已存在", bucketName);
            }
        } catch (Exception e) {
            logger.error("MinIO 桶检查/创建失败: {}", e.getMessage(), e);
            throw new RuntimeException("MinIO bucket initialization failed", e);
        }
    }

    @Bean
    public String minioPublicUrl() {
        return publicUrl;
    }
}
