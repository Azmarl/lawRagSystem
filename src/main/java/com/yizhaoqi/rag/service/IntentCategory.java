package com.yizhaoqi.rag.service;

/**
 * 对话检索意图。
 */
public enum IntentCategory {

    /** 只查询公开法典 / 法律法规。 */
    PUBLIC("PUBLIC", "仅法典检索", true, false),

    /** 只查询用户上传的私人案件材料。 */
    PRIVATE("PRIVATE", "仅私人案件检索", false, true),

    /** 同时查询法典与私人案件材料。 */
    MIXED("BOTH", "法典 + 私人案件混合检索", true, true);

    private final String apiValue;
    private final String label;
    private final boolean includesPublic;
    private final boolean includesPrivate;

    IntentCategory(String apiValue, String label, boolean includesPublic, boolean includesPrivate) {
        this.apiValue = apiValue;
        this.label = label;
        this.includesPublic = includesPublic;
        this.includesPrivate = includesPrivate;
    }

    public String getApiValue() {
        return apiValue;
    }

    public String getLabel() {
        return label;
    }

    public boolean includesPublic() {
        return includesPublic;
    }

    public boolean includesPrivate() {
        return includesPrivate;
    }

    public static IntentCategory fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return switch (code.trim().toUpperCase()) {
            case "PUBLIC", "PUBLIC_CODE", "LAW" -> PUBLIC;
            case "PRIVATE", "PRIVATE_CASE", "CASE" -> PRIVATE;
            case "BOTH", "MIXED", "ALL" -> MIXED;
            default -> null;
        };
    }
}
