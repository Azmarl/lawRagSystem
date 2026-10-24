package com.yizhaoqi.rag.parser.strategy;

import com.yizhaoqi.rag.chunker.DocumentType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 按用户选择的文档类型选择解析策略。
 */
@Component
public class DocumentParsingStrategyFactory {

    private final Map<DocumentType, DocumentParsingStrategy> strategies = new EnumMap<>(DocumentType.class);

    public DocumentParsingStrategyFactory(List<DocumentParsingStrategy> strategyList) {
        for (DocumentParsingStrategy strategy : strategyList) {
            strategies.put(strategy.documentType(), strategy);
        }
        for (DocumentType type : DocumentType.values()) {
            if (!strategies.containsKey(type)) {
                throw new IllegalStateException("缺少文档解析策略: " + type);
            }
        }
    }

    public DocumentParsingStrategy get(DocumentType documentType) {
        DocumentType resolved = documentType == null ? DocumentType.STRUCTURED_LAW : documentType;
        DocumentParsingStrategy strategy = strategies.get(resolved);
        if (strategy == null) {
            throw new IllegalArgumentException("不支持的文档类型: " + resolved);
        }
        return strategy;
    }
}
