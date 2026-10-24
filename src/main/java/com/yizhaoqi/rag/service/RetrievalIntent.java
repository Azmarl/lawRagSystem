package com.yizhaoqi.rag.service;

/**
 * 意图识别结果。
 *
 * @param category   意图分类
 * @param confidence 置信度 [0,1]
 * @param source     ALIYUN_DECISION / RULE / CONTEXT
 * @param reason     可展示给前端的判断依据
 */
public record RetrievalIntent(IntentCategory category, double confidence, String source, String reason) {

    public boolean includesPrivate() {
        return category != null && category.includesPrivate();
    }

    public boolean includesPublic() {
        return category != null && category.includesPublic();
    }
}
