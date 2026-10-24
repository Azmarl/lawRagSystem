package com.yizhaoqi.rag.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 文档向量基类。
 *
 * <p>公开法典分块写入 {@code document_vectors}，私人案件分块写入
 * {@code private_document_vectors}，通过 JPA 继承映射实现 MySQL 层面的物理隔离。</p>
 */
@Data
@MappedSuperclass
public abstract class DocumentVector {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long vectorId;

    @Column(nullable = false, length = 32)
    private String fileMd5;

    @Column(nullable = false)
    private Integer chunkId;

    @Lob
    private String textContent;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "anchor_text", length = 512)
    private String anchorText;

    @Column(length = 32)
    private String modelVersion;
    
    /**
     * 上传用户ID
     */
    @Column(nullable = false, name = "user_id", length = 64)
    private String userId;
    
    /**
     * 文件所属组织标签
     */
    @Column(name = "org_tag", length = 50)
    private String orgTag;
    
    /**
     * 文件是否公开
     */
    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;

    // ===== 法律树状分块新增字段 =====

    /** 分块级别：article / section / chapter */
    @Column(name = "chunk_level", length = 20)
    private String chunkLevel;

    /** 编名称，如 "第一编 总则" */
    @Column(name = "part_name", length = 128)
    private String partName;

    /** 章名称，如 "第一章 基本规定" */
    @Column(name = "chapter_name", length = 128)
    private String chapterName;

    /** 节名称，如 "第一节 民事权利能力和民事行为能力" */
    @Column(name = "section_name", length = 128)
    private String sectionName;

    /** 条文号，如 "第123条"（keyword类型，精确匹配用） */
    @Column(name = "article_id", length = 64)
    private String articleId;

    /** 条文号密度：条文号命中数 / 总token数 */
    @Column(name = "article_id_density")
    private Double articleIdDensity;

    /** 法律术语密度：法律术语命中数 / 总token数 */
    @Column(name = "legal_term_density")
    private Double legalTermDensity;

    @Column(name = "parent_block_id", length = 128)
    private String parentBlockId;

    @Column(name = "chunk_position")
    private Integer chunkPosition;

    /**
     * 文档类型：STRUCTURED_LAW / PRIVATE_LEGAL。
     * 与独立表一起构成双保险，避免历史数据迁移时误入公共索引。
     */
    @Column(name = "document_type", length = 32)
    private String documentType;

    /** 稳定块 ID（父块/子块唯一） */
    @Column(name = "block_id", length = 128)
    private String blockId;

    /** PARENT（仅上下文）/ CHILD（参与检索） */
    @Column(name = "chunk_role", length = 16)
    private String chunkRole;

    /** TEXT / TABLE / FIGURE */
    @Column(name = "content_type", length = 16)
    private String contentType;

    /** 分块策略：TREE_AWARE / CASE_ELEMENT / CASE_STRUCTURE / CHAR_OVERLAP / LEGAL_FALLBACK */
    @Column(name = "chunk_strategy", length = 32)
    private String chunkStrategy;

    /** 私人案件段落类型：PARTIES / CLAIMS / FACTS / EVIDENCE / JUDGMENT ... */
    @Column(name = "case_section", length = 32)
    private String caseSection;
}
