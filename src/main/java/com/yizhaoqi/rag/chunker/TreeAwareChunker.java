package com.yizhaoqi.rag.chunker;

import com.yizhaoqi.rag.parser.LegalNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 树状多级分块器 —— 基于 LegalStructureParser 解析出的法律树状结构，
 * 生成条级（article）、节级（section）、章级（chapter）三级粒度的 Chunk。
 *
 * 每个 Chunk 携带父级上下文（编/章/节 名称前缀），并计算法律术语密度与条文号密度。
 */
public class TreeAwareChunker {

    private static final Logger logger = LoggerFactory.getLogger(TreeAwareChunker.class);

    private final LegalTermScorer termScorer;
    private final int maxTokens;
    private final int overlapTokens;

    /**
     * @param maxTokens     条级 Chunk 最大 token 数
     * @param overlapTokens 重叠 token 数（保留，用于与旧接口兼容）
     */
    public TreeAwareChunker(int maxTokens, int overlapTokens) {
        this(new LegalTermScorer(), maxTokens, overlapTokens);
    }

    public TreeAwareChunker(LegalTermScorer termScorer, int maxTokens, int overlapTokens) {
        this.termScorer = termScorer;
        this.maxTokens = maxTokens;
        this.overlapTokens = overlapTokens;
    }

    /**
     * 从解析好的树根节点生成所有 Chunk。
     *
     * @param root LegalStructureParser 解析出的根节点
     * @return 携带完整元数据的 ChunkDTO 列表
     */
    public List<ChunkDTO> chunk(LegalNode root) {
        List<ChunkDTO> result = new ArrayList<>();

        if (root == null) {
            logger.warn("根节点为空，无法生成分块");
            return result;
        }

        // Step 1: 生成条级 Chunk（每个 Article 节点 = 1 个条级 Chunk）
        List<LegalNode> articles = root.collectArticles();
        for (LegalNode article : articles) {
            String text = buildArticleChunkText(article);
            if (text.isBlank()) {
                continue;
            }

            // 对过长条文按款项切分
            int tokenCount = termScorer.estimateTokenCount(text);
            if (tokenCount > maxTokens) {
                // 按子节点（款）切分
                if (!article.getChildren().isEmpty()) {
                    for (LegalNode paragraph : article.getChildren()) {
                        String paraText = buildArticleChunkWithPrefix(article, paragraph);
                        if (!paraText.isBlank()) {
                            result.add(buildArticleChunkDTO(article, paraText));
                        }
                    }
                } else {
                    // 无子节点，整体保留
                    result.add(buildArticleChunkDTO(article, text));
                }
            } else {
                result.add(buildArticleChunkDTO(article, text));
            }
        }
        logger.info("条级 Chunk 生成完成，共 {} 个", result.size());

        // Step 2: 生成节级 Chunk（同一 Section 下的所有条文合并）
        List<LegalNode> sections = root.collectSections();
        for (LegalNode section : sections) {
            List<LegalNode> sectionArticles = collectArticlesUnder(section);
            if (sectionArticles.size() >= 3) {
                ChunkDTO sectionChunk = buildHierarchyChunk(section, sectionArticles, "section");
                if (sectionChunk != null && !sectionChunk.getTextContent().isBlank()) {
                    result.add(sectionChunk);
                }
            }
        }
        logger.info("节级 Chunk 生成完成，共 {} 个", result.size());

        // Step 3: 生成章级 Chunk（同一 Chapter 下的所有条文合并，粗粒度召回补充）
        List<LegalNode> chapters = root.collectChapters();
        for (LegalNode chapter : chapters) {
            List<LegalNode> chapterArticles = collectArticlesUnder(chapter);
            if (chapterArticles.size() >= 10) {
                ChunkDTO chapterChunk = buildHierarchyChunk(chapter, chapterArticles, "chapter");
                if (chapterChunk != null && !chapterChunk.getTextContent().isBlank()) {
                    result.add(chapterChunk);
                }
            }
        }
        logger.info("树状多级分块完成，最终共生成 {} 个 Chunk", result.size());

        return result;
    }

    /**
     * 收集指定节点下所有 ARTICLE 类型的后代节点。
     */
    private List<LegalNode> collectArticlesUnder(LegalNode node) {
        return node.collectArticles();
    }

    /**
     * 构建条级 Chunk 文本：注入父级标题前缀。
     * 格式：【第一编 总则 · 第五章 民事权利】第123条 民事主体依法享有知识产权...
     */
    private String buildArticleChunkText(LegalNode article) {
        StringBuilder sb = new StringBuilder();

        // 注入父级标题前缀
        String prefix = buildHierarchyPrefix(article);
        if (!prefix.isEmpty()) {
            sb.append("【").append(prefix).append("】");
        }

        sb.append(article.fullText());
        return sb.toString().trim();
    }

    /**
     * 为单个款项构建带前缀的条级 Chunk 文本。
     */
    private String buildArticleChunkWithPrefix(LegalNode article, LegalNode paragraph) {
        StringBuilder sb = new StringBuilder();

        String prefix = buildHierarchyPrefix(article);
        if (!prefix.isEmpty()) {
            sb.append("【").append(prefix).append("】");
        }

        sb.append(article.getHeading());
        sb.append("\n");
        sb.append(paragraph.fullText());
        return sb.toString().trim();
    }

    /**
     * 为当前节点构建层级前缀字符串。
     * 如 "第一编 总则 · 第五章 民事权利"
     */
    private String buildHierarchyPrefix(LegalNode node) {
        StringBuilder sb = new StringBuilder();
        LegalNode part = node.getPartAncestor();
        LegalNode chapter = node.getChapterAncestor();
        LegalNode section = node.getSectionAncestor();

        if (part != null && !part.getHeading().isEmpty()) {
            sb.append(part.getHeading());
        }
        if (chapter != null && !chapter.getHeading().isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append(" · ");
            }
            sb.append(chapter.getHeading());
        }
        if (section != null && !section.getHeading().isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append(" · ");
            }
            sb.append(section.getHeading());
        }
        return sb.toString();
    }

    /**
     * 构建条级 ChunkDTO。
     */
    private ChunkDTO buildArticleChunkDTO(LegalNode article, String text) {
        LegalNode part = article.getPartAncestor();
        LegalNode chapter = article.getChapterAncestor();
        LegalNode section = article.getSectionAncestor();

        return ChunkDTO.builder()
                .textContent(text.trim())
                .chunkLevel("article")
                .partName(part != null ? part.getHeading() : null)
                .chapterName(chapter != null ? chapter.getHeading() : null)
                .sectionName(section != null ? section.getHeading() : null)
                .articleId(article.getArticleId())
                .tokenCount(termScorer.estimateTokenCount(text))
                .legalTermDensity(termScorer.computeLegalTermDensity(text))
                .articleIdDensity(termScorer.computeArticleIdDensity(text))
                .build();
    }

    /**
     * 构建节级/章级 Chunk（合并多条文的粗粒度 Chunk）。
     */
    private ChunkDTO buildHierarchyChunk(LegalNode node, List<LegalNode> articles, String level) {
        StringBuilder sb = new StringBuilder();

        // 注入层级标题
        String prefix = buildHierarchyPrefix(node);
        if (!prefix.isEmpty()) {
            sb.append("【").append(prefix).append("】\n");
        }
        sb.append(node.getHeading()).append("\n\n");

        // 合并所有条文
        for (LegalNode article : articles) {
            sb.append(article.fullText()).append("\n\n");
        }
        String text = sb.toString().trim();
        if (text.isBlank()) {
            return null;
        }

        LegalNode part = node.getPartAncestor();
        LegalNode chapterNode = "chapter".equals(level) ? node : node.getChapterAncestor();
        LegalNode sectionNode = "section".equals(level) ? node : null;

        return ChunkDTO.builder()
                .textContent(text)
                .chunkLevel(level)
                .partName(part != null ? part.getHeading() : null)
                .chapterName(chapterNode != null ? chapterNode.getHeading() : null)
                .sectionName(sectionNode != null ? sectionNode.getHeading() : null)
                .articleId(null)  // 粗粒度不关联单一条文号
                .tokenCount(termScorer.estimateTokenCount(text))
                .legalTermDensity(termScorer.computeLegalTermDensity(text))
                .articleIdDensity(termScorer.computeArticleIdDensity(text))
                .build();
    }
}