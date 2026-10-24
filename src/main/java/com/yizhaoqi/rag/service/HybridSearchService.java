package com.yizhaoqi.rag.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.yizhaoqi.rag.chunker.DocumentType;
import com.yizhaoqi.rag.chunker.LegalTermScorer;
import com.yizhaoqi.rag.client.EmbeddingClient;
import com.yizhaoqi.rag.entity.EsDocument;
import com.yizhaoqi.rag.entity.SearchResult;
import com.yizhaoqi.rag.exception.CustomException;
import com.yizhaoqi.rag.model.FileUpload;
import com.yizhaoqi.rag.model.User;
import com.yizhaoqi.rag.repository.FileUploadRepository;
import com.yizhaoqi.rag.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 混合搜索服务 —— 意图识别 + 双路并发召回 + Adaptive α 融合。
 *
 * <p>检索前先由 {@link IntentRecognitionService} 判断本次应访问：</p>
 * <ul>
 *   <li>PUBLIC：仅公开法典索引；</li>
 *   <li>PRIVATE：仅私人案件索引；</li>
 *   <li>MIXED：两个索引分别召回后合并，保证混合提问同时拿到法典和案件材料。</li>
 * </ul>
 *
 * <p>每个范围内仍复用原有 BM25 + KNN 双路召回、Adaptive α 融合和权限过滤。
 * 私人案件文档只允许所有者访问，永远不会通过 public / orgTag 分支泄漏。</p>
 */
@Service
public class HybridSearchService {

    private static final Logger logger = LoggerFactory.getLogger(HybridSearchService.class);

    /** BM25 和 KNN 的召回窗口扩大系数 */
    private static final int RECALL_FACTOR = 3;

    private static final String PRIVATE_DOCUMENT_TYPE = DocumentType.PRIVATE_LEGAL.name();
    private static final String PUBLIC_CORPUS = "PUBLIC_CODE";
    private static final String PRIVATE_CORPUS = "PRIVATE_CASE";

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private IntentRecognitionService intentRecognitionService;

    @Autowired
    private DocumentChunkContextService documentChunkContextService;

    private final LegalTermScorer termScorer = new LegalTermScorer();

    /**
     * 向后兼容入口：不携带对话上下文。
     */
    public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
        return searchWithPermission(query, userId, topK, null);
    }

    /**
     * 意图识别 + 按范围双路召回。
     *
     * @param query               用户问题
     * @param userId              用户ID
     * @param topK                返回条数
     * @param conversationContext 最近对话/检索摘要，用于指代消解
     */
    public List<SearchResult> searchWithPermission(String query, String userId, int topK,
            String conversationContext) {
        logger.debug("开始带权限检索，查询: {}, 用户ID: {}, topK: {}", query, userId, topK);

        try {
            UserContext userContext = resolveUserContext(userId);
            RetrievalIntent intent = intentRecognitionService.recognize(
                    query, conversationContext, userContext.effectiveTags());
            SearchScope scope = SearchScope.from(intent);

            LegalTermScorer.ArticleIdQueryInfo queryInfo = termScorer.analyzeQuery(query);
            double alpha = termScorer.computeAlpha(queryInfo);
            logger.info("Adaptive α: query='{}', articleIdDensity={}, legalTermDensity={}, α={}, intent={}",
                    query, queryInfo.articleIdDensity(), queryInfo.legalTermDensity(), alpha, intent.category());

            final List<Float> queryVector = embedToVectorList(query, userId);
            List<SearchResult> results;
            if (scope == SearchScope.BOTH) {
                CompletableFuture<List<SearchResult>> publicFuture = CompletableFuture.supplyAsync(
                        () -> retrieveSafe(SearchScope.PUBLIC, query, userContext, topK, queryInfo, alpha, queryVector));
                CompletableFuture<List<SearchResult>> privateFuture = CompletableFuture.supplyAsync(
                        () -> retrieveSafe(SearchScope.PRIVATE, query, userContext, topK, queryInfo, alpha, queryVector));
                results = mergeMixed(publicFuture.join(), privateFuture.join(), topK);
            } else {
                results = retrieveSafe(scope, query, userContext, topK, queryInfo, alpha, queryVector);
            }

            attachIntentMetadata(results, intent, scope);
            attachFileNames(results);
            logger.info("检索完成: query='{}', intent={}, scope={}, resultCount={}, privateCount={}",
                    query, intent.category(), scope, results.size(),
                    results.stream().filter(SearchResult::isPrivateDocument).count());
            return results;
        } catch (Exception e) {
            logger.error("带权限检索失败，query: {}, userId: {}", query, userId, e);
            return Collections.emptyList();
        }
    }

    private List<SearchResult> retrieveSafe(SearchScope scope, String query, UserContext userContext,
            int topK, LegalTermScorer.ArticleIdQueryInfo queryInfo, double alpha, List<Float> queryVector) {
        try {
            return retrieve(scope, query, userContext, topK, queryInfo, alpha, queryVector);
        } catch (Exception e) {
            logger.error("{} 范围召回失败，query: {}", scope, query, e);
            return Collections.emptyList();
        }
    }

    private List<SearchResult> retrieve(SearchScope scope, String query, UserContext userContext,
            int topK, LegalTermScorer.ArticleIdQueryInfo queryInfo, double alpha, List<Float> queryVector) {
        if (queryVector == null) {
            logger.warn("向量生成失败，{} 范围退化为纯 BM25 搜索", scope);
            return textOnlySearchWithPermission(query, userContext, topK, scope);
        }

        int recallK = Math.max(topK, topK * RECALL_FACTOR);
        String index = scope.index();

        CompletableFuture<Map<String, HitScore>> bm25Future = CompletableFuture.supplyAsync(() -> {
            try {
                return executeBm25Query(query, userContext, recallK, queryInfo, index, scope);
            } catch (Exception e) {
                logger.error("BM25 查询失败，scope: {}", scope, e);
                return Collections.emptyMap();
            }
        });
        CompletableFuture<Map<String, HitScore>> knnFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return executeKnnQuery(queryVector, userContext, recallK, index, scope);
            } catch (Exception e) {
                logger.error("KNN 查询失败，scope: {}", scope, e);
                return Collections.emptyMap();
            }
        });

        Map<String, HitScore> bm25Results = bm25Future.join();
        Map<String, HitScore> knnResults = knnFuture.join();
        logger.info("双路召回完成，scope: {}, BM25={} 条, KNN={} 条",
                scope, bm25Results.size(), knnResults.size());

        List<SearchResult> results = fuseAndRank(bm25Results, knnResults, alpha, topK, queryInfo, scope);
        expandParentContexts(results, scope, userContext);
        return results;
    }

    // ========================
    // 路径 A：BM25 倒排召回
    // ========================

    private Map<String, HitScore> executeBm25Query(String query, UserContext userContext, int recallK,
            LegalTermScorer.ArticleIdQueryInfo queryInfo, String index, SearchScope scope) throws Exception {
        List<String> expandedArticleIds = termScorer.expandArticleIds(queryInfo.matchedArticleIds());
        // 私人案件文档不一定出现精确条文号；混合/私人范围使用宽匹配，确保案件内容能被召回
        boolean hasArticleIds = !expandedArticleIds.isEmpty() && scope == SearchScope.PUBLIC;

        SearchResponse<EsDocument> response = esClient.search(s -> {
            s.index(index);
            s.size(recallK);
            s.query(q -> q.bool(b -> {
                if (hasArticleIds) {
                    b.must(m -> m.bool(inner -> {
                        for (String articleId : expandedArticleIds) {
                            inner.should(sh -> sh.matchPhrase(mp -> mp
                                    .field("textContent").query(articleId).boost(5.0f)));
                            inner.should(sh -> sh.term(t -> t
                                    .field("articleId").value(articleId).boost(10.0f)));
                        }
                        inner.minimumShouldMatch("1");
                        return inner;
                    }));
                    String lawName = extractLawName(query);
                    if (!lawName.isEmpty()) {
                        b.should(sh -> sh.matchPhrase(mp -> mp
                                .field("textContent").query(lawName).boost(0.5f)));
                    }
                } else {
                    b.must(m -> m.match(ma -> ma.field("textContent").query(query)));
                }
                b.filter(f -> f.bool(bf -> {
                    buildPermissionFilterInPlace(bf, userContext);
                    applyScopeFilter(bf, scope);
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
     */
    private String extractLawName(String query) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "^(.+?)(第[0-9零一二三四五六七八九十百千]+条)");
        java.util.regex.Matcher matcher = pattern.matcher(query);
        if (matcher.find()) {
            String prefix = matcher.group(1);
            if (prefix.length() > 10) {
                return prefix.substring(Math.max(0, prefix.length() - 6)).trim();
            }
            return prefix.trim();
        }
        return "";
    }

    // ========================
    // 路径 B：KNN 向量召回
    // ========================

    private Map<String, HitScore> executeKnnQuery(List<Float> queryVector, UserContext userContext,
            int recallK, String index, SearchScope scope) throws Exception {
        int numCandidates = recallK * 2;

        SearchResponse<EsDocument> response = esClient.search(s -> {
            s.index(index);
            s.knn(kn -> kn
                    .field("vector")
                    .queryVector(queryVector)
                    .k(recallK)
                    .numCandidates(numCandidates)
                    .filter(f -> f.bool(b -> {
                        buildPermissionFilterInPlace(b, userContext);
                        applyScopeFilter(b, scope);
                        return b;
                    }))
            );
            s.size(recallK);
            return s;
        }, EsDocument.class);

        return extractHits(response);
    }

    // ========================
    // 权限过滤
    // ========================

    private void buildPermissionFilterInPlace(BoolQuery.Builder bf, UserContext userContext) {
        // 条件1: 用户自己的文档（包含私人案件文档）
        bf.should(s1 -> s1.term(t -> t.field("userId").value(userContext.userDbId())));

        // 条件2: 公开文档，但私人案件文档即使 isPublic=true 也不过
        bf.should(s2 -> s2.bool(inner -> {
            inner.must(m -> m.term(t -> t.field("isPublic").value(true)));
            inner.mustNot(mn -> mn.term(t -> t.field("documentType").value(PRIVATE_DOCUMENT_TYPE)));
            return inner;
        }));

        // 条件3: 组织标签匹配，但私人案件文档不能走组织分支
        bf.should(s3 -> {
            List<String> tags = userContext.effectiveTags() == null
                    ? List.of() : userContext.effectiveTags();
            if (tags.isEmpty()) {
                return s3.matchNone(mn -> mn);
            }
            return s3.bool(inner -> {
                for (String tag : tags) {
                    inner.should(sh -> sh.term(t -> t.field("orgTag").value(tag)));
                }
                inner.minimumShouldMatch("1");
                inner.mustNot(mn -> mn.term(t -> t.field("documentType").value(PRIVATE_DOCUMENT_TYPE)));
                return inner;
            });
        });

        bf.minimumShouldMatch("1");
    }

    /**
     * 索引级别的双保险：公开范围排除私人类型，私人范围只允许私人类型/早期 private 分块。
     */
    private void applyScopeFilter(BoolQuery.Builder bf, SearchScope scope) {
        // 父块只用于回补上下文，不参与召回；缺省 chunkRole 的历史数据不受影响
        bf.mustNot(mn -> mn.term(t -> t.field("chunkRole").value("PARENT")));
        if (scope == SearchScope.PUBLIC) {
            bf.mustNot(mn -> mn.term(t -> t.field("documentType").value(PRIVATE_DOCUMENT_TYPE)));
            bf.mustNot(mn -> mn.term(t -> t.field("chunkLevel").value("private")));
        } else if (scope == SearchScope.PRIVATE) {
            bf.must(m -> m.bool(inner -> {
                inner.should(sh -> sh.term(t -> t.field("documentType").value(PRIVATE_DOCUMENT_TYPE)));
                inner.should(sh -> sh.term(t -> t.field("chunkLevel").value("private")));
                inner.minimumShouldMatch("1");
                return inner;
            }));
        }
    }

    // ========================
    // 搜索结果提取
    // ========================

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
            String documentType = doc.getDocumentType();
            if (documentType == null || documentType.isBlank()) {
                documentType = "private".equals(doc.getChunkLevel())
                        ? DocumentType.PRIVATE_LEGAL.name()
                        : DocumentType.STRUCTURED_LAW.name();
            }
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
                    doc.getArticleId(),
                    documentType,
                    doc.getChunkStrategy(),
                    doc.getCaseSection(),
                    doc.getBlockId(),
                    doc.getParentBlockId(),
                    doc.getContentType()
            ));
        }
        return result;
    }

    // ========================
    // 融合打分
    // ========================

    private List<SearchResult> fuseAndRank(Map<String, HitScore> bm25Results,
            Map<String, HitScore> knnResults, double alpha, int topK,
            LegalTermScorer.ArticleIdQueryInfo queryInfo, SearchScope scope) {
        Set<String> allDocIds = new LinkedHashSet<>();
        allDocIds.addAll(bm25Results.keySet());
        allDocIds.addAll(knnResults.keySet());

        double bm25Min = bm25Results.values().stream().mapToDouble(HitScore::score).min().orElse(0.0);
        double bm25Max = bm25Results.values().stream().mapToDouble(HitScore::score).max().orElse(1.0);
        double knnMin = knnResults.values().stream().mapToDouble(HitScore::score).min().orElse(0.0);
        double knnMax = knnResults.values().stream().mapToDouble(HitScore::score).max().orElse(1.0);

        List<FusedDoc> fusedDocs = new ArrayList<>();
        for (String docId : allDocIds) {
            HitScore bm25Hit = bm25Results.get(docId);
            HitScore knnHit = knnResults.get(docId);
            double bm25Norm = bm25Hit != null ? normalizeSafe(bm25Hit.score(), bm25Min, bm25Max) : 0.0;
            double knnNorm = knnHit != null ? normalizeSafe(knnHit.score(), knnMin, knnMax) : 0.0;
            double finalScore = alpha * bm25Norm + (1.0 - alpha) * knnNorm;

            HitScore primaryHit = bm25Hit != null ? bm25Hit : knnHit;
            if (primaryHit == null) {
                continue;
            }
            String hitReason = buildHitReason(queryInfo, alpha, bm25Hit, knnHit);
            fusedDocs.add(new FusedDoc(primaryHit, finalScore, bm25Norm, knnNorm, alpha, hitReason));
        }

        fusedDocs.sort((a, b) -> Double.compare(b.finalScore(), a.finalScore()));

        List<SearchResult> results = new ArrayList<>();
        int limit = Math.min(topK, fusedDocs.size());
        for (int i = 0; i < limit; i++) {
            FusedDoc fd = fusedDocs.get(i);
            HitScore hs = fd.hitScore();
            SearchResult sr = new SearchResult(
                    hs.fileMd5(), hs.chunkId(), hs.textContent(), fd.finalScore(),
                    hs.userId(), hs.orgTag(), hs.isPublic(), null,
                    hs.pageNumber(), hs.anchorText(), "HYBRID_ADAPTIVE", hs.textContent());
            sr.setChunkLevel(hs.chunkLevel());
            sr.setPartName(hs.partName());
            sr.setChapterName(hs.chapterName());
            sr.setSectionName(hs.sectionName());
            sr.setArticleId(hs.articleId());
            sr.setBm25NormScore(round(fd.bm25Norm()));
            sr.setKnnNormScore(round(fd.knnNorm()));
            sr.setAlpha(round(fd.alpha()));
            sr.setHitReason(fd.hitReason());
            sr.setDocumentType(hs.documentType());
            sr.setChunkStrategy(hs.chunkStrategy());
            sr.setCaseSection(hs.caseSection());
            sr.setBlockId(hs.blockId());
            sr.setParentBlockId(hs.parentBlockId());
            sr.setContentType(hs.contentType());
            sr.setSourceCorpus(scope == SearchScope.PRIVATE ? PRIVATE_CORPUS : PUBLIC_CORPUS);
            sr.setSearchScope(scope.name());
            results.add(sr);
        }
        return results;
    }

    private double normalizeSafe(double score, double min, double max) {
        if (max - min < 1e-6) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(1.0, (score - min) / (max - min)));
    }

    private String buildHitReason(LegalTermScorer.ArticleIdQueryInfo queryInfo, double alpha,
            HitScore bm25Hit, HitScore knnHit) {
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
    // 纯文本搜索（向量生成失败时的后备）
    // ============================================

    private List<SearchResult> textOnlySearchWithPermission(String query, UserContext userContext,
            int topK, SearchScope scope) {
        try {
            SearchResponse<EsDocument> response = esClient.search(s -> s
                    .index(scope.index())
                    .query(q -> q.bool(b -> b
                            .must(m -> m.match(ma -> ma.field("textContent").query(query)))
                            .filter(f -> f.bool(bf -> {
                                buildPermissionFilterInPlace(bf, userContext);
                                applyScopeFilter(bf, scope);
                                return bf;
                            }))
                    ))
                    .minScore(scope == SearchScope.PRIVATE ? 0.1d : 0.3d)
                    .size(topK),
                    EsDocument.class);

            List<SearchResult> results = new ArrayList<>(response.hits().hits().stream()
                    .map(hit -> toSearchResult(hit, scope, "TEXT_ONLY"))
                    .filter(java.util.Objects::nonNull)
                    .toList());
            expandParentContexts(results, scope, userContext);
            return results;
        } catch (Exception e) {
            logger.error("纯文本搜索失败，scope: {}", scope, e);
            return new ArrayList<>();
        }
    }

    private SearchResult toSearchResult(Hit<EsDocument> hit, SearchScope scope, String retrievalMode) {
        if (hit.source() == null) {
            return null;
        }
        EsDocument doc = hit.source();
        String documentType = doc.getDocumentType();
        if (documentType == null || documentType.isBlank()) {
            documentType = "private".equals(doc.getChunkLevel())
                    ? DocumentType.PRIVATE_LEGAL.name()
                    : DocumentType.STRUCTURED_LAW.name();
        }
        SearchResult sr = new SearchResult(
                doc.getFileMd5(), doc.getChunkId(), doc.getTextContent(),
                hit.score() != null ? hit.score() : 0.0,
                doc.getUserId(), doc.getOrgTag(), doc.isPublic(), null,
                doc.getPageNumber(), doc.getAnchorText(), retrievalMode, doc.getTextContent());
        sr.setChunkLevel(doc.getChunkLevel());
        sr.setPartName(doc.getPartName());
        sr.setChapterName(doc.getChapterName());
        sr.setSectionName(doc.getSectionName());
        sr.setArticleId(doc.getArticleId());
        sr.setDocumentType(documentType);
        sr.setChunkStrategy(doc.getChunkStrategy());
        sr.setCaseSection(doc.getCaseSection());
        sr.setBlockId(doc.getBlockId());
        sr.setParentBlockId(doc.getParentBlockId());
        sr.setContentType(doc.getContentType());
        sr.setSourceCorpus(scope == SearchScope.PRIVATE ? PRIVATE_CORPUS : PUBLIC_CORPUS);
        sr.setSearchScope(scope.name());
        return sr;
    }

    // ========================================
    // 父子分块：命中子块后回补父段上下文
    // ========================================

    private void expandParentContexts(List<SearchResult> results, SearchScope scope, UserContext userContext) {
        if (results == null || results.isEmpty()) {
            return;
        }
        Set<String> parentIds = results.stream()
                .map(SearchResult::getParentBlockId)
                .filter(java.util.Objects::nonNull)
                .filter(id -> !id.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (parentIds.isEmpty()) {
            return;
        }
        try {
            Map<String, String> parentTexts = documentChunkContextService.findParentTexts(
                    parentIds, scope == SearchScope.PRIVATE, userContext.userDbId());
            results.forEach(result -> {
                String parentText = parentTexts.get(result.getParentBlockId());
                if (parentText != null && !parentText.isBlank()) {
                    result.setContextText(parentText);
                }
            });
            logger.info("父子上下文回补完成，scope={}, 命中子块数={}, 父块数={}",
                    scope, results.size(), parentTexts.size());
        } catch (Exception e) {
            logger.warn("父子上下文回补失败，回退子块文本: {}", e.getMessage());
        }
    }

    // ========================================
    // 混合意图结果合并
    // ========================================

    private List<SearchResult> mergeMixed(List<SearchResult> publicResults,
            List<SearchResult> privateResults, int topK) {
        List<SearchResult> merged = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        int perCorpus = Math.max(1, topK / 2);
        List<SearchResult> publicTop = publicResults.stream().limit(perCorpus).toList();
        List<SearchResult> privateTop = privateResults.stream().limit(perCorpus).toList();
        addUnique(merged, seen, publicTop);
        addUnique(merged, seen, privateTop);

        List<SearchResult> rest = new ArrayList<>();
        rest.addAll(publicResults);
        rest.addAll(privateResults);
        rest.sort(Comparator.comparing(SearchResult::getScore,
                Comparator.nullsLast(Comparator.reverseOrder())));
        for (SearchResult result : rest) {
            if (merged.size() >= topK) {
                break;
            }
            addUnique(merged, seen, List.of(result));
        }

        merged.sort(Comparator.comparing(SearchResult::getScore,
                Comparator.nullsLast(Comparator.reverseOrder())));
        if (merged.size() > topK) {
            return new ArrayList<>(merged.subList(0, topK));
        }
        return merged;
    }

    private void addUnique(List<SearchResult> target, Set<String> seen, List<SearchResult> candidates) {
        for (SearchResult result : candidates) {
            String key = result.getFileMd5() + "#" + result.getDocumentType() + "#" + result.getChunkId();
            if (seen.add(key)) {
                target.add(result);
            }
        }
    }

    private void attachIntentMetadata(List<SearchResult> results, RetrievalIntent intent, SearchScope scope) {
        for (SearchResult result : results) {
            result.setSearchScope(scope.name());
            result.setIntentCategory(intent.category() == null
                    ? IntentCategory.PUBLIC.getApiValue() : intent.category().getApiValue());
            result.setIntentConfidence(intent.confidence());
            result.setIntentReason(intent.reason());
        }
    }

    // ========================================
    // 无权限搜索（仅公开法典，向后兼容）
    // ========================================

    public List<SearchResult> search(String query, int topK) {
        try {
            LegalTermScorer.ArticleIdQueryInfo queryInfo = termScorer.analyzeQuery(query);
            double alpha = termScorer.computeAlpha(queryInfo);
            final List<Float> queryVector = embedToVectorList(query, "system");
            if (queryVector == null) {
                return textOnlySearch(query, topK);
            }

            int recallK = Math.max(topK, topK * RECALL_FACTOR);
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

            return fuseAndRank(bm25Future.join(), knnFuture.join(), alpha, topK,
                    queryInfo, SearchScope.PUBLIC);
        } catch (Exception e) {
            logger.error("搜索失败", e);
            throw new RuntimeException("搜索完全失败", e);
        }
    }

    private Map<String, HitScore> executeBm25QueryNoPermission(String query, int recallK) throws Exception {
        SearchResponse<EsDocument> response = esClient.search(s -> {
            s.index(ElasticsearchService.PUBLIC_INDEX);
            s.size(recallK);
            s.query(q -> q.match(m -> m.field("textContent").query(query)));
            return s;
        }, EsDocument.class);
        return extractHits(response);
    }

    private Map<String, HitScore> executeKnnQueryNoPermission(List<Float> queryVector, int recallK)
            throws Exception {
        SearchResponse<EsDocument> response = esClient.search(s -> {
            s.index(ElasticsearchService.PUBLIC_INDEX);
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
                .index(ElasticsearchService.PUBLIC_INDEX)
                .query(q -> q.match(m -> m.field("textContent").query(query)))
                .size(topK),
                EsDocument.class
        );
        return response.hits().hits().stream()
                .map(hit -> {
                    if (hit.source() == null) {
                        return null;
                    }
                    EsDocument doc = hit.source();
                    SearchResult sr = new SearchResult(
                            doc.getFileMd5(), doc.getChunkId(), doc.getTextContent(),
                            hit.score() != null ? hit.score() : 0.0,
                            null, null, false, null,
                            doc.getPageNumber(), doc.getAnchorText(), "TEXT_ONLY", doc.getTextContent());
                    sr.setChunkLevel(doc.getChunkLevel());
                    sr.setPartName(doc.getPartName());
                    sr.setChapterName(doc.getChapterName());
                    sr.setSectionName(doc.getSectionName());
                    sr.setArticleId(doc.getArticleId());
                    sr.setDocumentType(DocumentType.STRUCTURED_LAW.name());
                    sr.setSourceCorpus(PUBLIC_CORPUS);
                    sr.setSearchScope(SearchScope.PUBLIC.name());
                    return sr;
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    // ========================================
    // 向量生成
    // ========================================

    private List<Float> embedToVectorList(String text, String requesterId) {
        try {
            List<float[]> vectors = embeddingClient.embed(List.of(text), requesterId,
                    EmbeddingClient.UsageType.QUERY);
            if (vectors == null || vectors.isEmpty()) {
                logger.warn("生成的向量为空");
                return null;
            }
            float[] raw = vectors.get(0);
            List<Float> list = new ArrayList<>(raw.length);
            for (float value : raw) {
                list.add(value);
            }
            return list;
        } catch (Exception e) {
            logger.error("生成查询向量失败", e);
            return null;
        }
    }

    // ========================================
    // 权限辅助方法
    // ========================================

    private UserContext resolveUserContext(String userId) {
        try {
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);
                user = userRepository.findById(userIdLong)
                        .orElseThrow(() -> new CustomException("User not found with ID: " + userId,
                                HttpStatus.NOT_FOUND));
            } catch (NumberFormatException e) {
                user = userRepository.findByUsername(userId)
                        .orElseThrow(() -> new CustomException("User not found: " + userId,
                                HttpStatus.NOT_FOUND));
            }
            List<String> effectiveTags = orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
            return new UserContext(user.getId().toString(), effectiveTags);
        } catch (Exception e) {
            logger.error("解析用户权限上下文失败: {}", e.getMessage(), e);
            throw new RuntimeException("解析用户权限上下文失败", e);
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
            Map<String, String> fileNameByKey = new LinkedHashMap<>();
            Map<String, String> fileNameByMd5 = new LinkedHashMap<>();
            for (FileUpload upload : uploads) {
                String key = upload.getFileMd5() + "|" + upload.getDocumentTypeEnum().name();
                fileNameByKey.putIfAbsent(key, upload.getFileName());
                fileNameByMd5.putIfAbsent(upload.getFileMd5(), upload.getFileName());
            }
            results.forEach(result -> {
                String key = result.getFileMd5() + "|" + DocumentType.from(result.getDocumentType()).name();
                result.setFileName(fileNameByKey.getOrDefault(key,
                        fileNameByMd5.get(result.getFileMd5())));
            });
        } catch (Exception e) {
            logger.error("补充文件名失败", e);
        }
    }

    // ========================================
    // 内部数据结构
    // ========================================

    private enum SearchScope {
        PUBLIC,
        PRIVATE,
        BOTH;

        static SearchScope from(RetrievalIntent intent) {
            if (intent == null || intent.category() == null) {
                return PUBLIC;
            }
            return switch (intent.category()) {
                case PUBLIC -> PUBLIC;
                case PRIVATE -> PRIVATE;
                case MIXED -> BOTH;
            };
        }

        String index() {
            return this == PRIVATE ? ElasticsearchService.PRIVATE_INDEX : ElasticsearchService.PUBLIC_INDEX;
        }
    }

    private record UserContext(String userDbId, List<String> effectiveTags) {
    }

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
            String articleId,
            String documentType,
            String chunkStrategy,
            String caseSection,
            String blockId,
            String parentBlockId,
            String contentType
    ) {
    }

    private record FusedDoc(
            HitScore hitScore,
            double finalScore,
            double bm25Norm,
            double knnNorm,
            double alpha,
            String hitReason
    ) {
    }
}
