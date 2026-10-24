package com.yizhaoqi.rag.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 公开法典分块实体。
 */
@Entity
@Table(name = "document_vectors")
public class PublicDocumentVector extends DocumentVector {
}
