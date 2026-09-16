package com.yizhaoqi.rag.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

import com.yizhaoqi.rag.parser.LegalNode.NodeType;

/**
 * 法律文本结构解析器 —— 识别"编→章→节→条→款"树状层级结构。
 * * 使用基于 NodeType.ordinal() 的动态层级回溯算法，
 * 完美解决法律文本中存在的“跳级”现象（如：某章没有节，直接下挂条文）。
 */
public class LegalStructureParser {

    private static final Logger logger = LoggerFactory.getLogger(LegalStructureParser.class);

    // 正则模式
    private static final Pattern PART_PATTERN = Pattern.compile(
            "^\\s*第[一二三四五六七八九十]+编\\s*(.+)?|^\\s*(总则|分则)\\s*$");
    private static final Pattern CHAPTER_PATTERN = Pattern.compile(
            "^\\s*第[一二三四五六七八九十百]+章\\s*(.+)?");
    private static final Pattern SECTION_PATTERN = Pattern.compile(
            "^\\s*第[一二三四五六七八九十]+节\\s*(.+)?");
    private static final Pattern ARTICLE_PATTERN = Pattern.compile(
            "^\\s*第[0-9零一二三四五六七八九十百千]+条\\s*(.+)?");
    private static final Pattern PARAGRAPH_PATTERN = Pattern.compile(
            "^（[一二三四五六七八九十]+）|^\\(\\d+\\)");

    /**
     * 解析法律文本为树状结构。
     *
     * @param text 法律全文
     * @return 根节点（RootNode），其 children 即为第一层级节点
     */
    public LegalNode parse(String text) {
        if (text == null || text.isBlank()) {
            return new LegalNode(NodeType.ROOT, "", null);
        }

        Deque<LegalNode> stack = new ArrayDeque<>();
        // Root 的 ordinal 为 0，永远不会被弹出
        LegalNode root = new LegalNode(NodeType.ROOT, "", null);
        stack.push(root);

        int lineNumber = 0;
        for (String rawLine : text.split("\\R")) {
            lineNumber++;
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }

            try {
                if (PART_PATTERN.matcher(line).find()) {
                    processNode(stack, new LegalNode(NodeType.PART, line, null));
                } else if (CHAPTER_PATTERN.matcher(line).find()) {
                    processNode(stack, new LegalNode(NodeType.CHAPTER, line, null));
                } else if (SECTION_PATTERN.matcher(line).find()) {
                    processNode(stack, new LegalNode(NodeType.SECTION, line, null));
                } else if (ARTICLE_PATTERN.matcher(line).find()) {
                    String articleId = extractArticleId(line);
                    processNode(stack, new LegalNode(NodeType.ARTICLE, line, articleId));
                } else if (PARAGRAPH_PATTERN.matcher(line).find() && stack.peek().getType() == NodeType.ARTICLE) {
                    // 款必须严格属于条
                    processNode(stack, new LegalNode(NodeType.PARAGRAPH, line, null));
                } else {
                    // 不匹配任何层级，追加为当前节点的正文内容
                    if (!stack.isEmpty()) {
                        stack.peek().appendContent(line);
                    }
                }
            } catch (Exception e) {
                logger.debug("解析第 {} 行时出现异常: {}", lineNumber, e.getMessage());
                if (!stack.isEmpty()) {
                    stack.peek().appendContent(line);
                }
            }
        }

        logger.info("法律结构解析完成，共解析 {} 行文本", lineNumber);
        return root;
    }

    /**
     * 核心状态机回溯逻辑：基于 NodeType 的枚举自然顺序进行回溯
     * ROOT(0) < PART(1) < CHAPTER(2) < SECTION(3) < ARTICLE(4) < PARAGRAPH(5)
     */
    private void processNode(Deque<LegalNode> stack, LegalNode newNode) {
        // 如果栈顶节点的级别 ≥ 新节点的级别（数字越大级别越低），说明栈顶节点装不下新节点，必须弹出
        // 例如：当前栈顶是 ARTICLE(4)，来了个新 CHAPTER(2)，4 >= 2，弹出 ARTICLE
        // 直到遇到 PART(1)，1 < 2 不成立，循环停止，CHAPTER 正确地挂在 PART 下面。
        while (stack.peek().getType().ordinal() >= newNode.getType().ordinal()) {
            stack.pop();
        }
        
        // 将新节点挂在合适的父节点下
        stack.peek().addChild(newNode);
        // 新节点入栈，成为新的当前上下文
        stack.push(newNode);
    }

    /**
     * 从条文行提取条文号，如 "第一百二十三条"。
     */
    private String extractArticleId(String line) {
        java.util.regex.Matcher m = Pattern.compile("第[0-9零一二三四五六七八九十百千]+条").matcher(line);
        if (m.find()) {
            return m.group();
        }
        return null;
    }
}