package com.yizhaoqi.rag.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 私人案件分块实体。使用独立表，与公开法典的 {@code document_vectors} 物理隔离。
 */
@Entity
@Table(name = "private_document_vectors")
public class PrivateDocumentVector extends DocumentVector {
}
