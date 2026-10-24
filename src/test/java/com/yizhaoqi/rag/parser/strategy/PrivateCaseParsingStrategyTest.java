package com.yizhaoqi.rag.parser.strategy;

import com.yizhaoqi.rag.chunker.ChunkDTO;
import com.yizhaoqi.rag.chunker.ChunkRole;
import com.yizhaoqi.rag.chunker.ChunkStrategy;
import com.yizhaoqi.rag.chunker.ContentType;
import com.yizhaoqi.rag.chunker.DocumentType;
import com.yizhaoqi.rag.chunker.OverlapTextChunker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrivateCaseParsingStrategyTest {

    private final OverlapTextChunker chunker = new OverlapTextChunker(128, 20);
    private final PrivateCaseParsingStrategy strategy = new PrivateCaseParsingStrategy(chunker);

    @Test
    void parsesCaseSectionsIntoTypedParentAndChildChunks() {
        String text = "民事判决书\n"
                + "原告：张三\n被告：李四\n"
                + "一、当事人信息\n原告张三，男，1980年生。\n被告李四，男，1982年生。\n"
                + "二、诉讼请求\n请求判令被告支付货款十万元。\n"
                + "三、事实与理由\n双方签订买卖合同，被告逾期未付款。\n"
                + "四、证据材料\n原告提交买卖合同一份、转账记录一份。\n"
                + "五、本院认为\n被告应当承担违约责任。\n"
                + "六、判决如下\n被告于本判决生效之日起十日内支付货款。";

        List<ChunkDTO> chunks = strategy.parse(text);

        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().allMatch(chunk -> DocumentType.PRIVATE_LEGAL.name().equals(chunk.getDocumentType())));
        assertTrue(chunks.stream().allMatch(chunk -> ChunkStrategy.CASE_ELEMENT.name().equals(chunk.getChunkStrategy())));
        Set<String> sections = chunks.stream().map(ChunkDTO::getCaseSection).collect(Collectors.toSet());
        assertTrue(sections.contains("PARTIES"));
        assertTrue(sections.contains("CLAIMS"));
        assertTrue(sections.contains("FACTS"));
        assertTrue(sections.contains("EVIDENCE"));
        assertTrue(sections.contains("COURT_OPINION"));
        assertTrue(sections.contains("JUDGMENT"));

        List<ChunkDTO> parents = chunks.stream()
                .filter(chunk -> ChunkRole.PARENT.name().equals(chunk.getChunkRole()))
                .toList();
        List<ChunkDTO> children = chunks.stream()
                .filter(chunk -> ChunkRole.CHILD.name().equals(chunk.getChunkRole()))
                .toList();
        assertFalse(parents.isEmpty());
        assertFalse(children.isEmpty());
        Set<String> parentIds = parents.stream().map(ChunkDTO::getBlockId).collect(Collectors.toSet());
        assertTrue(children.stream().allMatch(child -> parentIds.contains(child.getParentBlockId())));
        assertTrue(parents.stream().allMatch(parent -> parent.getBlockId() != null));
        assertTrue(children.stream().allMatch(child -> child.getBlockId() != null));
    }

    @Test
    void recognizesCriminalCaseElementsAndTables() {
        String text = "刑事判决书\n"
                + "公诉机关：某市人民检察院。\n"
                + "被告人：王某，男，1990年生。\n"
                + "一、公诉机关指控\n王某以非法占有为目的，虚构项目骗取他人财物。\n"
                + "二、被告人辩称\n王某辩称其具有还款意愿。\n"
                + "三、经审理查明\n[TABLE]\n| 证据名称 | 证明目的 |\n| 转账记录 | 资金流向 |\n[/TABLE]\n"
                + "四、本院认为\n王某的行为构成合同诈骗罪。\n"
                + "五、判决如下\n判处有期徒刑三年。";

        List<ChunkDTO> chunks = strategy.parse(text);

        assertFalse(chunks.isEmpty());
        Set<String> sections = chunks.stream().map(ChunkDTO::getCaseSection).collect(Collectors.toSet());
        assertTrue(sections.contains("PROSECUTION"));
        assertTrue(sections.contains("DEFENSE"));
        assertTrue(sections.contains("FACTS"));
        assertTrue(sections.contains("COURT_OPINION"));
        assertTrue(sections.contains("JUDGMENT"));
        assertTrue(chunks.stream().anyMatch(chunk -> ContentType.TABLE.name().equals(chunk.getContentType())));
        assertNotNull(chunks.get(0).getBlockId());
    }

    @Test
    void returnsEmptyForUnstructuredTextSoCallerCanFallback() {
        List<ChunkDTO> chunks = strategy.parse("这是一段没有任何案件段落标题的普通材料，只有连续叙述。");

        assertTrue(chunks.isEmpty());
    }

    @Test
    void supportsDocumentTypeContract() {
        assertEquals(DocumentType.PRIVATE_LEGAL, strategy.documentType());
    }
}
