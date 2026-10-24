package com.yizhaoqi.rag.service;

import com.yizhaoqi.rag.chunker.DocumentType;
import com.yizhaoqi.rag.config.MinioConfig;
import com.yizhaoqi.rag.model.FileUpload;
import io.minio.ComposeObjectArgs;
import io.minio.ComposeSource;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 文档对象存储路由。
 *
 * <p>公开法典对象写入 {@code minio.bucketName}，私人案件对象写入
 * {@code minio.privateBucketName}，并在对象 key 中再使用 {@code private/{userId}/} 前缀，
 * 实现 bucket + prefix 双层隔离。数据库仅保存 bucket / objectKey，读取时不依赖文件名。</p>
 */
@Service
public class DocumentStorageService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentStorageService.class);

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private MinioConfig minioConfig;

    /**
     * 根据文档类型与文件记录解析对象存储位置。
     */
    public StorageLocation resolve(FileUpload fileUpload) {
        if (fileUpload == null) {
            throw new IllegalArgumentException("fileUpload must not be null");
        }
        DocumentType documentType = fileUpload.getDocumentTypeEnum();
        String bucket = hasText(fileUpload.getStorageBucket())
                ? fileUpload.getStorageBucket()
                : (documentType.isPrivateDocument() ? minioConfig.getPrivateBucketName() : minioConfig.getBucketName());
        String mergedKey = hasText(fileUpload.getStorageObjectKey())
                ? fileUpload.getStorageObjectKey()
                : defaultMergedKey(documentType, fileUpload.getUserId(), fileUpload.getFileMd5());
        return new StorageLocation(bucket, mergedKey, chunkPrefix(documentType, fileUpload.getUserId()));
    }

    public StorageLocation resolve(DocumentType documentType, String userId, String fileMd5) {
        DocumentType resolved = documentType == null ? DocumentType.STRUCTURED_LAW : documentType;
        String bucket = resolved.isPrivateDocument()
                ? minioConfig.getPrivateBucketName()
                : minioConfig.getBucketName();
        return new StorageLocation(bucket,
                defaultMergedKey(resolved, userId, fileMd5),
                chunkPrefix(resolved, userId));
    }

    public String chunkObjectKey(FileUpload fileUpload, int chunkIndex) {
        return resolve(fileUpload).chunkPrefix() + fileUpload.getFileMd5() + "/" + chunkIndex;
    }

    public void uploadChunk(FileUpload fileUpload, int chunkIndex, MultipartFile file) throws Exception {
        StorageLocation location = resolve(fileUpload);
        String objectKey = location.chunkPrefix() + fileUpload.getFileMd5() + "/" + chunkIndex;
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(location.bucket())
                .object(objectKey)
                .stream(file.getInputStream(), file.getSize(), -1)
                .contentType(file.getContentType())
                .build());
        logger.info("分片写入对象存储完成: bucket={}, object={}, fileMd5={}, chunkIndex={}",
                location.bucket(), objectKey, fileUpload.getFileMd5(), chunkIndex);
    }

    public StatObjectResponse stat(FileUpload fileUpload, String objectKey) throws Exception {
        StorageLocation location = resolve(fileUpload);
        return minioClient.statObject(StatObjectArgs.builder()
                .bucket(location.bucket())
                .object(objectKey)
                .build());
    }

    public StatObjectResponse statMerged(FileUpload fileUpload) throws Exception {
        StorageLocation location = resolve(fileUpload);
        return minioClient.statObject(StatObjectArgs.builder()
                .bucket(location.bucket())
                .object(location.mergedObjectKey())
                .build());
    }

    public void composeMerged(FileUpload fileUpload, List<String> partObjectKeys) throws Exception {
        StorageLocation location = resolve(fileUpload);
        List<ComposeSource> sources = partObjectKeys.stream()
                .map(path -> ComposeSource.builder().bucket(location.bucket()).object(path).build())
                .toList();
        minioClient.composeObject(ComposeObjectArgs.builder()
                .bucket(location.bucket())
                .object(location.mergedObjectKey())
                .sources(sources)
                .build());
        logger.info("分片合并完成: bucket={}, object={}, parts={}",
                location.bucket(), location.mergedObjectKey(), partObjectKeys.size());
    }

    public GetObjectResponse getMerged(FileUpload fileUpload) throws Exception {
        StorageLocation location = resolve(fileUpload);
        return minioClient.getObject(GetObjectArgs.builder()
                .bucket(location.bucket())
                .object(location.mergedObjectKey())
                .build());
    }

    public String presignMerged(FileUpload fileUpload) throws Exception {
        StorageLocation location = resolve(fileUpload);
        return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(location.bucket())
                .object(location.mergedObjectKey())
                .expiry(1, TimeUnit.HOURS)
                .build());
    }

    public void deleteMerged(FileUpload fileUpload) throws Exception {
        StorageLocation location = resolve(fileUpload);
        minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(location.bucket())
                .object(location.mergedObjectKey())
                .build());
        logger.info("已删除合并文件: bucket={}, object={}", location.bucket(), location.mergedObjectKey());
    }

    public void deleteObject(FileUpload fileUpload, String objectKey) throws Exception {
        StorageLocation location = resolve(fileUpload);
        minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(location.bucket())
                .object(objectKey)
                .build());
    }

    /**
     * 兼容历史记录：旧数据没有 document_type / storage_bucket / storage_object_key，
     * 统一按公开 bucket 的 merged/{md5} 读取。
     */
    public StorageLocation resolveLegacyPublic(String fileMd5) {
        return new StorageLocation(minioConfig.getBucketName(), "merged/" + fileMd5, "chunks/");
    }

    private String defaultMergedKey(DocumentType type, String userId, String fileMd5) {
        if (type != null && type.isPrivateDocument()) {
            return "private/" + safeSegment(userId) + "/merged/" + fileMd5;
        }
        return "merged/" + fileMd5;
    }

    private String chunkPrefix(DocumentType type, String userId) {
        if (type != null && type.isPrivateDocument()) {
            return "private/" + safeSegment(userId) + "/chunks/";
        }
        return "chunks/";
    }

    private String safeSegment(String value) {
        if (value == null || value.isBlank()) {
            return "anonymous";
        }
        String safe = value.replaceAll("[^a-zA-Z0-9_-]", "_");
        return safe.length() <= 64 ? safe : safe.substring(0, 64);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record StorageLocation(String bucket, String mergedObjectKey, String chunkPrefix) {
    }
}
