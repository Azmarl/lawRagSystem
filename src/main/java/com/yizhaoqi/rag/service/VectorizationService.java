package com.yizhaoqi.rag.service;

import com.yizhaoqi.rag.chunker.DocumentType;
import com.yizhaoqi.rag.client.EmbeddingClient;
import com.yizhaoqi.rag.entity.EsDocument;
import com.yizhaoqi.rag.entity.TextChunk;
import com.yizhaoqi.rag.model.DocumentVector;
import com.yizhaoqi.rag.model.FileUpload;
import com.yizhaoqi.rag.repository.DocumentVectorRepository;
import com.yizhaoqi.rag.repository.FileUploadRepository;
import com.yizhaoqi.rag.repository.PrivateDocumentVectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

// 向量化服务类
@Service
public class VectorizationService {

    private static final Logger logger = LoggerFactory.getLogger(VectorizationService.class);

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired
    private PrivateDocumentVectorRepository privateDocumentVectorRepository;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    /**
     * 执行向量化操作（向后兼容：自动读取文件记录中的文档类型）。
     */
    public void vectorize(String fileMd5, String userId, String orgTag, boolean isPublic) {
        vectorizeWithUsage(fileMd5, userId, orgTag, isPublic, userId);
    }

    public void vectorize(String fileMd5, String userId, String orgTag, boolean isPublic, String requesterId) {
        vectorizeWithUsage(fileMd5, userId, orgTag, isPublic, requesterId);
    }

    public VectorizationUsageResult vectorizeWithUsage(String fileMd5, String userId, String orgTag,
            boolean isPublic, String requesterId) {
        DocumentType documentType = fileUploadRepository
                .findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, userId)
                .map(FileUpload::getDocumentTypeEnum)
                .orElse(DocumentType.STRUCTURED_LAW);
        return vectorizeWithUsage(fileMd5, userId, orgTag, isPublic, requesterId, documentType);
    }

    public VectorizationUsageResult vectorizeWithUsage(String fileMd5, String userId, String orgTag,
            boolean isPublic, String requesterId, DocumentType documentType) {
        DocumentType resolvedType = documentType == null ? DocumentType.STRUCTURED_LAW : documentType;
        try {
            logger.info("开始向量化文件，fileMd5: {}, userId: {}, orgTag: {}, isPublic: {}, documentType: {}",
                    fileMd5, userId, orgTag, isPublic, resolvedType);

            // 获取对应隔离表/索引中的分块
            List<TextChunk> chunks = fetchTextChunks(fileMd5, userId, resolvedType);
            if (chunks == null || chunks.isEmpty()) {
                logger.warn("未找到分块内容，fileMd5: {}, documentType: {}", fileMd5, resolvedType);
                return new VectorizationUsageResult(0, 0, embeddingClient.currentModelVersion());
            }

            List<String> texts = chunks.stream()
                    .map(TextChunk::getContent)
                    .toList();

            EmbeddingClient.EmbeddingUsageResult embeddingResult = embeddingClient.embedWithUsage(
                    texts,
                    requesterId,
                    EmbeddingClient.UsageType.UPLOAD
            );
            List<float[]> vectors = embeddingResult.vectors();

            List<EsDocument> esDocuments = IntStream.range(0, chunks.size())
                    .mapToObj(i -> {
                        TextChunk chunk = chunks.get(i);
                        EsDocument esDocument = new EsDocument(
                                UUID.randomUUID().toString(),
                                fileMd5,
                                chunk.getChunkId(),
                                chunk.getContent(),
                                chunk.getPageNumber(),
                                chunk.getAnchorText(),
                                vectors.get(i),
                                embeddingResult.modelVersion(),
                                userId,
                                orgTag,
                                !resolvedType.isPrivateDocument() && isPublic,
                                chunk.getChunkLevel(),
                                chunk.getPartName(),
                                chunk.getChapterName(),
                                chunk.getSectionName(),
                                chunk.getArticleId(),
                                chunk.getArticleIdDensity(),
                                chunk.getLegalTermDensity(),
                                resolvedType.name(),
                                chunk.getParentBlockId(),
                                chunk.getChunkPosition()
                        );
                        esDocument.setChunkStrategy(chunk.getChunkStrategy());
                        esDocument.setCaseSection(chunk.getCaseSection());
                        esDocument.setBlockId(chunk.getBlockId());
                        esDocument.setChunkRole(chunk.getChunkRole());
                        esDocument.setContentType(chunk.getContentType());
                        return esDocument;
                    })
                    .toList();

            elasticsearchService.bulkIndex(esDocuments);

            logger.info("向量化完成，fileMd5: {}, documentType: {}, index: {}",
                    fileMd5, resolvedType,
                    resolvedType.isPrivateDocument() ? ElasticsearchService.PRIVATE_INDEX : ElasticsearchService.PUBLIC_INDEX);
            return new VectorizationUsageResult(
                    embeddingResult.totalTokens(),
                    chunks.size(),
                    embeddingResult.modelVersion()
            );
        } catch (Exception e) {
            logger.error("向量化失败，fileMd5: {}, documentType: {}", fileMd5, resolvedType, e);
            String message = e.getMessage();
            if (message == null || message.isBlank()) {
                throw new RuntimeException("向量化失败", e);
            }
            throw new RuntimeException("向量化失败: " + message, e);
        }
    }

    /**
     * 获取文件分块内容（私人案件从独立表读取）。
     */
    private List<TextChunk> fetchTextChunks(String fileMd5, String userId, DocumentType documentType) {
        List<DocumentVector> vectors = new ArrayList<>();
        if (documentType.isPrivateDocument()) {
            vectors.addAll(privateDocumentVectorRepository
                    .findByFileMd5AndUserIdOrderByChunkIdAsc(fileMd5, userId));
        } else {
            vectors.addAll(documentVectorRepository.findByFileMd5OrderByChunkIdAsc(fileMd5));
        }

        return vectors.stream()
                // 父块只用于上下文回补，不做 Embedding / 不进入 ES，避免父块被直接召回
                .filter(vector -> !"PARENT".equalsIgnoreCase(vector.getChunkRole()))
                .map(vector -> {
                    TextChunk chunk = new TextChunk(
                            vector.getChunkId(),
                            vector.getTextContent(),
                            vector.getPageNumber(),
                            vector.getAnchorText()
                    );
                    chunk.setChunkLevel(vector.getChunkLevel());
                    chunk.setPartName(vector.getPartName());
                    chunk.setChapterName(vector.getChapterName());
                    chunk.setSectionName(vector.getSectionName());
                    chunk.setArticleId(vector.getArticleId());
                    chunk.setArticleIdDensity(vector.getArticleIdDensity());
                    chunk.setLegalTermDensity(vector.getLegalTermDensity());
                    chunk.setParentBlockId(vector.getParentBlockId());
                    chunk.setChunkPosition(vector.getChunkPosition());
                    chunk.setDocumentType(vector.getDocumentType() == null
                            ? documentType.name() : vector.getDocumentType());
                    chunk.setChunkStrategy(vector.getChunkStrategy());
                    chunk.setCaseSection(vector.getCaseSection());
                    chunk.setBlockId(vector.getBlockId());
                    chunk.setChunkRole(vector.getChunkRole());
                    chunk.setContentType(vector.getContentType());
                    return chunk;
                })
                .toList();
    }

    public record VectorizationUsageResult(int actualEmbeddingTokens, int actualChunkCount, String modelVersion) {
    }
}
