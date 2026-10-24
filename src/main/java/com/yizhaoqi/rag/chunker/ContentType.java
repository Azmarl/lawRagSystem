package com.yizhaoqi.rag.chunker;

/**
 * 分块内容类型。用于区分普通正文、表格和图注，避免表格被当成普通长文本切碎。
 */
public enum ContentType {
    TEXT,
    TABLE,
    FIGURE
}
