package com.yizhaoqi.rag.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentRecognitionServiceTest {

    private final IntentRecognitionService service = new IntentRecognitionService(null, 0.85);

    @Test
    void classifiesPureLawQuestionAsPublic() {
        RetrievalIntent intent = service.recognize("民法典第123条的内容是什么？", "", List.of());

        assertEquals(IntentCategory.PUBLIC, intent.category());
        assertTrue(intent.confidence() > 0.5);
        assertFalse(intent.includesPrivate());
    }

    @Test
    void classifiesPrivateCaseQuestionAsPrivate() {
        RetrievalIntent intent = service.recognize("帮我分析一下我上传的案件报告里的合同风险", "", List.of());

        assertEquals(IntentCategory.PRIVATE, intent.category());
        assertTrue(intent.includesPrivate());
        assertFalse(intent.includesPublic());
    }

    @Test
    void classifiesCaseByReferentialWordingAsPrivate() {
        RetrievalIntent intent = service.recognize(
                "2026年一月五号立项的那个买卖合同纠纷案件是什么背景情况？", "", List.of());

        assertEquals(IntentCategory.PRIVATE, intent.category());
        assertTrue(intent.includesPrivate());
    }

    @Test
    void classifiesCombinedQuestionAsMixed() {
        RetrievalIntent intent = service.recognize("结合我的案件和民法典第123条，分析是否合法", "", List.of());

        assertEquals(IntentCategory.MIXED, intent.category());
        assertTrue(intent.includesPrivate());
        assertTrue(intent.includesPublic());
    }

    @Test
    void defaultsToPublicWhenNoSignal() {
        RetrievalIntent intent = service.recognize("你好，帮我看看这个问题", "", List.of());

        assertEquals(IntentCategory.PUBLIC, intent.category());
    }

    @Test
    void usesConversationContextForShortFollowUp() {
        RetrievalIntent intent = service.recognize("那它的法律依据呢",
                "user: 帮我分析我上传的案件报告\nassistant: 案件材料中的合同存在违约风险", List.of());

        assertEquals(IntentCategory.MIXED, intent.category());
        assertEquals("CONTEXT", intent.source());
    }
}
