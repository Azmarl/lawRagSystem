package com.yizhaoqi.rag.chunker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlapTextChunkerTest {

    private final OverlapTextChunker chunker = new OverlapTextChunker(100, 20);

    @Test
    void alwaysProducesAtLeastOneChunkForNonBlankText() {
        List<ChunkDTO> chunks = chunker.chunk("这是一段没有句号的案件事实", DocumentType.PRIVATE_LEGAL);

        assertFalse(chunks.isEmpty());
        assertEquals(DocumentType.PRIVATE_LEGAL.name(), chunks.get(0).getDocumentType());
        assertEquals(ChunkStrategy.CHAR_OVERLAP.name(), chunks.get(0).getChunkStrategy());
        assertEquals("case-fallback", chunks.get(0).getChunkLevel());
    }

    @Test
    void splitsLongTextAndKeepsChunkMetadata() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            text.append("第").append(i).append("段案件事实内容，需要保留证据和合同条款。");
        }

        List<ChunkDTO> chunks = chunker.chunk(text.toString(), DocumentType.PRIVATE_LEGAL);

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.getTokenCount() <= 100));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.getTextContent() != null
                && !chunk.getTextContent().isBlank()));
    }

    @Test
    void blankTextReturnsNoChunks() {
        assertTrue(chunker.chunk("   \n ", DocumentType.PRIVATE_LEGAL).isEmpty());
    }
}
