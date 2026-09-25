package com.yizhaoqi.rag.service;

import com.yizhaoqi.rag.client.EmbeddingClient;
import com.yizhaoqi.rag.model.DocumentVector;
import com.yizhaoqi.rag.entity.EsDocument;
import com.yizhaoqi.rag.entity.TextChunk;
import com.yizhaoqi.rag.repository.DocumentVectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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

    /**
     * 执行向量化操作
     * @param fileMd5 文件指纹
     * @param userId 上传用户ID
     * @param orgTag 组织标签
     * @param isPublic 是否公开
     */
    public void vectorize(String fileMd5, String userId, String orgTag, boolean isPublic) {
        vectorizeWithUsage(fileMd5, userId, orgTag, isPublic, userId);
    }

    public void vectorize(String fileMd5, String userId, String orgTag, boolean isPublic, String requesterId) {
        vectorizeWithUsage(fileMd5, userId, orgTag, isPublic, requesterId);
    }

    public VectorizationUsageResult vectorizeWithUsage(String fileMd5, String userId, String orgTag, boolean isPublic, String requesterId) {
        try {
            logger.info("开始向量化文件，fileMd5: {}, userId: {}, orgTag: {}, isPublic: {}", 
                       fileMd5, userId, orgTag, isPublic);
                       
            // 获取文件分块内容（含法律层级元数据）
            List<TextChunk> chunks = fetchTextChunks(fileMd5);
            if (chunks == null || chunks.isEmpty()) {
                logger.warn("未找到分块内容，fileMd5: {}", fileMd5);
                return new VectorizationUsageResult(0, 0, embeddingClient.currentModelVersion());
            }

            // 提取文本内容
            List<String> texts = chunks.stream()
                    .map(TextChunk::getContent)
                    .toList();

            // 调用外部模型生成向量
            EmbeddingClient.EmbeddingUsageResult embeddingResult = embeddingClient.embedWithUsage(
                    texts,
                    requesterId,
                    EmbeddingClient.UsageType.UPLOAD
            );
            List<float[]> vectors = embeddingResult.vectors();

            // 构建 Elasticsearch 文档并存储（携带法律层级元数据）
            List<EsDocument> esDocuments = IntStream.range(0, chunks.size())
                    .mapToObj(i -> {
                        TextChunk chunk = chunks.get(i);
                        return new EsDocument(
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
                                isPublic,
                                // 法律层级元数据
                                chunk.getChunkLevel(),
                                chunk.getPartName(),
                                chunk.getChapterName(),
                                chunk.getSectionName(),
                                chunk.getArticleId(),
                                chunk.getArticleIdDensity(),
                                chunk.getLegalTermDensity(),
                                "private".equals(chunk.getChunkLevel()) ? "PRIVATE_LEGAL" : "STRUCTURED_LAW",
                                chunk.getParentBlockId(),
                                chunk.getChunkPosition()
                        );
                    })
                    .toList();

            elasticsearchService.bulkIndex(esDocuments); // 批量存储到 Elasticsearch

            logger.info("向量化完成，fileMd5: {}", fileMd5);
            return new VectorizationUsageResult(
                    embeddingResult.totalTokens(),
                    chunks.size(),
                    embeddingResult.modelVersion()
            );
        } catch (Exception e) {
            logger.error("向量化失败，fileMd5: {}", fileMd5, e);
            String message = e.getMessage();
            if (message == null || message.isBlank()) {
                throw new RuntimeException("向量化失败", e);
            }
            throw new RuntimeException("向量化失败: " + message, e);
        }
    }
    

    /**
     * 获取文件分块内容（含法律层级元数据）
     * @param fileMd5 文件指纹
     * @return 分块内容列表
     */
    private List<TextChunk> fetchTextChunks(String fileMd5) {
        // 调用 Repository 查询数据
        List<DocumentVector> vectors = documentVectorRepository.findByFileMd5OrderByChunkIdAsc(fileMd5);

        // 转换为 TextChunk 列表，携带法律层级元数据
        return vectors.stream()
                .map(vector -> {
                    TextChunk chunk = new TextChunk(
                            vector.getChunkId(),
                            vector.getTextContent(),
                            vector.getPageNumber(),
                            vector.getAnchorText()
                    );
                    // 法律层级元数据
                    chunk.setChunkLevel(vector.getChunkLevel());
                    chunk.setPartName(vector.getPartName());
                    chunk.setChapterName(vector.getChapterName());
                    chunk.setSectionName(vector.getSectionName());
                    chunk.setArticleId(vector.getArticleId());
                    chunk.setArticleIdDensity(vector.getArticleIdDensity());
                    chunk.setLegalTermDensity(vector.getLegalTermDensity());
                    chunk.setParentBlockId(vector.getParentBlockId());
                    chunk.setChunkPosition(vector.getChunkPosition());
                    return chunk;
                })
                .toList();
    }

    public record VectorizationUsageResult(int actualEmbeddingTokens, int actualChunkCount, String modelVersion) {
    }
}