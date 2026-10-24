package com.yizhaoqi.rag.chunker;

/**
 * 分块策略标识，持久化到 MySQL / Elasticsearch，便于检索溯源面板展示。
 */
public enum ChunkStrategy {

    /** 法典多级结构感知分块（编 / 章 / 节 / 条）。 */
    TREE_AWARE("多级结构感知"),

    /** 私人案件文书结构分块（当事人、诉讼请求、事实与理由、证据、裁判结果等）。 */
    CASE_STRUCTURE("案件文书结构感知"),

    /** 案件要素感知的父子分层分块（父段回补上下文，子块参与检索）。 */
    CASE_ELEMENT("案件要素分层"),

    /** 字符/句子重叠保底分块。 */
    CHAR_OVERLAP("字符重叠保底"),

    /** 法律文档结构解析失败后的保底分块。 */
    LEGAL_FALLBACK("法典解析保底");

    private final String label;

    ChunkStrategy(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
