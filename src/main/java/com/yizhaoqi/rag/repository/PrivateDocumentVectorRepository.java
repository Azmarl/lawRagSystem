package com.yizhaoqi.rag.repository;

import com.yizhaoqi.rag.model.PrivateDocumentVector;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * 私人案件分块仓储，只访问 {@code private_document_vectors}，不会触碰公开法典分块表。
 *
 * <p>私人分块按 {@code fileMd5 + userId} 查询/删除：不同用户上传相同文件时，
 * 分块不会在 MySQL 中互相混合。</p>
 */
public interface PrivateDocumentVectorRepository extends JpaRepository<PrivateDocumentVector, Long> {

    List<PrivateDocumentVector> findByFileMd5(String fileMd5);

    List<PrivateDocumentVector> findByFileMd5OrderByChunkIdAsc(String fileMd5);

    List<PrivateDocumentVector> findByFileMd5AndUserIdOrderByChunkIdAsc(String fileMd5, String userId);

    List<PrivateDocumentVector> findByBlockIdInAndUserId(Collection<String> blockIds, String userId);

    long countByFileMd5(String fileMd5);

    long countByFileMd5AndUserId(String fileMd5, String userId);

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM private_document_vectors WHERE file_md5 = ?1", nativeQuery = true)
    void deleteByFileMd5(String fileMd5);

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM private_document_vectors WHERE file_md5 = ?1 AND user_id = ?2", nativeQuery = true)
    void deleteByFileMd5AndUserId(String fileMd5, String userId);
}
