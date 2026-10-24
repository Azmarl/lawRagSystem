package com.yizhaoqi.rag.entity;


import lombok.Data;

/**
 * Elasticsearch存储的文档实体类
 * 包含文档内容和权限信息
 */
@Data
public class EsDocument {

    private String id;             // 文档唯一标识
    private String fileMd5;        // 文件指纹
    private Integer chunkId;       // 文本分块序号
    private String textContent;    // 文本内容
    private Integer pageNumber;    // PDF 页码
    private String anchorText;     // 页内定位锚点
    private float[] vector;        // 向量数据
    private String modelVersion;   // 向量生成模型版本
    private String userId;         // 上传用户ID
    private String orgTag;         // 组织标签
    private boolean isPublic;      // 是否公开

    // ===== 法律树状分块新增字段 =====

    /** 分块级别：article / section / chapter */
    private String chunkLevel;

    /** 编名称，如 "第一编 总则" */
    private String partName;

    /** 章名称，如 "第一章 基本规定" */
    private String chapterName;

    /** 节名称，如 "第一节 民事权利能力和民事行为能力" */
    private String sectionName;

    /** 条文号，如 "第123条"（keyword类型，精确匹配用） */
    private String articleId;

    /** 条文号密度 */
    private Double articleIdDensity;

    /** 法律术语密度 */
    private Double legalTermDensity;
    private String documentType;
    private String parentBlockId;
    private Integer chunkPosition;
    private String chunkStrategy;
    private String caseSection;
    private String blockId;
    private String chunkRole;
    private String contentType;

    /**
     * 默认构造函数，用于Jackson反序列化
     */
    public EsDocument() {
    }

    /**
     * 完整构造函数，包含权限字段
     */
    public EsDocument(String id, String fileMd5, int chunkId, String content,
                     Integer pageNumber, String anchorText,
                     float[] vector, String modelVersion, 
                     String userId, String orgTag, boolean isPublic) {
        this.id = id;
        this.fileMd5 = fileMd5;
        this.chunkId = chunkId;
        this.textContent = content;
        this.pageNumber = pageNumber;
        this.anchorText = anchorText;
        this.vector = vector;
        this.modelVersion = modelVersion;
        this.userId = userId;
        this.orgTag = orgTag;
        this.isPublic = isPublic;
    }

    /**
     * 扩展构造函数，包含法律树状分块字段
     */
    public EsDocument(String id, String fileMd5, int chunkId, String content,
                     Integer pageNumber, String anchorText,
                     float[] vector, String modelVersion,
                     String userId, String orgTag, boolean isPublic,
                     String chunkLevel, String partName, String chapterName,
                     String sectionName, String articleId,
                     Double articleIdDensity, Double legalTermDensity) {
        this.id = id;
        this.fileMd5 = fileMd5;
        this.chunkId = chunkId;
        this.textContent = content;
        this.pageNumber = pageNumber;
        this.anchorText = anchorText;
        this.vector = vector;
        this.modelVersion = modelVersion;
        this.userId = userId;
        this.orgTag = orgTag;
        this.isPublic = isPublic;
        this.chunkLevel = chunkLevel;
        this.partName = partName;
        this.chapterName = chapterName;
        this.sectionName = sectionName;
        this.articleId = articleId;
        this.articleIdDensity = articleIdDensity;
        this.legalTermDensity = legalTermDensity;
    }

    public EsDocument(String id, String fileMd5, int chunkId, String content,
                     Integer pageNumber, String anchorText, float[] vector, String modelVersion,
                     String userId, String orgTag, boolean isPublic, String chunkLevel,
                     String partName, String chapterName, String sectionName, String articleId,
                     Double articleIdDensity, Double legalTermDensity, String documentType,
                     String parentBlockId, Integer chunkPosition) {
        this(id, fileMd5, chunkId, content, pageNumber, anchorText, vector, modelVersion,
                userId, orgTag, isPublic, chunkLevel, partName, chapterName, sectionName,
                articleId, articleIdDensity, legalTermDensity);
        this.documentType = documentType;
        this.parentBlockId = parentBlockId;
        this.chunkPosition = chunkPosition;
    }
    

}
