package com.yizhaoqi.rag.parser.strategy;

import com.yizhaoqi.rag.chunker.ChunkDTO;
import com.yizhaoqi.rag.chunker.DocumentType;

import java.util.List;

/**
 * 文档解析策略。上传时由用户选择“法律文档 / 私人案件文档”，
 * 再由 {@link DocumentParsingStrategyFactory} 选择对应策略，避免用同一种分块逻辑处理所有文档。
 */
public interface DocumentParsingStrategy {

    DocumentType documentType();

    /**
     * 将全文解析为携带层级/段落元数据的分块。
     * 返回空列表表示结构化解析失败，由调用方走保底策略。
     */
    List<ChunkDTO> parse(String text);
}
