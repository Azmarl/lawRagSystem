package com.yizhaoqi.rag.chunker;

/**
 * 分块在父子结构中的角色。
 *
 * <p>PARENT 块只用于回补上下文，不参与向量检索；CHILD 块参与 BM25/KNN 召回。</p>
 */
public enum ChunkRole {
    PARENT,
    CHILD
}
