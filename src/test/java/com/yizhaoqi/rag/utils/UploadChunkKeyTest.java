package com.yizhaoqi.rag.utils;

import com.yizhaoqi.rag.chunker.DocumentType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class UploadChunkKeyTest {

    @Test
    void publicLawKeepsLegacyMd5Key() {
        assertEquals("abcdef", UploadChunkKey.of("abcdef", "1", DocumentType.STRUCTURED_LAW));
    }

    @Test
    void privateCaseKeyIsScopedByUserAndType() {
        String first = UploadChunkKey.of("abcdef", "1", DocumentType.PRIVATE_LEGAL);
        String second = UploadChunkKey.of("abcdef", "2", DocumentType.PRIVATE_LEGAL);
        String publicKey = UploadChunkKey.of("abcdef", "1", DocumentType.STRUCTURED_LAW);

        assertEquals(32, first.length());
        assertNotEquals(first, second);
        assertNotEquals(first, publicKey);
        assertNotEquals("abcdef", first);
    }
}
