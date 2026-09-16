package com.yizhaoqi.rag.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 文档向量实体类
 * 用于存储文本分块和相关元数据
 */
@Data
@Entity
@Table(name = "document_vectors")
public class DocumentVector {
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
}
