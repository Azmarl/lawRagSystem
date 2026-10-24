package com.yizhaoqi.rag.chunker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentTypeTest {

    @Test
    void normalizesFrontendValues() {
        assertEquals(DocumentType.STRUCTURED_LAW, DocumentType.from("LAW"));
        assertEquals(DocumentType.STRUCTURED_LAW, DocumentType.from("法律文档"));
        assertEquals(DocumentType.PRIVATE_LEGAL, DocumentType.from("PRIVATE_CASE"));
        assertEquals(DocumentType.PRIVATE_LEGAL, DocumentType.from("私人案件文档"));
    }

    @Test
    void unknownOrBlankDefaultsToPublicLaw() {
        assertEquals(DocumentType.STRUCTURED_LAW, DocumentType.from(null));
        assertEquals(DocumentType.STRUCTURED_LAW, DocumentType.from("unknown"));
    }

    @Test
    void privateDocumentCannotBePublic() {
        assertTrue(DocumentType.PRIVATE_LEGAL.isPrivateDocument());
        assertFalse(DocumentType.STRUCTURED_LAW.isPrivateDocument());
    }
}
