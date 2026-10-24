package com.yizhaoqi.rag.entity;

import lombok.Data;

@Data
public class SearchResult {
    private String fileMd5;    // 文件指纹
    private Integer chunkId;   // 文本分块序号
    private String textContent; // 文本内容
    private Double score;      // 搜索得分
    private String fileName;   // 原始文件名
    private String userId;     // 上传用户ID
    private String orgTag;     // 组织标签
    private Boolean isPublic;  // 是否公开
    private Integer pageNumber; // PDF 页码
    private String anchorText; // 页内定位锚点
    private String retrievalMode; // 召回方式
    private String matchedChunkText; // 命中的 chunk 原文

    // ===== 法律混合检索溯源面板字段 =====

    /** 分块级别：article / section / chapter */
    private String chunkLevel;
    /** 编名称 */
    private String partName;
    /** 章名称 */
    private String chapterName;
    /** 节名称 */
    private String sectionName;
    /** 条文号 */
    private String articleId;
    /** BM25 归一化得分 */
    private Double bm25NormScore;
    /** KNN 归一化得分 */
    private Double knnNormScore;
    /** 融合权重 α */
    private Double alpha;
    /** 命中原因描述 */
    private String hitReason;

    // ===== 私人案件检索与意图识别字段 =====

    /** 文档类型：STRUCTURED_LAW / PRIVATE_LEGAL */
    private String documentType;
    /** 分块策略 */
    private String chunkStrategy;
    /** 私人案件段落类型 */
    private String caseSection;
    /** 子块 ID */
    private String blockId;
    /** 父块 ID：命中子块后用于回补父段上下文 */
    private String parentBlockId;
    /** 父块上下文（命中子块时回填，供 LLM 使用） */
    private String contextText;
    /** 内容类型：TEXT / TABLE / FIGURE */
    private String contentType;
    /** 本次检索范围：PUBLIC / PRIVATE / BOTH */
    private String searchScope;
    /** 意图分类：PUBLIC / PRIVATE / BOTH */
    private String intentCategory;
    /** 意图识别置信度 */
    private Double intentConfidence;
    /** 意图识别说明 */
    private String intentReason;
    /** 当前结果来自哪个语料：PUBLIC_CODE / PRIVATE_CASE */
    private String sourceCorpus;

    public boolean isPrivateDocument() {
        return "PRIVATE_LEGAL".equalsIgnoreCase(documentType);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score) {
        this(fileMd5, chunkId, textContent, score, null, null, false, null, null, null, null, null);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String fileName) {
        this(fileMd5, chunkId, textContent, score, null, null, false, fileName, null, null, null, null);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String userId, String orgTag, boolean isPublic) {
        this(fileMd5, chunkId, textContent, score, userId, orgTag, isPublic, null, null, null, null, null);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String userId, String orgTag, boolean isPublic, String fileName) {
        this(fileMd5, chunkId, textContent, score, userId, orgTag, isPublic, fileName, null, null, null, null);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String userId, String orgTag,
                        boolean isPublic, String fileName, Integer pageNumber, String anchorText) {
        this(fileMd5, chunkId, textContent, score, userId, orgTag, isPublic, fileName, pageNumber, anchorText, null, textContent);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String userId, String orgTag,
                        boolean isPublic, String fileName, Integer pageNumber, String anchorText,
                        String retrievalMode, String matchedChunkText) {
        this.fileMd5 = fileMd5;
        this.chunkId = chunkId;
        this.textContent = textContent;
        this.score = score;
        this.userId = userId;
        this.orgTag = orgTag;
        this.isPublic = isPublic;
        this.fileName = fileName;
        this.pageNumber = pageNumber;
        this.anchorText = anchorText;
        this.retrievalMode = retrievalMode;
        this.matchedChunkText = matchedChunkText != null ? matchedChunkText : textContent;
    }
}
