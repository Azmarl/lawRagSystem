package com.yizhaoqi.rag.repository;

import com.yizhaoqi.rag.model.FileUpload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface FileUploadRepository extends JpaRepository<FileUpload, Long> {
    Optional<FileUpload> findFirstByFileMd5OrderByCreatedAtDesc(String fileMd5);

    List<FileUpload> findAllByFileMd5(String fileMd5);

    List<FileUpload> findAllByVectorizationStatusIsNull();

    List<FileUpload> findAllByFileMd5AndUserIdOrderByCreatedAtDesc(String fileMd5, String userId);

    Optional<FileUpload> findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(String fileMd5, String userId);

    Optional<FileUpload> findFirstByFileMd5AndUserIdAndDocumentTypeOrderByCreatedAtDesc(
            String fileMd5, String userId, String documentType);

    Optional<FileUpload> findFirstByFileMd5AndIsPublicTrueOrderByCreatedAtDesc(String fileMd5);

    Optional<FileUpload> findFirstByFileNameAndIsPublicTrueOrderByCreatedAtDesc(String fileName);
    
    long countByFileMd5(String fileMd5);

    long countByFileMd5AndUserId(String fileMd5, String userId);
    
    void deleteByFileMd5(String fileMd5);
    
    void deleteByFileMd5AndUserId(String fileMd5, String userId);
    
    /**
     * 查询用户自己的文件和公开文件。
     * 私人案件文档只允许所有者本人访问，即使其组织标签意外与其他组织重合也不会通过公开/组织分支泄漏。
     */
    @Query("SELECT f FROM FileUpload f WHERE f.userId = :userId "
            + "OR (f.isPublic = true AND (f.documentType IS NULL OR f.documentType <> 'PRIVATE_LEGAL'))")
    List<FileUpload> findOwnedOrPublicFiles(@Param("userId") String userId);

    /**
     * 查询用户可访问的所有文件（考虑层级标签权限）。
     *
     * <p>私人案件文档（documentType=PRIVATE_LEGAL）只通过 userId 分支返回，永远不会走公开或组织分支。</p>
     *
     * @param userId 用户ID
     * @param orgTagList 用户有效的组织标签列表（包含层级结构）
     * @return 用户可访问的文件列表
     */
    @Query("SELECT f FROM FileUpload f WHERE f.userId = :userId "
            + "OR (f.isPublic = true AND (f.documentType IS NULL OR f.documentType <> 'PRIVATE_LEGAL')) "
            + "OR (f.orgTag IN :orgTagList AND (f.documentType IS NULL OR f.documentType <> 'PRIVATE_LEGAL'))")
    List<FileUpload> findAccessibleFilesWithTags(@Param("userId") String userId, @Param("orgTagList") List<String> orgTagList);
    
    /**
     * 查询用户可访问的所有文件（原始方法，保留向后兼容性）
     * 
     * @param userId 用户ID
     * @param orgTagList 用户所属的组织标签列表（逗号分隔）
     * @return 用户可访问的文件列表
     */
    @Query("SELECT f FROM FileUpload f WHERE f.userId = :userId "
            + "OR (f.isPublic = true AND (f.documentType IS NULL OR f.documentType <> 'PRIVATE_LEGAL')) "
            + "OR (f.orgTag IN :orgTagList AND (f.documentType IS NULL OR f.documentType <> 'PRIVATE_LEGAL'))")
    List<FileUpload> findAccessibleFiles(@Param("userId") String userId, @Param("orgTagList") List<String> orgTagList);
    
    /**
     * 查询用户自己上传的所有文件
     * 
     * @param userId 用户ID
     * @return 用户上传的文件列表
     */
    List<FileUpload> findByUserId(String userId);

    List<FileUpload> findByUserIdAndFileNameOrderByCreatedAtDesc(String userId, String fileName);

    List<FileUpload> findByFileMd5In(List<String> md5List);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE FileUpload f SET f.status = :newStatus WHERE f.id = :id AND f.status = :currentStatus")
    int updateStatusIfCurrent(@Param("id") Long id,
                              @Param("currentStatus") int currentStatus,
                              @Param("newStatus") int newStatus);
}
