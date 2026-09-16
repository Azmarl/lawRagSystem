package com.yizhaoqi.rag.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.yizhaoqi.rag.client.EmbeddingClient;
import com.yizhaoqi.rag.chunker.LegalTermScorer;
import com.yizhaoqi.rag.entity.EsDocument;
import com.yizhaoqi.rag.entity.SearchResult;
import com.yizhaoqi.rag.model.User;
import com.yizhaoqi.rag.model.FileUpload;
import com.yizhaoqi.rag.exception.CustomException;
import com.yizhaoqi.rag.repository.FileUploadRepository;
import com.yizhaoqi.rag.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 混合搜索服务 —— 双路并发召回 + Adaptive α 融合。
 *
 * 两条独立查询路径：
 * - 路径 A (BM25 倒排召回)：match textContent + term articleId（高 boost）
 * - 路径 B (KNN 向量召回)：dense_vector 语义相似度
 *
 * 融合公式：
 *   FinalScore = α * BM25_NormScore + (1-α) * KNN_NormScore
 *   α = sigmoid(w1 * articleIdDensity(query) + w2 * legalTermDensity(query) + bias)
 *
 * 权限过滤（原封不动保留）：
 *   - 用户自己的文档
 *   - 公开文档
 *   - 用户所属组织的文档（含层级关系）
 */
@Service
public class HybridSearchService {

    private static final Logger logger = LoggerFactory.getLogger(HybridSearchService.class);

    /** BM25 和 KNN 的召回窗口扩大系数 */
    private static final int RECALL_FACTOR = 3;

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    private final LegalTermScorer termScorer = new LegalTermScorer();

    /**
     * 使用双路并发召回 + Adaptive α 融合进行带权限搜索。
     *
     * @param query  查询字符串
     * @param userId 用户ID
     * @param topK   返回结果数量
     * @return 搜索结果列表（含融合得分与溯源信息）
     */
    public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
        logger.debug("开始带权限双路并发搜索，查询: {}, 用户ID: {}, topK: {}", query, userId, topK);

        try {
            // ===== 1. 获取用户权限信息（保留原有逻辑） =====
            List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
            logger.debug("用户 {} 的有效组织标签: {}", userId, userEffectiveTags);

            String userDbId = getUserDbId(userId);
            logger.debug("用户 {} 的数据库ID: {}", userId, userDbId);

            // ===== 2. 计算 Adaptive α（含精确条文查询补偿）=====
            LegalTermScorer.ArticleIdQueryInfo queryInfo = termScorer.analyzeQuery(query);
            double alpha = termScorer.computeAlpha(queryInfo);
            logger.info("Adaptive α 计算: query='{}', articleIdDensity={:.4f}, legalTermDensity={:.4f}, α={:.4f}",
                    query, queryInfo.articleIdDensity(), queryInfo.legalTermDensity(), alpha);

            // ===== 3. 生成查询向量 =====
            final List<Float> queryVector = embedToVectorList(query, userId);

            // 如果向量生成失败，退化为纯 BM25
            if (queryVector == null) {
                logger.warn("向量生成失败，退化为纯 BM25 搜索");
                return textOnlySearchWithPermission(query, userDbId, userEffectiveTags, topK);
            }

            // ===== 4. 双路并发查询 =====
            int recallK = topK * RECALL_FACTOR;

            // 路径 A: BM25 倒排召回（异步）
            CompletableFuture<Map<String, HitScore>> bm25Future = CompletableFuture.supplyAsync(() -> {
                try {
                    return executeBm25Query(query, userDbId, userEffectiveTags, recallK, queryInfo);
                } catch (Exception e) {
                    logger.error("BM25 查询失败", e);
                    return Collections.emptyMap();
                }
            });

            // 路径 B: KNN 向量召回（异步）
            CompletableFuture<Map<String, HitScore>> knnFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return executeKnnQuery(queryVector, userDbId, userEffectiveTags, recallK);
                } catch (Exception e) {
                    logger.error("KNN 查询失败", e);
                    return Collections.emptyMap();
                }
            });

            // 等待双路完成
            Map<String, HitScore> bm25Results = bm25Future.join();
            Map<String, HitScore> knnResults = knnFuture.join();

            logger.info("双路召回完成: BM25={} 条, KNN={} 条", bm25Results.size(), knnResults.size());

            // ===== 5. 归一化与融合打分 =====
            List<SearchResult> fusedResults = fuseAndRank(
                    bm25Results, knnResults, alpha, topK, query, queryInfo);

            logger.debug("融合排序完成，返回 {} 条结果，α={:.4f}", fusedResults.size(), alpha);
            attachFileNames(fusedResults);
            return fusedResults;

        } catch (Exception e) {
            logger.error("带权限的双路搜索失败", e);
            try {
                logger.info("尝试使用纯文本搜索作为后备方案");
                return textOnlySearchWithPermission(query, getUserDbId(userId),
                        getUserEffectiveOrgTags(userId), topK);
            } catch (Exception fallbackError) {
                logger.error("后备搜索也失败", fallbackError);
                return Collections.emptyList();
            }
        }
    }

    // ========================
    // 路径 A：BM25 倒排召回
    // ========================

    private Map<String, HitScore> executeBm25Query(String query, String userDbId,
            List<String> userEffectiveTags, int recallK,
            LegalTermScorer.ArticleIdQueryInfo queryInfo) throws Exception {

        // 展开 articleId：阿拉伯数字形式 → 同时包含中文数字形式（双向转换）
        List<String> expandedArticleIds = termScorer.expandArticleIds(queryInfo.matchedArticleIds());
        boolean hasArticleIds = !expandedArticleIds.isEmpty();

        SearchResponse<EsDocument> response = esClient.search(s -> {
            s.index("knowledge_base");
            s.size(recallK);

            s.query(q -> q.bool(b -> {
                if (hasArticleIds) {
                    // 精确条文查询模式：must 至少命中一个 articleId（match_phrase 或 term），
                    // 再加法典名作为辅助 boost
                    b.must(m -> m.bool(inner -> {
                        for (String articleId : expandedArticleIds) {
                            // match_phrase: textContent 中精确匹配 "第一百二十三条" 或 "第123条"
                            inner.should(sh2 -> sh2.matchPhrase(mp -> mp
                                    .field("textContent")
                                    .query(articleId)
                                    .boost(5.0f)
                            ));
                            // term: articleId keyword 字段精确匹配
                            inner.should(sh3 -> sh3.term(t -> t
                                    .field("articleId")
                                    .value(articleId)
                                    .boost(10.0f)
                            ));
                        }
                        inner.minimumShouldMatch("1");
                        return inner;
                    }));
                    // 法典名作为辅助 should（低 boost，仅在同名法典中自然偏序）
                    String lawName = extractLawName(query);
                    if (!lawName.isEmpty()) {
                        b.should(sh -> sh.matchPhrase(mp -> mp
                                .field("textContent")
                                .query(lawName)
                                .boost(0.5f)
                        ));
                    }
                } else {
                    // 非条文查询：保持原有 broad match 行为
                    b.must(m -> m.match(ma -> ma
                            .field("textContent")
                            .query(query)
                    ));
                }
                // 权限过滤
                b.filter(f -> f.bool(bf -> {
                    buildPermissionFilterInPlace(bf, userDbId, userEffectiveTags);
                    return bf;
                }));
                return b;
            }));
            return s;
        }, EsDocument.class);

        return extractHits(response);
    }

    /**
     * 从查询中提取法典名称。
     * 例如："民法典第123条的内容是什么？" → "民法典"
     */
    private String extractLawName(String query) {
        // 按条文号之前的内容截取
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "^(.+?)(第[0-9零一二三四五六七八九十百千]+条)");
        java.util.regex.Matcher m = p.matcher(query);
        if (m.find()) {
            String prefix = m.group(1);
            // 去掉常见的口语化前缀
            if (prefix.length() > 10) {
                // 如果前缀太长，尝试只保留最后几个字（通常是法典名）
                return prefix.substring(Math.max(0, prefix.length() - 6)).trim();
            }
            return prefix.trim();
        }
        return "";
    }

    // ========================
    // 路径 B：KNN 向量召回
    // ========================

    private Map<String, HitScore> executeKnnQuery(List<Float> queryVector, String userDbId,
            List<String> userEffectiveTags, int recallK) throws Exception {

        int numCandidates = recallK * 2;

        SearchResponse<EsDocument> response = esClient.search(s -> {
            s.index("knowledge_base");
            s.knn(kn -> kn
                    .field("vector")
                    .queryVector(queryVector)
                    .k(recallK)
                    .numCandidates(numCandidates)
                    // KNN 内部的权限过滤
                    .filter(f -> f.bool(b -> {
                        buildPermissionFilterInPlace(b, userDbId, userEffectiveTags);
                        return b;
                    }))
            );
            s.size(recallK);
            return s;
        }, EsDocument.class);

        return extractHits(response);
    }

    // ========================
    // 权限过滤构建（保留原有逻辑）
    // ========================

    /**
     * 构建权限 filter bool query（与原有逻辑完全一致）。
     */
    private BoolQuery buildPermissionFilter(
            co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder bf,
            String userDbId, List<String> userEffectiveTags) {
        buildPermissionFilterInPlace(bf, userDbId, userEffectiveTags);
        return bf.build();
    }

    private void buildPermissionFilterInPlace(
            co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder bf,
            String userDbId, List<String> userEffectiveTags) {
        // 条件1: 用户可访问自己的文档
        bf.should(s1 -> s1.term(t -> t.field("userId").value(userDbId)));
        // 条件2: 公开文档
        bf.should(s2 -> s2.term(t -> t.field("isPublic").value(true)));
        // 条件3: 组织标签
        bf.should(s3 -> {
            if (userEffectiveTags.isEmpty()) {
                return s3.matchNone(mn -> mn);
            } else if (userEffectiveTags.size() == 1) {
                return s3.term(t -> t.field("orgTag").value(userEffectiveTags.get(0)));
            } else {
                return s3.bool(inner -> {
                    for (String tag : userEffectiveTags) {
                        inner.should(sh2 -> sh2.term(t -> t.field("orgTag").value(tag)));
                    }
                    return inner;
                });
            }
        });
    }

    // ========================
    // 搜索结果提取
    // ========================

    /**
     * 从 ES SearchResponse 提取 docId → HitScore 映射。
     */
    private Map<String, HitScore> extractHits(SearchResponse<EsDocument> response) {
        Map<String, HitScore> result = new LinkedHashMap<>();
        if (response.hits() == null || response.hits().hits() == null) {
            return result;
        }
        for (Hit<EsDocument> hit : response.hits().hits()) {
            if (hit.id() == null || hit.source() == null) {
                continue;
            }
            EsDocument doc = hit.source();
            result.put(hit.id(), new HitScore(
                    hit.score() != null ? hit.score() : 0.0,
                    doc.getFileMd5(),
                    doc.getChunkId(),
                    doc.getTextContent(),
                    doc.getUserId(),
                    doc.getOrgTag(),
                    doc.isPublic(),
                    doc.getPageNumber(),
                    doc.getAnchorText(),
                    doc.getChunkLevel(),
                    doc.getPartName(),
                    doc.getChapterName(),
                    doc.getSectionName(),
                    doc.getArticleId()
            ));
        }
        return result;
    }

    // ========================
    // 融合打分
    // ========================

    private List<SearchResult> fuseAndRank(
            Map<String, HitScore> bm25Results,
            Map<String, HitScore> knnResults,
            double alpha, int topK,
            String originalQuery,
            LegalTermScorer.ArticleIdQueryInfo queryInfo) {

        Set<String> allDocIds = new LinkedHashSet<>();
        allDocIds.addAll(bm25Results.keySet());
        allDocIds.addAll(knnResults.keySet());

        double bm25Min = bm25Results.values().stream().mapToDouble(h -> h.score()).min().orElse(0.0);
        double bm25Max = bm25Results.values().stream().mapToDouble(h -> h.score()).max().orElse(1.0);
        double knnMin = knnResults.values().stream().mapToDouble(h -> h.score()).min().orElse(0.0);
        double knnMax = knnResults.values().stream().mapToDouble(h -> h.score()).max().orElse(1.0);

        List<FusedDoc> fusedDocs = new ArrayList<>();

        for (String docId : allDocIds) {
            HitScore bm25Hit = bm25Results.get(docId);
            HitScore knnHit = knnResults.get(docId);

            // Min-Max 归一化 (使用安全除法)
            double bm25Norm = bm25Hit != null ? normalizeSafe(bm25Hit.score(), bm25Min, bm25Max) : 0.0;
            double knnNorm = knnHit != null ? normalizeSafe(knnHit.score(), knnMin, knnMax) : 0.0;

            // 核心融合公式
            double finalScore = alpha * bm25Norm + (1.0 - alpha) * knnNorm;

            HitScore primaryHit = bm25Hit != null ? bm25Hit : knnHit;
            if (primaryHit == null) continue;

            String hitReason = buildHitReason(queryInfo, alpha, bm25Hit, knnHit);
            fusedDocs.add(new FusedDoc(primaryHit, finalScore, bm25Norm, knnNorm, alpha, hitReason));
        }

        fusedDocs.sort((a, b) -> Double.compare(b.finalScore, a.finalScore));

        List<SearchResult> results = new ArrayList<>();
        int limit = Math.min(topK, fusedDocs.size());
        for (int i = 0; i < limit; i++) {
            FusedDoc fd = fusedDocs.get(i);
            HitScore hs = fd.hitScore;
            
            // 构建返回给前端的对象
            SearchResult sr = new SearchResult(
                    hs.fileMd5(), hs.chunkId(), hs.textContent(), fd.finalScore,
                    hs.userId(), hs.orgTag(), hs.isPublic(), null,
                    hs.pageNumber(), hs.anchorText(), "HYBRID_ADAPTIVE", hs.textContent()
            );
            sr.setChunkLevel(hs.chunkLevel());
            sr.setPartName(hs.partName());
            sr.setChapterName(hs.chapterName());
            sr.setSectionName(hs.sectionName());
            sr.setArticleId(hs.articleId());
            sr.setBm25NormScore(round(fd.bm25Norm));
            sr.setKnnNormScore(round(fd.knnNorm));
            sr.setAlpha(round(fd.alpha));
            sr.setHitReason(fd.hitReason);
            results.add(sr);
        }
        return results;
    }

    // 安全归一化：防止 (max - min) 为 0 导致结果变 Infinity 或 NaN
    private double normalizeSafe(double score, double min, double max) {
        if (max - min < 1e-6) {
            return 1.0; // 如果这批数据得分全一样，或者只有一条数据，直接给满分
        }
        return Math.max(0.0, Math.min(1.0, (score - min) / (max - min))); // 强制截断在 [0,1]
    }

    /**
     * 构建命中原因描述。
     */
    private String buildHitReason(LegalTermScorer.ArticleIdQueryInfo queryInfo,
            double alpha, HitScore bm25Hit, HitScore knnHit) {
        StringBuilder sb = new StringBuilder();
        if (!queryInfo.matchedArticleIds().isEmpty()) {
            sb.append("精确条文号匹配（")
                    .append(String.join("、", queryInfo.matchedArticleIds()))
                    .append("）");
        }
        if (alpha > 0.6) {
            if (!sb.isEmpty()) sb.append(" + ");
            sb.append("BM25 主导（α=").append(String.format("%.2f", alpha)).append("）");
        } else if (alpha < 0.4) {
            if (!sb.isEmpty()) sb.append(" + ");
            sb.append("向量语义主导（α=").append(String.format("%.2f", alpha)).append("）");
        } else {
            if (!sb.isEmpty()) sb.append(" + ");
            sb.append("混合检索（α=").append(String.format("%.2f", alpha)).append("）");
        }
        if (bm25Hit != null && knnHit != null) {
            sb.append(" · 双路命中");
        } else if (bm25Hit != null) {
            sb.append(" · 仅 BM25 命中");
        } else {
            sb.append(" · 仅向量命中");
        }
        return sb.toString();
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    // ============================================
    // 纯文本搜索（保留，作为向量生成失败时的后备）
    // ============================================

    /**
     * 仅使用文本匹配的带权限搜索方法。
     */
    private List<SearchResult> textOnlySearchWithPermission(String query, String userDbId,
            List<String> userEffectiveTags, int topK) {
        try {
            logger.debug("开始执行纯文本搜索，用户数据库ID: {}, 标签: {}", userDbId, userEffectiveTags);

            SearchResponse<EsDocument> response = esClient.search(s -> s
                    .index("knowledge_base")
                    .query(q -> q
                            .bool(b -> b
                                    .must(m -> m
                                            .match(ma -> ma
                                                    .field("textContent")
                                                    .query(query)
                                            )
                                    )
                                    .filter(f -> f
                                            .bool(bf -> {
                                                buildPermissionFilterInPlace(bf, userDbId, userEffectiveTags);
                                                return bf;
                                            })
                                    )
                            )
                    )
                    .minScore(0.3d)
                    .size(topK),
                    EsDocument.class
            );

            logger.debug("纯文本查询执行完成，命中数量: {}, 最大分数: {}",
                response.hits().total().value(), response.hits().maxScore());

            List<SearchResult> results = response.hits().hits().stream()
                    .map(hit -> {
                        assert hit.source() != null;
                        EsDocument doc = hit.source();
                        SearchResult sr = new SearchResult(
                                doc.getFileMd5(),
                                doc.getChunkId(),
                                doc.getTextContent(),
                                hit.score(),
                                doc.getUserId(),
                                doc.getOrgTag(),
                                doc.isPublic(),
                                null,
                                doc.getPageNumber(),
                                doc.getAnchorText(),
                                "TEXT_ONLY",
                                doc.getTextContent()
                        );
                        // 携带法律层级元数据
                        sr.setChunkLevel(doc.getChunkLevel());
                        sr.setPartName(doc.getPartName());
                        sr.setChapterName(doc.getChapterName());
                        sr.setSectionName(doc.getSectionName());
                        sr.setArticleId(doc.getArticleId());
                        return sr;
                    })
                    .toList();

            logger.debug("返回纯文本搜索结果数量: {}", results.size());
            attachFileNames(results);
            return results;
        } catch (Exception e) {
            logger.error("纯文本搜索失败", e);
            return new ArrayList<>();
        }
    }

    // ========================================
    // 向后兼容：无权限过滤的搜索方法
    // ========================================

    /**
     * 原始搜索方法，不包含权限过滤，保留向后兼容性。
     */
    public List<SearchResult> search(String query, int topK) {
        try {
            logger.debug("开始混合检索（无权限过滤），查询: {}, topK: {}", query, topK);

            LegalTermScorer.ArticleIdQueryInfo queryInfo = termScorer.analyzeQuery(query);
            double alpha = termScorer.computeAlpha(queryInfo.articleIdDensity(), queryInfo.legalTermDensity());

            final List<Float> queryVector = embedToVectorList(query, "system");
            if (queryVector == null) {
                logger.warn("向量生成失败，仅使用文本匹配进行搜索");
                return textOnlySearch(query, topK);
            }

            int recallK = topK * RECALL_FACTOR;

            // 并发双路查询
            CompletableFuture<Map<String, HitScore>> bm25Future = CompletableFuture.supplyAsync(() -> {
                try {
                    return executeBm25QueryNoPermission(query, recallK);
                } catch (Exception e) {
                    logger.error("BM25 无权限查询失败", e);
                    return Collections.emptyMap();
                }
            });

            CompletableFuture<Map<String, HitScore>> knnFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return executeKnnQueryNoPermission(queryVector, recallK);
                } catch (Exception e) {
                    logger.error("KNN 无权限查询失败", e);
                    return Collections.emptyMap();
                }
            });

            Map<String, HitScore> bm25Results = bm25Future.join();
            Map<String, HitScore> knnResults = knnFuture.join();

            return fuseAndRank(bm25Results, knnResults, alpha, topK, query, queryInfo);
        } catch (Exception e) {
            logger.error("搜索失败", e);
            try {
                return textOnlySearch(query, topK);
            } catch (Exception fallbackError) {
                logger.error("后备搜索也失败", fallbackError);
                throw new RuntimeException("搜索完全失败", fallbackError);
            }
        }
    }

    private Map<String, HitScore> executeBm25QueryNoPermission(String query, int recallK)
            throws Exception {
        SearchResponse<EsDocument> response = esClient.search(s -> {
            s.index("knowledge_base");
            s.size(recallK);
            s.query(q -> q.match(m -> m.field("textContent").query(query)));
            return s;
        }, EsDocument.class);
        return extractHits(response);
    }

    private Map<String, HitScore> executeKnnQueryNoPermission(List<Float> queryVector, int recallK)
            throws Exception {
        SearchResponse<EsDocument> response = esClient.search(s -> {
            s.index("knowledge_base");
            s.knn(kn -> kn
                    .field("vector")
                    .queryVector(queryVector)
                    .k(recallK)
                    .numCandidates(recallK * 2)
            );
            s.size(recallK);
            return s;
        }, EsDocument.class);
        return extractHits(response);
    }

    private List<SearchResult> textOnlySearch(String query, int topK) throws Exception {
        SearchResponse<EsDocument> response = esClient.search(s -> s
                .index("knowledge_base")
                .query(q -> q.match(m -> m.field("textContent").query(query)))
                .size(topK),
                EsDocument.class
        );

        return response.hits().hits().stream()
                .map(hit -> {
                    assert hit.source() != null;
                    EsDocument doc = hit.source();
                    SearchResult sr = new SearchResult(
                            doc.getFileMd5(),
                            doc.getChunkId(),
                            doc.getTextContent(),
                            hit.score(),
                            null, null, false,
                            null,
                            doc.getPageNumber(),
                            doc.getAnchorText(),
                            "TEXT_ONLY",
                            doc.getTextContent()
                    );
                    sr.setChunkLevel(doc.getChunkLevel());
                    sr.setPartName(doc.getPartName());
                    sr.setChapterName(doc.getChapterName());
                    sr.setSectionName(doc.getSectionName());
                    sr.setArticleId(doc.getArticleId());
                    return sr;
                })
                .toList();
    }

    // ========================================
    // 向量生成
    // ========================================

    /**
     * 生成查询向量，返回 List<Float>，失败时返回 null
     */
    private List<Float> embedToVectorList(String text, String requesterId) {
        try {
            List<float[]> vecs = embeddingClient.embed(List.of(text), requesterId,
                    EmbeddingClient.UsageType.QUERY);
            if (vecs == null || vecs.isEmpty()) {
                logger.warn("生成的向量为空");
                return null;
            }
            float[] raw = vecs.get(0);
            List<Float> list = new ArrayList<>(raw.length);
            for (float v : raw) {
                list.add(v);
            }
            return list;
        } catch (Exception e) {
            logger.error("生成向量失败", e);
            return null;
        }
    }

    // ========================================
    // 权限辅助方法（保留原有逻辑）
    // ========================================

    private List<String> getUserEffectiveOrgTags(String userId) {
        logger.debug("获取用户有效组织标签，用户ID: {}", userId);
        try {
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);
                user = userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
            } catch (NumberFormatException e) {
                user = userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
            }
            return orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
        } catch (Exception e) {
            logger.error("获取用户有效组织标签失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private String getUserDbId(String userId) {
        logger.debug("获取用户数据库ID，用户ID: {}", userId);
        try {
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);
                user = userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
                return userIdLong.toString();
            } catch (NumberFormatException e) {
                user = userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
                return user.getId().toString();
            }
        } catch (Exception e) {
            logger.error("获取用户数据库ID失败: {}", e.getMessage(), e);
            throw new RuntimeException("获取用户数据库ID失败", e);
        }
    }

    private void attachFileNames(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        try {
            Set<String> md5Set = results.stream()
                    .map(SearchResult::getFileMd5)
                    .collect(Collectors.toSet());
            List<FileUpload> uploads = fileUploadRepository.findByFileMd5In(new ArrayList<>(md5Set));
            Map<String, String> md5ToName = uploads.stream()
                    .collect(Collectors.toMap(FileUpload::getFileMd5, FileUpload::getFileName,
                            (existing, replacement) -> existing));
            results.forEach(r -> r.setFileName(md5ToName.get(r.getFileMd5())));
        } catch (Exception e) {
            logger.error("补充文件名失败", e);
        }
    }

    // ========================================
    // 内部数据结构
    // ========================================

    /**
     * 单路查询命中的文档得分及元数据。
     */
    private record HitScore(
            double score,
            String fileMd5,
            Integer chunkId,
            String textContent,
            String userId,
            String orgTag,
            boolean isPublic,
            Integer pageNumber,
            String anchorText,
            String chunkLevel,
            String partName,
            String chapterName,
            String sectionName,
            String articleId
    ) {}

    /**
     * 融合后的文档得分。
     */
    private record FusedDoc(
            HitScore hitScore,
            double finalScore,
            double bm25Norm,
            double knnNorm,
            double alpha,
            String hitReason
    ) {}
}