package com.yizhaoqi.rag.parser.strategy;

import com.yizhaoqi.rag.chunker.ChunkDTO;
import com.yizhaoqi.rag.chunker.ChunkRole;
import com.yizhaoqi.rag.chunker.ChunkStrategy;
import com.yizhaoqi.rag.chunker.ContentType;
import com.yizhaoqi.rag.chunker.DocumentType;
import com.yizhaoqi.rag.chunker.LegalTermScorer;
import com.yizhaoqi.rag.chunker.OverlapTextChunker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 私人案件文档解析策略。
 *
 * <p>用户上传的案件报告、起诉状、答辩状、判决书等通常具有“当事人信息 / 诉讼请求 / 事实与理由 /
 * 证据 / 本院认为 / 裁判结果”等段落结构。该策略按案件文书结构识别段落标题，并将每个段落
 * 打包为带重叠的 Chunk；如果文书中完全没有可识别结构，则返回空列表，由
 * {@code ParseService} 使用 {@link OverlapTextChunker} 做字符重叠保底。</p>
 */
@Component
public class PrivateCaseParsingStrategy implements DocumentParsingStrategy {

    private static final Logger logger = LoggerFactory.getLogger(PrivateCaseParsingStrategy.class);

    private static final int MAX_HEADING_LENGTH = 60;

    private static final Pattern CHINESE_NUMBER_HEADING = Pattern.compile(
            "^[一二三四五六七八九十百]+[、.．]\\s*(.{0,50})$");
    private static final Pattern PARENTHESIZED_HEADING = Pattern.compile(
            "^[（(][一二三四五六七八九十百]+[）)]\\s*(.{0,50})$");
    private static final Pattern PART_HEADING = Pattern.compile(
            "^第[一二三四五六七八九十百]+[部分章节]\\s*(.{0,50})$");
    private static final Pattern NUMBERED_HEADING = Pattern.compile(
            "^\\d+(?:\\.\\d+){0,2}[、.．)]\\s*(.{1,50})$");
    private static final Pattern BRACKET_HEADING = Pattern.compile(
            "^【([^】]{1,30})】\\s*$");
    private static final Pattern KEYWORD_HEADING = Pattern.compile(
            "^(案件基本信息|案件概况|当事人信息|当事人|诉讼请求|上诉请求|请求事项|答辩意见|答辩状|"
                    + "代理意见|辩护意见|事实与理由|事实认定|案件事实|基本案情|案情简介|审理经过|"
                    + "证据材料|证据清单|证据目录|证据|质证意见|争议焦点|本院认为|法院认为|裁判理由|"
                    + "裁判结果|判决结果|判决如下|裁定如下|执行情况|法律依据|适用法律|法律分析|"
                    + "风险提示|风险分析|结论|建议|案件分析|分析意见|"
                    // 刑事案件判决书/审查报告常见要素
                    + "公诉机关指控|公诉机关|起诉书|指控|被告人辩称|被告人辩解|被告人信息|被告人|"
                    + "辩护人意见|辩护人|案件由来|经审理查明|审理查明|本院查明|量刑建议|"
                    + "附带民事诉讼|审判人员|书记员|合议庭|上诉|抗诉|原判|二审|再审)[:：]?\\s*$");

    private static final List<String> SECTION_KEYWORDS = List.of(
            "当事人", "诉讼请求", "上诉请求", "答辩", "事实与理由", "事实认定", "案情",
            "证据", "质证", "争议焦点", "本院认为", "法院认为", "裁判", "判决", "裁定",
            "法律依据", "适用法律", "法律分析", "风险", "结论", "建议", "代理意见", "辩护意见",
            "审理经过", "执行", "案件基本", "案件概况", "分析意见",
            // 刑事案件要素
            "公诉机关", "指控", "起诉书", "被告人", "辩护人", "案件由来", "查明",
            "量刑建议", "附带民事", "审判人员", "书记员", "合议庭", "抗诉", "原判"
    );

    private final OverlapTextChunker overlapTextChunker;
    private final LegalTermScorer termScorer = new LegalTermScorer();

    public PrivateCaseParsingStrategy(OverlapTextChunker overlapTextChunker) {
        this.overlapTextChunker = overlapTextChunker;
    }

    @Override
    public DocumentType documentType() {
        return DocumentType.PRIVATE_LEGAL;
    }

    @Override
    public List<ChunkDTO> parse(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        ParsedCaseDocument document = parseStructure(text);
        if (document.sections().isEmpty() || !document.hasHeading()) {
            logger.info("私人案件文档未识别出段落结构，改用字符重叠保底策略");
            return List.of();
        }

        List<ChunkDTO> chunks = new ArrayList<>();
        int sectionIndex = 0;
        for (CaseSection section : document.sections()) {
            if (section.content() == null || section.content().isBlank()) {
                continue;
            }
            String body = section.heading() + "\n" + section.content().trim();
            // 父块 ID 在一次解析内唯一即可；reindex 会删除旧分块并重建
            String parentKey = "case-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                    + "-" + sectionIndex;
            String contentType = detectContentType(body);

            // 父段：只存 MySQL，用于命中子块后回补上下文，不参与向量检索
            chunks.add(buildParentChunk(document.title(), section, body, parentKey, contentType));

            List<ChunkDTO> children = overlapTextChunker.chunk(
                    body,
                    DocumentType.PRIVATE_LEGAL,
                    "case-child",
                    ChunkStrategy.CASE_ELEMENT,
                    document.title(),
                    section.heading(),
                    section.type()
            );
            int childIndex = 0;
            for (ChunkDTO child : children) {
                child.setParentBlockId(parentKey);
                child.setBlockId(parentKey + "-c" + childIndex++);
                child.setChunkRole(ChunkRole.CHILD.name());
                child.setContentType(child.getContentType() == null ? contentType : child.getContentType());
                chunks.add(child);
            }
            sectionIndex++;
        }

        if (chunks.isEmpty()) {
            logger.info("私人案件文档段落结构解析未产生分块，改用字符重叠保底策略");
            return List.of();
        }
        logger.info("私人案件文档要素分层分块完成，title={}, sectionCount={}, chunkCount={}, parentCount={}",
                document.title(), document.sections().size(), chunks.size(),
                chunks.stream().filter(chunk -> ChunkRole.PARENT.name().equals(chunk.getChunkRole())).count());
        return chunks;
    }

    private ChunkDTO buildParentChunk(String documentTitle, CaseSection section, String body,
            String parentKey, String contentType) {
        return ChunkDTO.builder()
                .textContent(body)
                .chunkLevel("case-parent")
                .chunkRole(ChunkRole.PARENT.name())
                .blockId(parentKey)
                .contentType(contentType)
                .partName(documentTitle)
                .chapterName(section.heading())
                .caseSection(section.type())
                .chunkStrategy(ChunkStrategy.CASE_ELEMENT.name())
                .documentType(DocumentType.PRIVATE_LEGAL.name())
                .chunkPosition(0)
                .tokenCount(termScorer.estimateTokenCount(body))
                .legalTermDensity(termScorer.computeLegalTermDensity(body))
                .articleIdDensity(termScorer.computeArticleIdDensity(body))
                .build();
    }

    private String detectContentType(String text) {
        if (text.contains("[TABLE]")) {
            return ContentType.TABLE.name();
        }
        if (text.contains("[图片]") || text.contains("[FIGURE]")) {
            return ContentType.FIGURE.name();
        }
        return ContentType.TEXT.name();
    }

    /**
     * 保底分块：供 ParseService 在结构解析失败时直接调用。
     */
    public List<ChunkDTO> fallback(String text, String title) {
        return overlapTextChunker.chunk(
                text,
                DocumentType.PRIVATE_LEGAL,
                "case-fallback",
                ChunkStrategy.CHAR_OVERLAP,
                title,
                null,
                "FALLBACK"
        );
    }

    private ParsedCaseDocument parseStructure(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);

        String title = detectTitle(lines);
        List<CaseSection> sections = new ArrayList<>();
        StringBuilder overview = new StringBuilder();

        StringBuilder currentContent = null;
        String currentHeading = null;
        String currentType = null;
        boolean hasHeading = false;

        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.strip();
            if (line.isEmpty()) {
                appendLineBreakIfNeeded(currentContent, overview);
                continue;
            }

            String heading = detectHeading(line);
            if (heading != null) {
                hasHeading = true;
                if (currentContent != null) {
                    sections.add(new CaseSection(currentHeading, currentType, currentContent.toString().trim()));
                }
                currentHeading = heading;
                currentType = classifySection(heading);
                currentContent = new StringBuilder();
                continue;
            }

            if (currentContent == null) {
                overview.append(line).append('\n');
            } else {
                currentContent.append(line).append('\n');
            }
        }

        if (currentContent != null) {
            sections.add(new CaseSection(currentHeading, currentType, currentContent.toString().trim()));
        }

        if (!overview.isEmpty()) {
            sections.add(0, new CaseSection("案件概况", "OVERVIEW", overview.toString().trim()));
        }

        sections.removeIf(section -> section.content() == null || section.content().isBlank());
        return new ParsedCaseDocument(title, sections, hasHeading);
    }

    private void appendLineBreakIfNeeded(StringBuilder currentContent, StringBuilder overview) {
        StringBuilder target = currentContent != null ? currentContent : overview;
        if (target.length() > 0 && target.charAt(target.length() - 1) != '\n') {
            target.append('\n');
        }
    }

    private String detectTitle(String[] lines) {
        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.strip();
            if (line.isEmpty() || detectHeading(line) != null) {
                continue;
            }
            String cleaned = line.replaceAll("\\s+", " ").trim();
            if (cleaned.length() > 80) {
                return cleaned.substring(0, 80);
            }
            return cleaned;
        }
        return "私人案件文档";
    }

    /**
     * 识别段落标题。注意“原告：张三”这类键值行不是段落标题，而是段落正文。
     */
    private String detectHeading(String line) {
        if (line == null || line.isBlank() || line.length() > MAX_HEADING_LENGTH) {
            return null;
        }
        String normalized = line.strip();

        Matcher keywordMatcher = KEYWORD_HEADING.matcher(normalized);
        if (keywordMatcher.matches()) {
            return normalized;
        }

        Matcher chineseMatcher = CHINESE_NUMBER_HEADING.matcher(normalized);
        if (chineseMatcher.matches()) {
            String rest = chineseMatcher.group(1).trim();
            if (rest.isEmpty() || looksLikeHeading(rest)) {
                return normalized;
            }
            return null;
        }

        Matcher parenMatcher = PARENTHESIZED_HEADING.matcher(normalized);
        if (parenMatcher.matches()) {
            String rest = parenMatcher.group(1).trim();
            if (rest.isEmpty() || looksLikeHeading(rest)) {
                return normalized;
            }
            return null;
        }

        Matcher partMatcher = PART_HEADING.matcher(normalized);
        if (partMatcher.matches()) {
            return normalized;
        }

        Matcher bracketMatcher = BRACKET_HEADING.matcher(normalized);
        if (bracketMatcher.matches()) {
            return bracketMatcher.group(1).trim();
        }

        Matcher numberedMatcher = NUMBERED_HEADING.matcher(normalized);
        if (numberedMatcher.matches()) {
            String rest = numberedMatcher.group(1).trim();
            if (looksLikeHeading(rest)) {
                return normalized;
            }
        }
        return null;
    }

    private boolean looksLikeHeading(String value) {
        if (value.length() > 30) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (value.contains("。") || value.contains("！") || value.contains("？")) {
            return false;
        }
        return SECTION_KEYWORDS.stream().anyMatch(lower::contains);
    }

    private String classifySection(String heading) {
        String normalized = heading == null ? "" : heading;
        if (containsAny(normalized, "公诉机关指控", "公诉机关", "起诉书", "指控", "量刑建议")) {
            return "PROSECUTION";
        }
        if (containsAny(normalized, "被告人辩称", "被告人辩解", "辩护人意见", "辩护意见", "答辩", "代理意见")) {
            return "DEFENSE";
        }
        if (containsAny(normalized, "经审理查明", "审理查明", "本院查明",
                "事实与理由", "事实认定", "案件事实", "基本案情", "案情")) {
            return "FACTS";
        }
        if (containsAny(normalized, "审理经过", "案件由来")) {
            return "PROCEDURE";
        }
        if (containsAny(normalized, "诉讼请求", "上诉请求", "请求事项")) {
            return "CLAIMS";
        }
        if (containsAny(normalized, "证据", "质证")) {
            return "EVIDENCE";
        }
        if (containsAny(normalized, "本院认为", "法院认为", "裁判理由", "争议焦点")) {
            return "COURT_OPINION";
        }
        if (containsAny(normalized, "裁判结果", "判决结果", "判决如下", "裁定如下", "执行情况")) {
            return "JUDGMENT";
        }
        if (containsAny(normalized, "法律依据", "适用法律")) {
            return "LEGAL_BASIS";
        }
        if (containsAny(normalized, "被告人", "当事人", "辩护人")) {
            return "PARTIES";
        }
        if (containsAny(normalized, "风险", "结论", "建议", "案件分析", "分析意见", "法律分析")) {
            return "ANALYSIS";
        }
        return "OTHER";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private record CaseSection(String heading, String type, String content) {
    }

    private record ParsedCaseDocument(String title, List<CaseSection> sections, boolean hasHeading) {
    }
}
