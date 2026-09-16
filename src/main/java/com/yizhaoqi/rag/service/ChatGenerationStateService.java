package com.yizhaoqi.rag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ChatGenerationStateService {

    private static final Logger logger = LoggerFactory.getLogger(ChatGenerationStateService.class);
    private static final Duration GENERATION_TTL = Duration.ofMinutes(30);
    private static final TypeReference<Map<String, Map<String, Object>>> REFERENCE_MAP_TYPE =
            new TypeReference<Map<String, Map<String, Object>>>() {};
    private static final TypeReference<List<Map<String, Object>>> RETRIEVAL_RESULT_LIST_TYPE =
            new TypeReference<List<Map<String, Object>>>() {};

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public ChatGenerationStateService(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public GenerationSnapshot createGeneration(String userId, String conversationId, String question) {
        String generationId = UUID.randomUUID().toString();
        String now = LocalDateTime.now().toString();
        GenerationMeta meta = new GenerationMeta(generationId, userId, conversationId, question, GenerationStatus.STREAMING, now, now, null);

        writeMeta(meta);
        redisTemplate.opsForValue().set(contentKey(generationId), "", GENERATION_TTL);
        redisTemplate.delete(referenceKey(generationId));
        redisTemplate.delete(retrievalKey(generationId));
        redisTemplate.opsForValue().set(activeGenerationKey(userId), generationId, GENERATION_TTL);
        return toSnapshot(meta, "", Collections.emptyMap(), Collections.emptyList());
    }

    public void appendChunk(String generationId, String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            touch(generationId);
            return;
        }

        redisTemplate.opsForValue().append(contentKey(generationId), chunk);
        touch(generationId);
    }

    public void updateReferenceMappings(String generationId, Map<String, Map<String, Object>> referenceMappings) {
        try {
            if (referenceMappings == null || referenceMappings.isEmpty()) {
                redisTemplate.delete(referenceKey(generationId));
            } else {
                redisTemplate.opsForValue().set(
                        referenceKey(generationId),
                        objectMapper.writeValueAsString(referenceMappings),
                        GENERATION_TTL
                );
            }
            touch(generationId);
        } catch (Exception e) {
            logger.warn("保存生成态引用映射失败: generationId={}", generationId, e);
        }
    }

    public void updateRetrievalResults(String generationId, List<Map<String, Object>> retrievalResults) {
        try {
            if (retrievalResults == null || retrievalResults.isEmpty()) {
                redisTemplate.delete(retrievalKey(generationId));
            } else {
                redisTemplate.opsForValue().set(
                        retrievalKey(generationId),
                        objectMapper.writeValueAsString(retrievalResults),
                        GENERATION_TTL
                );
            }
            touch(generationId);
        } catch (Exception e) {
            logger.warn("保存生成态检索溯源失败: generationId={}", generationId, e);
        }
    }

    public void markCompleted(String generationId, Map<String, Map<String, Object>> referenceMappings, List<Map<String, Object>> retrievalResults) {
        updateTerminalState(generationId, GenerationStatus.COMPLETED, null, referenceMappings, retrievalResults);
    }

    public void markFailed(String generationId, String errorMessage) {
        updateTerminalState(generationId, GenerationStatus.FAILED, errorMessage, null, null);
    }

    public void markCancelled(String generationId) {
        updateTerminalState(generationId, GenerationStatus.CANCELLED, null, null, null);
    }

    public Optional<GenerationSnapshot> getGeneration(String generationId) {
        GenerationMeta meta = readMeta(generationId);
        if (meta == null) {
            return Optional.empty();
        }

        String content = Optional.ofNullable(redisTemplate.opsForValue().get(contentKey(generationId))).orElse("");
        Map<String, Map<String, Object>> references = readReferenceMappings(generationId);
        List<Map<String, Object>> retrievalResults = readRetrievalResults(generationId);
        return Optional.of(toSnapshot(meta, content, references, retrievalResults));
    }

    public Optional<GenerationSnapshot> getGenerationForUser(String generationId, String userId) {
        return getGeneration(generationId).filter(snapshot -> snapshot.userId().equals(userId));
    }

    public Optional<GenerationSnapshot> getActiveGenerationForUser(String userId) {
        String generationId = redisTemplate.opsForValue().get(activeGenerationKey(userId));
        if (generationId == null || generationId.isBlank()) {
            return Optional.empty();
        }
        return getGenerationForUser(generationId, userId);
    }

    private void updateTerminalState(String generationId,
                                     GenerationStatus status,
                                     String errorMessage,
                                     Map<String, Map<String, Object>> referenceMappings,
                                     List<Map<String, Object>> retrievalResults) {
        GenerationMeta meta = readMeta(generationId);
        if (meta == null) {
            return;
        }

        if (referenceMappings != null) {
            updateReferenceMappings(generationId, referenceMappings);
        }
        if (retrievalResults != null) {
            updateRetrievalResults(generationId, retrievalResults);
        }
        if (referenceMappings == null && retrievalResults == null) {
            touch(generationId);
        }

        String now = LocalDateTime.now().toString();
        GenerationMeta updated = new GenerationMeta(
                meta.generationId(),
                meta.userId(),
                meta.conversationId(),
                meta.question(),
                status,
                meta.createdAt(),
                now,
                errorMessage
        );
        writeMeta(updated);
        clearActiveGeneration(meta.userId(), generationId);
    }

    private void touch(String generationId) {
        GenerationMeta meta = readMeta(generationId);
        if (meta == null) {
            return;
        }

        GenerationMeta updated = new GenerationMeta(
                meta.generationId(),
                meta.userId(),
                meta.conversationId(),
                meta.question(),
                meta.status(),
                meta.createdAt(),
                LocalDateTime.now().toString(),
                meta.errorMessage()
        );
        writeMeta(updated);
        redisTemplate.expire(contentKey(generationId), GENERATION_TTL);
        redisTemplate.expire(referenceKey(generationId), GENERATION_TTL);
        redisTemplate.expire(retrievalKey(generationId), GENERATION_TTL);
        redisTemplate.opsForValue().set(activeGenerationKey(meta.userId()), generationId, GENERATION_TTL);
    }

    private void clearActiveGeneration(String userId, String generationId) {
        String current = redisTemplate.opsForValue().get(activeGenerationKey(userId));
        if (generationId.equals(current)) {
            redisTemplate.delete(activeGenerationKey(userId));
        }
    }

    private void writeMeta(GenerationMeta meta) {
        try {
            redisTemplate.opsForValue().set(metaKey(meta.generationId()), objectMapper.writeValueAsString(meta), GENERATION_TTL);
        } catch (Exception e) {
            throw new IllegalStateException("保存生成态元数据失败", e);
        }
    }

    private GenerationMeta readMeta(String generationId) {
        String raw = redisTemplate.opsForValue().get(metaKey(generationId));
        if (raw == null || raw.isBlank()) {
            return null;
        }

        try {
            return objectMapper.readValue(raw, GenerationMeta.class);
        } catch (Exception e) {
            logger.warn("解析生成态元数据失败: generationId={}", generationId, e);
            return null;
        }
    }

    private Map<String, Map<String, Object>> readReferenceMappings(String generationId) {
        String raw = redisTemplate.opsForValue().get(referenceKey(generationId));
        if (raw == null || raw.isBlank()) {
            return Collections.emptyMap();
        }

        try {
            return objectMapper.readValue(raw, REFERENCE_MAP_TYPE);
        } catch (Exception e) {
            logger.warn("解析生成态引用映射失败: generationId={}", generationId, e);
            return Collections.emptyMap();
        }
    }

    private List<Map<String, Object>> readRetrievalResults(String generationId) {
        String raw = redisTemplate.opsForValue().get(retrievalKey(generationId));
        if (raw == null || raw.isBlank()) {
            return Collections.emptyList();
        }

        try {
            return objectMapper.readValue(raw, RETRIEVAL_RESULT_LIST_TYPE);
        } catch (Exception e) {
            logger.warn("解析生成态检索溯源失败: generationId={}", generationId, e);
            return Collections.emptyList();
        }
    }

    private GenerationSnapshot toSnapshot(GenerationMeta meta,
                                          String content,
                                          Map<String, Map<String, Object>> referenceMappings,
                                          List<Map<String, Object>> retrievalResults) {
        return new GenerationSnapshot(
                meta.generationId(),
                meta.userId(),
                meta.conversationId(),
                meta.question(),
                meta.status(),
                content,
                meta.createdAt(),
                meta.updatedAt(),
                meta.errorMessage(),
                referenceMappings == null ? Collections.emptyMap() : referenceMappings,
                retrievalResults == null ? Collections.emptyList() : retrievalResults
        );
    }

    private String metaKey(String generationId) {
        return "chat:generation:" + generationId + ":meta";
    }

    private String contentKey(String generationId) {
        return "chat:generation:" + generationId + ":content";
    }

    private String referenceKey(String generationId) {
        return "chat:generation:" + generationId + ":refs";
    }

    private String retrievalKey(String generationId) {
        return "chat:generation:" + generationId + ":retrieval";
    }

    private String activeGenerationKey(String userId) {
        return "chat:user:" + userId + ":active_generation";
    }

    public enum GenerationStatus {
        STREAMING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public record GenerationMeta(
            String generationId,
            String userId,
            String conversationId,
            String question,
            GenerationStatus status,
            String createdAt,
            String updatedAt,
            String errorMessage
    ) {
    }

    public record GenerationSnapshot(
            String generationId,
            String userId,
            String conversationId,
            String question,
            GenerationStatus status,
            String content,
            String createdAt,
            String updatedAt,
            String errorMessage,
            Map<String, Map<String, Object>> referenceMappings,
            List<Map<String, Object>> retrievalResults
    ) {
    }
}
