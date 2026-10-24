package com.yizhaoqi.rag.utils;

import com.yizhaoqi.rag.chunker.DocumentType;
import org.apache.commons.codec.digest.DigestUtils;

/**
 * 分片上传的存储/数据库键。
 *
 * <p>公开法典继续使用文件 MD5 作为分片键，保持历史数据兼容；私人案件文档使用
 * {@code sha256(fileMd5 + userId + documentType)} 的前 32 位作为分片键，避免不同用户上传
 * 相同文件时在 {@code chunk_info} 的唯一约束上互相覆盖。</p>
 */
public final class UploadChunkKey {

    private UploadChunkKey() {
    }

    public static String of(String fileMd5, String userId, DocumentType documentType) {
        if (documentType == null || !documentType.isPrivateDocument()) {
            return fileMd5;
        }
        String raw = (fileMd5 == null ? "" : fileMd5)
                + "|" + (userId == null ? "" : userId)
                + "|" + documentType.name();
        return DigestUtils.sha256Hex(raw).substring(0, 32);
    }
}
