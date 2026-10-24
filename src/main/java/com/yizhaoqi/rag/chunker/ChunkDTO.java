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
    private String parentBlockId;
    private Integer chunkPosition;

    /** 文档类型：STRUCTURED_LAW / PRIVATE_LEGAL */
    private String documentType;

    /** 分块策略：TREE_AWARE / CASE_STRUCTURE / CHAR_OVERLAP / LEGAL_FALLBACK */
    private String chunkStrategy;

    /** 私人案件文书的段落类型，例如 PARTIES / CLAIMS / FACTS / EVIDENCE / JUDGMENT */
    private String caseSection;

    /** 稳定块 ID。父块和子块都拥有唯一 ID，子块通过 parentBlockId 指向父块。 */
    private String blockId;

    /** 分块角色：PARENT（仅回补上下文）/ CHILD（参与检索） */
    private String chunkRole;

    /** 内容类型：TEXT / TABLE / FIGURE */
    private String contentType;

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
        private String parentBlockId;
        private Integer chunkPosition;
        private String documentType;
        private String chunkStrategy;
        private String caseSection;
        private String blockId;
        private String chunkRole;
        private String contentType;

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

        public Builder parentBlockId(String parentBlockId) {
            this.parentBlockId = parentBlockId;
            return this;
        }

        public Builder chunkPosition(Integer chunkPosition) {
            this.chunkPosition = chunkPosition;
            return this;
        }

        public Builder documentType(String documentType) {
            this.documentType = documentType;
            return this;
        }

        public Builder chunkStrategy(String chunkStrategy) {
            this.chunkStrategy = chunkStrategy;
            return this;
        }

        public Builder caseSection(String caseSection) {
            this.caseSection = caseSection;
            return this;
        }

        public Builder blockId(String blockId) {
            this.blockId = blockId;
            return this;
        }

        public Builder chunkRole(String chunkRole) {
            this.chunkRole = chunkRole;
            return this;
        }

        public Builder contentType(String contentType) {
            this.contentType = contentType;
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
        this.parentBlockId = builder.parentBlockId;
        this.chunkPosition = builder.chunkPosition;
        this.documentType = builder.documentType;
        this.chunkStrategy = builder.chunkStrategy;
        this.caseSection = builder.caseSection;
        this.blockId = builder.blockId;
        this.chunkRole = builder.chunkRole;
        this.contentType = builder.contentType;
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

    public String getParentBlockId() {
        return parentBlockId;
    }

    public Integer getChunkPosition() {
        return chunkPosition;
    }

    public String getDocumentType() {
        return documentType;
    }

    public String getChunkStrategy() {
        return chunkStrategy;
    }

    public String getCaseSection() {
        return caseSection;
    }

    public String getBlockId() {
        return blockId;
    }

    public String getChunkRole() {
        return chunkRole;
    }

    public String getContentType() {
        return contentType;
    }

    public void setParentBlockId(String parentBlockId) {
        this.parentBlockId = parentBlockId;
    }

    public void setBlockId(String blockId) {
        this.blockId = blockId;
    }

    public void setChunkRole(String chunkRole) {
        this.chunkRole = chunkRole;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    /**
     * 为已有 Chunk 补充元数据。用于 TreeAwareChunker 产出后统一打标。
     */
    public ChunkDTO withMetadata(String documentType, String chunkStrategy, String caseSection) {
        if (this.documentType == null) {
            this.documentType = documentType;
        }
        if (this.chunkStrategy == null) {
            this.chunkStrategy = chunkStrategy;
        }
        if (this.caseSection == null) {
            this.caseSection = caseSection;
        }
        return this;
    }
}