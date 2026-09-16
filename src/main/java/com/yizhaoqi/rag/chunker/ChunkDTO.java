package com.yizhaoqi.rag.chunker;

/**
 * 树状分块结果 DTO —— 携带法律层级元数据的 Chunk。
 *
 * 每个 Chunk 包含：
 * - 文本内容（用于 Embedding）
 * - 层级元数据（chunkLevel, partName, chapterName, sectionName, articleId）
 * - 密度特征（articleIdDensity, legalTermDensity）
 */
public class ChunkDTO {

    /** 分块文本内容（含层级前缀） */
    private String textContent;

    /** 分块级别：article / section / chapter */
    private String chunkLevel;

    /** 编名称，如 "第一编 总则" */
    private String partName;

    /** 章名称，如 "第五章 民事权利" */
    private String chapterName;

    /** 节名称，如 "第一节 民事权利能力和民事行为能力" */
    private String sectionName;

    /** 条文号，如 "第123条"（仅 article 级别有值） */
    private String articleId;

    /** 估算 token 数 */
    private int tokenCount;

    /** 法律术语密度 */
    private Double legalTermDensity;

    /** 条文号密度 */
    private Double articleIdDensity;

    // ===== Builder =====

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String textContent;
        private String chunkLevel;
        private String partName;
        private String chapterName;
        private String sectionName;
        private String articleId;
        private int tokenCount;
        private Double legalTermDensity;
        private Double articleIdDensity;

        public Builder textContent(String textContent) {
            this.textContent = textContent;
            return this;
        }

        public Builder chunkLevel(String chunkLevel) {
            this.chunkLevel = chunkLevel;
            return this;
        }

        public Builder partName(String partName) {
            this.partName = partName;
            return this;
        }

        public Builder chapterName(String chapterName) {
            this.chapterName = chapterName;
            return this;
        }

        public Builder sectionName(String sectionName) {
            this.sectionName = sectionName;
            return this;
        }

        public Builder articleId(String articleId) {
            this.articleId = articleId;
            return this;
        }

        public Builder tokenCount(int tokenCount) {
            this.tokenCount = tokenCount;
            return this;
        }

        public Builder legalTermDensity(Double legalTermDensity) {
            this.legalTermDensity = legalTermDensity;
            return this;
        }

        public Builder articleIdDensity(Double articleIdDensity) {
            this.articleIdDensity = articleIdDensity;
            return this;
        }

        public ChunkDTO build() {
            return new ChunkDTO(this);
        }
    }

    private ChunkDTO(Builder builder) {
        this.textContent = builder.textContent;
        this.chunkLevel = builder.chunkLevel;
        this.partName = builder.partName;
        this.chapterName = builder.chapterName;
        this.sectionName = builder.sectionName;
        this.articleId = builder.articleId;
        this.tokenCount = builder.tokenCount;
        this.legalTermDensity = builder.legalTermDensity;
        this.articleIdDensity = builder.articleIdDensity;
    }

    // ===== Getters =====

    public String getTextContent() {
        return textContent;
    }

    public String getChunkLevel() {
        return chunkLevel;
    }

    public String getPartName() {
        return partName;
    }

    public String getChapterName() {
        return chapterName;
    }

    public String getSectionName() {
        return sectionName;
    }

    public String getArticleId() {
        return articleId;
    }

    public int getTokenCount() {
        return tokenCount;
    }

    public Double getLegalTermDensity() {
        return legalTermDensity;
    }

    public Double getArticleIdDensity() {
        return articleIdDensity;
    }
}