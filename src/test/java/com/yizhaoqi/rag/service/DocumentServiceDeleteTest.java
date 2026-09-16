package com.yizhaoqi.rag.service;

import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.yizhaoqi.rag.model.FileUpload;
import com.yizhaoqi.rag.repository.ChunkInfoRepository;
import com.yizhaoqi.rag.repository.DocumentVectorRepository;
import com.yizhaoqi.rag.repository.FileUploadRepository;
import com.yizhaoqi.rag.service.DocumentService;
import com.yizhaoqi.rag.service.ElasticsearchService;
import com.yizhaoqi.rag.service.UploadService;

import java.time.Duration;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentServiceDeleteTest {

    @Test
    void deleteDocumentShouldRemoveChunkMetadataAndMarkDeletion() {
        FileUploadRepository fileUploadRepository = mock(FileUploadRepository.class);
        DocumentVectorRepository documentVectorRepository = mock(DocumentVectorRepository.class);
        ChunkInfoRepository chunkInfoRepository = mock(ChunkInfoRepository.class);
        MinioClient minioClient = mock(MinioClient.class);
        ElasticsearchService elasticsearchService = mock(ElasticsearchService.class);
        UploadService uploadService = mock(UploadService.class);
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        DocumentService service = new DocumentService();
        ReflectionTestUtils.setField(service, "fileUploadRepository", fileUploadRepository);
        ReflectionTestUtils.setField(service, "documentVectorRepository", documentVectorRepository);
        ReflectionTestUtils.setField(service, "chunkInfoRepository", chunkInfoRepository);
        ReflectionTestUtils.setField(service, "minioClient", minioClient);
        ReflectionTestUtils.setField(service, "elasticsearchService", elasticsearchService);
        ReflectionTestUtils.setField(service, "uploadService", uploadService);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(service, "kafkaTemplate", kafkaTemplate);

        FileUpload fileUpload = new FileUpload();
        fileUpload.setFileMd5("abc123");
        fileUpload.setFileName("demo.pdf");
        fileUpload.setUserId("user-1");

        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("abc123", "user-1"))
                .thenReturn(Optional.of(fileUpload));

        service.deleteDocument("abc123", "user-1");

        verify(chunkInfoRepository).deleteByFileMd5("abc123");
        verify(documentVectorRepository).deleteByFileMd5("abc123");
        verify(uploadService).deleteFileMark("abc123", "user-1");
        verify(valueOperations).set(eq("document:deleted:abc123"), eq("user-1"), any(Duration.class));
    }
}
