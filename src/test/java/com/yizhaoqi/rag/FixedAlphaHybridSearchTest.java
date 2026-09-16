package com.yizhaoqi.rag;

import com.yizhaoqi.rag.chunker.LegalTermScorer;
import com.yizhaoqi.rag.entity.SearchResult;
import com.yizhaoqi.rag.service.HybridSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 固定 α=0.5 (即 BM25 权重 0.5, KNN 权重 0.5) 下的 RAG 检索性能测试。
 *
 * 不修改 HybridSearchService 源码，通过 ReflectionTestUtils 注入 stub
 * LegalTermScorer，使其 computeAlpha() 始终返回 0.5。
 *
 * 测试 10 个法律查询问题，记录 Top-1 / Top-3 / Top-5 命中情况。
 *
 * 前置条件：
 *   - ES 在 localhost:9200 已启动
 *   - knowledge_base 索引已有数据
 *   - embedding 服务在 localhost:8000 可用
 */
@SpringBootTest
@DisplayName("固定 α=0.5 混合检索性能测试")
class FixedAlphaHybridSearchTest {

    @Autowired
    private HybridSearchService hybridSearchService;

    /**
     * 在每次测试前，用返回固定 α=0.5 的 stub 替换 termScorer。
     * analyzeQuery() 仍走基类逻辑，保证 BM25 路径的 articleId 匹配功能正常。
     */
    @BeforeEach
    void injectFixedAlphaScorer() {
        LegalTermScorer stubScorer = new LegalTermScorer() {
            @Override
            public double computeAlpha(ArticleIdQueryInfo queryInfo) {
                // 固定 α=0.5，即 BM25 和 KNN 各占 50% 权重
                return 0.5;
            }

            @Override
            public double computeAlpha(double articleIdDensity, double legalTermDensity) {
                return 0.5;
            }
        };
        ReflectionTestUtils.setField(hybridSearchService, "termScorer", stubScorer);
    }

    /**
     * 测试用例定义。
     */
    private record TestCase(int index, String type, String question, List<String> targetArticleIds) {}

    private List<TestCase> buildTestCases() {
        return List.of(
                new TestCase(1, "精确条文查询", "《民法典》第123条的内容是什么？",
                        List.of("第123条")),
                new TestCase(2, "精确条文查询", "《民法典》第562条是什么内容？",
                        List.of("第562条")),
                new TestCase(3, "精确条文查询", "刑法第232条是关于什么的？",
                        List.of("第232条")),
                new TestCase(4, "语义意图查询", "什么情况下可以解除合同？",
                        List.of("第562条", "第563条")),
                new TestCase(5, "语义意图查询", "合同不履行时如何处理？",
                        List.of("第577条")),
                new TestCase(6, "语义意图查询", "侵权责任怎么承担？",
                        List.of("侵权责任编")), // 语义类，检查命中的分块是否属于侵权责任编
                new TestCase(7, "语义意图查询", "什么情形下可以正当防卫？",
                        List.of("第20条")),
                new TestCase(8, "混合型查询", "故意伤害罪的量刑范围是什么？",
                        List.of("第234条")),
                new TestCase(9, "混合型查询", "自首怎么认定？",
                        List.of("第67条")),
                new TestCase(10, "混合型查询", "继承人可以继承哪些财产？",
                        List.of("继承编")) // 继承相关
        );
    }

    @Test
    @DisplayName("固定 α=0.5 下 10 个法律查询问题检索命中测试")
    void testFixedAlphaHybridSearch() {
        List<TestCase> testCases = buildTestCases();
        int topK = 5;

        // 结果收集
        List<ResultRow> results = new ArrayList<>();

        System.out.println();
        System.out.println("======================================================================");
        System.out.println("  固定 α=0.5 (BM25+KNN 各 50%) 混合检索实验结果");
        System.out.println("======================================================================");
        System.out.println();

        for (TestCase tc : testCases) {
            System.out.printf(">>> [%d/%d] 问题: %s%n", tc.index(), testCases.size(), tc.question());
            System.out.printf("    类型: %s | 目标法条: %s%n", tc.type(),
                    String.join(", ", tc.targetArticleIds()));

            List<SearchResult> searchResults;
            try {
                searchResults = hybridSearchService.search(tc.question(), topK);
            } catch (Exception e) {
                System.err.printf("    ❌ 搜索失败: %s%n", e.getMessage());
                results.add(new ResultRow(tc, 0, 0, 0, "搜索异常: " + e.getMessage()));
                continue;
            }

            if (searchResults == null || searchResults.isEmpty()) {
                System.err.println("    ⚠ 返回结果为空");
                results.add(new ResultRow(tc, 0, 0, 0, "结果为空"));
                continue;
            }

            // 检查 Top-1 / Top-3 / Top-5 命中
            boolean hitTop1 = isHit(searchResults.subList(0, Math.min(1, searchResults.size())), tc.targetArticleIds());
            boolean hitTop3 = isHit(searchResults.subList(0, Math.min(3, searchResults.size())), tc.targetArticleIds());
            boolean hitTop5 = isHit(searchResults, tc.targetArticleIds());

            // 打印详细结果
            for (int i = 0; i < searchResults.size(); i++) {
                SearchResult sr = searchResults.get(i);
                String articleId = sr.getArticleId() != null ? sr.getArticleId() : "-";
                String hit = isTargetMatched(sr, tc.targetArticleIds()) ? " ★" : "";
                System.out.printf("    %d. [score=%.4f] [α=%.4f] [articleId=%s] [chunkLevel=%s]%s%n",
                        i + 1, sr.getScore(), sr.getAlpha(),
                        articleId, sr.getChunkLevel(), hit);
            }

            System.out.printf("    命中: Top-1=%s, Top-3=%s, Top-5=%s%n%n",
                    hitTop1 ? "✓" : "✗", hitTop3 ? "✓" : "✗", hitTop5 ? "✓" : "✗");

            results.add(new ResultRow(tc,
                    hitTop1 ? 1 : 0, hitTop3 ? 1 : 0, hitTop5 ? 1 : 0, null));
        }

        // ===== 输出结果表格 =====
        System.out.println("======================================================================");
        System.out.println("  1. 问题级结果记录");
        System.out.println("======================================================================");
        System.out.println();
        System.out.println("| 序号 | 问题 | 类型 | 目标法条 | Top-1 | Top-3 | Top-5 | 备注 |");
        System.out.println("|---|---|---|---|---|---|---|---|");

        for (ResultRow row : results) {
            System.out.printf("| %d | %s | %s | %s | %d | %d | %d | %s |%n",
                    row.tc.index(),
                    row.tc.question(),
                    row.tc.type(),
                    String.join("、", row.tc.targetArticleIds()),
                    row.top1Hit,
                    row.top3Hit,
                    row.top5Hit,
                    row.note != null ? row.note : "");
        }

        System.out.println();
        System.out.println("======================================================================");
        System.out.println("  2. 汇总指标表");
        System.out.println("======================================================================");
        System.out.println();

        long totalTop1 = results.stream().filter(r -> r.top1Hit == 1).count();
        long totalTop3 = results.stream().filter(r -> r.top3Hit == 1).count();
        long totalTop5 = results.stream().filter(r -> r.top5Hit == 1).count();
        int total = results.size();

        long preciseTotal = results.stream().filter(r -> "精确条文查询".equals(r.tc.type())).count();
        long preciseHit5 = results.stream()
                .filter(r -> "精确条文查询".equals(r.tc.type()) && r.top5Hit == 1).count();
        long semanticTotal = results.stream().filter(r -> "语义意图查询".equals(r.tc.type())).count();
        long semanticHit5 = results.stream()
                .filter(r -> "语义意图查询".equals(r.tc.type()) && r.top5Hit == 1).count();
        long mixedTotal = results.stream().filter(r -> "混合型查询".equals(r.tc.type())).count();
        long mixedHit5 = results.stream()
                .filter(r -> "混合型查询".equals(r.tc.type()) && r.top5Hit == 1).count();

        double recallAt5 = (double) totalTop5 / total * 100;
        double preciseRate = preciseTotal > 0 ? (double) preciseHit5 / preciseTotal * 100 : 0;
        double semanticRate = semanticTotal > 0 ? (double) semanticHit5 / semanticTotal * 100 : 0;
        double mixedRate = mixedTotal > 0 ? (double) mixedHit5 / mixedTotal * 100 : 0;

        System.out.println("| 指标 | 值 | 说明 |");
        System.out.println("|---|---|---|");
        System.out.printf("| Recall@5 | %.0f%% | %d/%d 个问题 Top-5 命中 |%n",
                recallAt5, totalTop5, total);
        System.out.printf("| Top-1 命中率 | %.0f%% | %d/%d |%n",
                (double) totalTop1 / total * 100, totalTop1, total);
        System.out.printf("| Top-3 命中率 | %.0f%% | %d/%d |%n",
                (double) totalTop3 / total * 100, totalTop3, total);
        System.out.printf("| 精确题命中率 | %.0f%% | %d/%d |%n",
                preciseRate, preciseHit5, preciseTotal);
        System.out.printf("| 语义题命中率 | %.0f%% | %d/%d |%n",
                semanticRate, semanticHit5, semanticTotal);
        System.out.printf("| 混合题命中率 | %.0f%% | %d/%d |%n",
                mixedRate, mixedHit5, mixedTotal);

        System.out.println();
        System.out.println("======================================================================");
        System.out.println("  α 配置: 固定 0.5 (BM25 权重 0.5, KNN 权重 0.5)");
        System.out.println("======================================================================");
    }

    // -----------------------------------------------------------------
    // 命中判断
    // -----------------------------------------------------------------

    /**
     * 判断结果列表中是否有任意一条命中目标法条。
     */
    private boolean isHit(List<SearchResult> results, List<String> targetArticleIds) {
        return results.stream().anyMatch(sr -> isTargetMatched(sr, targetArticleIds));
    }

    /**
     * 判断单条结果是否命中目标法条。
     * 匹配规则：
     * 1. 精确 articleId 匹配：结果的 articleId 与目标之一完全相等
     * 2. 包含匹配（针对语义类如"继承编"/"侵权责任编"）：articleId 或 textContent 中包含目标字符串
     */
    private boolean isTargetMatched(SearchResult sr, List<String> targetArticleIds) {
        if (targetArticleIds == null || targetArticleIds.isEmpty()) {
            return false;
        }

        String resultArticleId = sr.getArticleId();
        String textContent = sr.getTextContent();

        for (String target : targetArticleIds) {
            // 精确 articleId 匹配
            if (resultArticleId != null && resultArticleId.equals(target)) {
                return true;
            }
            // 包含匹配：用于编级别目标（如"继承编"、"侵权责任编"）
            if (resultArticleId != null && resultArticleId.contains(target)) {
                return true;
            }
            if (textContent != null && textContent.contains(target)) {
                return true;
            }
        }
        return false;
    }

    // -----------------------------------------------------------------
    // 内部数据结构
    // -----------------------------------------------------------------

    private static class ResultRow {
        final TestCase tc;
        final int top1Hit;
        final int top3Hit;
        final int top5Hit;
        final String note;

        ResultRow(TestCase tc, int top1Hit, int top3Hit, int top5Hit, String note) {
            this.tc = tc;
            this.top1Hit = top1Hit;
            this.top3Hit = top3Hit;
            this.top5Hit = top5Hit;
            this.note = note;
        }
    }
}