package com.yizhaoqi.rag.service;

import com.yizhaoqi.rag.chunker.DocumentType;
import com.yizhaoqi.rag.config.MinioConfig;
import com.yizhaoqi.rag.model.FileUpload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentStorageServiceTest {

    private DocumentStorageService storageService;

    @BeforeEach
    void setUp() {
        storageService = new DocumentStorageService();
        MinioConfig minioConfig = mock(MinioConfig.class);
        when(minioConfig.getBucketName()).thenReturn("uploads");
        when(minioConfig.getPrivateBucketName()).thenReturn("private-uploads");
        ReflectionTestUtils.setField(storageService, "minioConfig", minioConfig);
    }

    @Test
    void publicLawUsesPublicBucketAndPrefix() {
        FileUpload file = fileUpload("md5", "7", DocumentType.STRUCTURED_LAW);

        DocumentStorageService.StorageLocation location = storageService.resolve(file);

        assertEquals("uploads", location.bucket());
        assertEquals("merged/md5", location.mergedObjectKey());
        assertEquals("chunks/", location.chunkPrefix());
    }

    @Test
    void privateCaseUsesPrivateBucketAndUserScopedPrefix() {
        FileUpload file = fileUpload("md5", "7", DocumentType.PRIVATE_LEGAL);

        DocumentStorageService.StorageLocation location = storageService.resolve(file);

        assertEquals("private-uploads", location.bucket());
        assertEquals("private/7/merged/md5", location.mergedObjectKey());
        assertEquals("private/7/chunks/", location.chunkPrefix());
        assertNotEquals("uploads", location.bucket());
    }

    private FileUpload fileUpload(String fileMd5, String userId, DocumentType type) {
        FileUpload file = new FileUpload();
        file.setFileMd5(fileMd5);
        file.setUserId(userId);
        file.setDocumentTypeEnum(type);
        return file;
    }
}
