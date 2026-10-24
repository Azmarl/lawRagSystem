package com.yizhaoqi.rag.parser.strategy;

import com.yizhaoqi.rag.chunker.ChunkDTO;
import com.yizhaoqi.rag.chunker.ChunkStrategy;
import com.yizhaoqi.rag.chunker.DocumentType;
import com.yizhaoqi.rag.chunker.LegalTermScorer;
import com.yizhaoqi.rag.chunker.TreeAwareChunker;
import com.yizhaoqi.rag.parser.LegalNode;
import com.yizhaoqi.rag.parser.LegalStructureParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 法律文档解析策略：保留现有的“结构解析（编/章/节/条）→ 树状多级分块”流程。
 */
@Component
public class StructuredLegalParsingStrategy implements DocumentParsingStrategy {

    private static final Logger logger = LoggerFactory.getLogger(StructuredLegalParsingStrategy.class);

    private final LegalStructureParser parser = new LegalStructureParser();
    private final TreeAwareChunker chunker;

    public StructuredLegalParsingStrategy(
            @Value("${file.parsing.chunk-size:512}") int chunkSize,
            @Value("${file.parsing.overlap-size:100}") int overlapSize) {
        this.chunker = new TreeAwareChunker(new LegalTermScorer(), chunkSize, overlapSize);
    }

    @Override
    public DocumentType documentType() {
        return DocumentType.STRUCTURED_LAW;
    }

    @Override
    public List<ChunkDTO> parse(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        LegalNode root = parser.parse(text);
        List<ChunkDTO> chunks = new ArrayList<>(chunker.chunk(root));
        if (chunks.isEmpty()) {
            logger.info("法律文档未识别出编/章/节/条层级，将由保底分块处理");
            return List.of();
        }
        chunks.forEach(chunk -> chunk.withMetadata(
                DocumentType.STRUCTURED_LAW.name(),
                ChunkStrategy.TREE_AWARE.name(),
                null
        ));
        logger.info("法律文档结构感知分块完成，chunkCount={}", chunks.size());
        return chunks;
    }
}
