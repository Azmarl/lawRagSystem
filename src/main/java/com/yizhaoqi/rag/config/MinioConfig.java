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

    /**
     * 私人案件文档独立 bucket。即使配置成与公开 bucket 相同，
     * {@link com.yizhaoqi.rag.service.DocumentStorageService} 仍会使用 private/ 前缀做二次隔离。
     */
    @Value("${minio.privateBucketName:private-uploads}")
    private String privateBucketName;

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
            ensureBucket(bucketCheckClient, bucketName);
            if (privateBucketName != null && !privateBucketName.isBlank()) {
                ensureBucket(bucketCheckClient, privateBucketName);
            }
        } catch (Exception e) {
            logger.error("MinIO 桶检查/创建失败: {}", e.getMessage(), e);
            throw new RuntimeException("MinIO bucket initialization failed", e);
        }
    }

    private void ensureBucket(MinioClient client, String bucket) throws Exception {
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            logger.info("MinIO bucket '{}' 不存在，正在创建", bucket);
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            logger.info("MinIO bucket '{}' 已创建", bucket);
        } else {
            logger.info("MinIO bucket '{}' 已存在", bucket);
        }
    }

    @Bean
    public String minioPublicUrl() {
        return publicUrl;
    }
}
