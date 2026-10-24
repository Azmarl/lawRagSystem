package com.yizhaoqi.rag.model;

import com.yizhaoqi.rag.chunker.DocumentType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件处理任务类，用于Kafka消息传递
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class FileProcessingTask {
    public static final String TASK_TYPE_UPLOAD_PROCESS = "UPLOAD_PROCESS";
    public static final String TASK_TYPE_REINDEX = "REINDEX";

    private String fileMd5; // 文件的 MD5 校验值
    private String filePath; // 文件存储路径
    private String fileName; // 文件名
    private String userId;   // 上传用户ID
    private String orgTag;   // 文件所属组织标签
    private boolean isPublic; // 文件是否公开
    private String taskType; // 任务类型
    private String requesterId; // 发起重试的用户
    private String documentType; // 文档类型：STRUCTURED_LAW / PRIVATE_LEGAL

    public DocumentType resolveDocumentType() {
        return DocumentType.from(documentType);
    }

    /**
     * 向后兼容的构造函数（默认按法律文档处理）。
     */
    public FileProcessingTask(String fileMd5, String filePath, String fileName) {
        this(fileMd5, filePath, fileName, null, "DEFAULT", false,
                TASK_TYPE_UPLOAD_PROCESS, null, DocumentType.STRUCTURED_LAW.name());
    }

    /**
     * 向后兼容的构造函数（历史调用方未传 documentType）。
     */
    public FileProcessingTask(String fileMd5, String filePath, String fileName, String userId,
                              String orgTag, boolean isPublic, String taskType, String requesterId) {
        this(fileMd5, filePath, fileName, userId, orgTag, isPublic,
                taskType, requesterId, DocumentType.STRUCTURED_LAW.name());
    }
}
