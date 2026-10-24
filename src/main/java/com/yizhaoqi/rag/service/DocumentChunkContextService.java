package com.yizhaoqi.rag.service;

import com.yizhaoqi.rag.chunker.ChunkRole;
import com.yizhaoqi.rag.model.DocumentVector;
import com.yizhaoqi.rag.repository.DocumentVectorRepository;
import com.yizhaoqi.rag.repository.PrivateDocumentVectorRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 父子分块的父段上下文读取。
 *
 * <p>父段只存 MySQL，不进入 ES、不参与 Embedding；检索命中子块后，通过 parentBlockId
 * 回查父段原文，供 ChatHandler 构造更完整的 LLM 上下文。</p>
 */
@Service
public class DocumentChunkContextService {

    @Autowired
    private DocumentVectorRepository publicDocumentVectorRepository;

    @Autowired
    private PrivateDocumentVectorRepository privateDocumentVectorRepository;

    public Map<String, String> findParentTexts(Collection<String> blockIds,
            boolean privateDocument, String userId) {
        if (blockIds == null || blockIds.isEmpty()) {
            return Map.of();
        }

        List<DocumentVector> vectors = new ArrayList<>();
        if (privateDocument) {
            vectors.addAll(privateDocumentVectorRepository.findByBlockIdInAndUserId(blockIds, userId));
        } else {
            vectors.addAll(publicDocumentVectorRepository.findByBlockIdIn(blockIds));
        }

        Map<String, String> result = new LinkedHashMap<>();
        for (DocumentVector vector : vectors) {
            if (vector.getBlockId() == null || vector.getTextContent() == null) {
                continue;
            }
            if (vector.getChunkRole() != null
                    && !ChunkRole.PARENT.name().equalsIgnoreCase(vector.getChunkRole())) {
                continue;
            }
            result.putIfAbsent(vector.getBlockId(), vector.getTextContent());
        }
        return result;
    }
}
