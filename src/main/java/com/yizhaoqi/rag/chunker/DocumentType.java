package com.yizhaoqi.rag.chunker;

import java.util.Locale;

/**
 * 知识库文档类型。
 *
 * <p>该类型同时决定三件事：</p>
 * <ol>
 *   <li>解析策略：法律文档走多级结构感知解析，私人案件文档走案件文书结构解析；</li>
 *   <li>存储隔离：私人案件文档写入独立的 MinIO bucket、独立的 MySQL 分块表以及独立的 ES 索引；</li>
 *   <li>访问控制：私人案件文档永远不允许公开，默认归属用户私人组织。</li>
 * </ol>
 */
public enum DocumentType {

    /** 公开法典 / 法律法规文档，沿用原有的多级结构感知解析流程。 */
    STRUCTURED_LAW("LAW", "法律文档", false),

    /** 用户上传的私人案件材料，例如案件报告、起诉状、答辩状、证据清单等。 */
    PRIVATE_LEGAL("PRIVATE_CASE", "私人案件文档", true);

    private final String apiValue;
    private final String label;
    private final boolean privateDocument;

    DocumentType(String apiValue, String label, boolean privateDocument) {
        this.apiValue = apiValue;
        this.label = label;
        this.privateDocument = privateDocument;
    }

    public String getApiValue() {
        return apiValue;
    }

    public String getLabel() {
        return label;
    }

    public boolean isPrivateDocument() {
        return privateDocument;
    }

    /**
     * 将前端/历史数据中的多种写法归一化为枚举。
     * 无法识别时回退到 {@link #STRUCTURED_LAW}，避免把未知类型误判为私人数据。
     */
    public static DocumentType from(String value) {
        if (value == null || value.isBlank()) {
            return STRUCTURED_LAW;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "LAW", "LEGAL", "PUBLIC", "PUBLIC_CODE", "PUBLIC_LAW",
                    "STRUCTURED_LAW", "法律文档", "法典", "公开法典" -> STRUCTURED_LAW;
            case "PRIVATE", "CASE", "PRIVATE_CASE", "PRIVATE_LEGAL",
                    "PRIVATE_DOCUMENT", "私人案件文档", "私人文档", "案件文档", "私人案件" -> PRIVATE_LEGAL;
            default -> STRUCTURED_LAW;
        };
    }

    /**
     * 与 {@link #from(String)} 类似，但空白值返回 {@code null}，用于“用户未显式选择类型”的场景。
     */
    public static DocumentType fromNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return from(value);
    }

    public boolean isStructuredLaw() {
        return this == STRUCTURED_LAW;
    }
}
