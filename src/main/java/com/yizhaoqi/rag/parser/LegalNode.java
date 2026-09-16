package com.yizhaoqi.rag.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * 法律文本树状结构节点。
 *
 * 类型层级：ROOT → PART(编) → CHAPTER(章) → SECTION(节) → ARTICLE(条) → PARAGRAPH(款)
 */
public class LegalNode {

    public enum NodeType {
        ROOT, PART, CHAPTER, SECTION, ARTICLE, PARAGRAPH
    }

    private final NodeType type;
    private final String heading;   // 标题行，如 "第一编 总则"、"第123条"
    private final String articleId; // 条文号，如 "第123条"，仅 ARTICLE 节点有值
    private final StringBuilder content = new StringBuilder();
    private final List<LegalNode> children = new ArrayList<>();
    private LegalNode parent;

    public LegalNode(NodeType type, String heading, String articleId) {
        this.type = type;
        this.heading = heading != null ? heading : "";
        this.articleId = articleId;
    }

    public void addChild(LegalNode child) {
        child.parent = this;
        children.add(child);
    }

    public void appendContent(String line) {
        if (content.length() > 0) {
            content.append('\n');
        }
        content.append(line);
    }

    /**
     * 获取完整文本内容 = 标题 + 追加正文。
     */
    public String fullText() {
        StringBuilder sb = new StringBuilder();
        if (!heading.isEmpty()) {
            sb.append(heading);
            if (content.length() > 0) {
                sb.append('\n');
            }
        }
        sb.append(content);
        return sb.toString();
    }

    /**
     * 递归收集所有 ARTICLE 类型的后代节点。
     */
    public List<LegalNode> collectArticles() {
        List<LegalNode> articles = new ArrayList<>();
        collectByType(this, NodeType.ARTICLE, articles);
        return articles;
    }

    /**
     * 递归收集所有 SECTION 类型的后代节点。
     */
    public List<LegalNode> collectSections() {
        List<LegalNode> sections = new ArrayList<>();
        collectByType(this, NodeType.SECTION, sections);
        return sections;
    }

    /**
     * 递归收集所有 CHAPTER 类型的后代节点。
     */
    public List<LegalNode> collectChapters() {
        List<LegalNode> chapters = new ArrayList<>();
        collectByType(this, NodeType.CHAPTER, chapters);
        return chapters;
    }

    /**
     * 递归收集所有 PART 类型的后代节点。
     */
    public List<LegalNode> collectParts() {
        List<LegalNode> parts = new ArrayList<>();
        collectByType(this, NodeType.PART, parts);
        return parts;
    }

    private void collectByType(LegalNode node, NodeType targetType, List<LegalNode> result) {
        if (node.type == targetType) {
            result.add(node);
        }
        for (LegalNode child : node.children) {
            collectByType(child, targetType, result);
        }
    }

    /**
     * 获取从根到此节点的层级路径中各节点的 heading。
     * 如 ["第一编 总则", "第五章 民事权利", "第123条"]
     */
    public List<String> getPathHeadings() {
        List<String> path = new ArrayList<>();
        LegalNode current = this;
        while (current != null && current.type != NodeType.ROOT) {
            if (!current.heading.isEmpty()) {
                path.add(0, current.heading);
            }
            current = current.parent;
        }
        return path;
    }

    /**
     * 获取当前节点所属的 PART 节点。
     */
    public LegalNode getPartAncestor() {
        return getAncestorOfType(NodeType.PART);
    }

    /**
     * 获取当前节点所属的 CHAPTER 节点。
     */
    public LegalNode getChapterAncestor() {
        return getAncestorOfType(NodeType.CHAPTER);
    }

    /**
     * 获取当前节点所属的 SECTION 节点。
     */
    public LegalNode getSectionAncestor() {
        return getAncestorOfType(NodeType.SECTION);
    }

    private LegalNode getAncestorOfType(NodeType targetType) {
        LegalNode current = this;
        while (current != null) {
            if (current.type == targetType) {
                return current;
            }
            current = current.parent;
        }
        return null;
    }

    // ===== Getters =====

    public NodeType getType() {
        return type;
    }

    public String getHeading() {
        return heading;
    }

    public String getArticleId() {
        return articleId;
    }

    public String getContent() {
        return content.toString();
    }

    public List<LegalNode> getChildren() {
        return children;
    }

    public LegalNode getParent() {
        return parent;
    }
}