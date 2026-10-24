package com.yizhaoqi.rag.chunker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 保底分块器：按句子/换行切段，再把相邻段打包到 token 上限，并在相邻 chunk 之间保留重叠。
 *
 * <p>该分块器不依赖任何法律结构，可用于两种场景：</p>
 * <ul>
 *   <li>私人案件文书结构化解析失败时的兜底；</li>
 *   <li>法典文本结构解析失败时的兜底。</li>
 * </ul>
 *
 * <p>保底策略保证：只要输入文本非空，就一定至少产出一个 Chunk，不会出现“解析成功但零分块”导致文档不可检索。</p>
 */
@Component
public class OverlapTextChunker {

    private static final int MIN_PROGRESS_CHARS = 1;

    private final LegalTermScorer termScorer;
    private final int maxTokens;
    private final int overlapTokens;

    public OverlapTextChunker(
            @Value("${file.parsing.private-chunk-size:512}") int maxTokens,
            @Value("${file.parsing.private-overlap-size:100}") int overlapTokens) {
        this.termScorer = new LegalTermScorer();
        this.maxTokens = Math.max(64, maxTokens);
        this.overlapTokens = Math.max(0, Math.min(overlapTokens, this.maxTokens / 2));
    }

    /**
     * 使用默认的保底元数据切块。
     */
    public List<ChunkDTO> chunk(String text, DocumentType documentType) {
        boolean privateDoc = documentType != null && documentType.isPrivateDocument();
        String chunkLevel = privateDoc ? "case-fallback" : "legal-fallback";
        ChunkStrategy strategy = privateDoc ? ChunkStrategy.CHAR_OVERLAP : ChunkStrategy.LEGAL_FALLBACK;
        return chunk(text, documentType, chunkLevel, strategy, null, null, privateDoc ? "FALLBACK" : null);
    }

    /**
     * 按指定元数据切块。
     *
     * @param text          原始文本
     * @param documentType  文档类型
     * @param chunkLevel    分块级别，例如 case-section / case-fallback / legal-fallback
     * @param strategy      分块策略
     * @param partName      编/案件标题
     * @param chapterName   章/案件段落标题
     * @param caseSection   案件段落类型
     */
    public List<ChunkDTO> chunk(String text,
                                DocumentType documentType,
                                String chunkLevel,
                                ChunkStrategy strategy,
                                String partName,
                                String chapterName,
                                String caseSection) {
        List<ChunkDTO> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }

        List<String> segments = splitIntoSegments(text);
        List<String> safeSegments = new ArrayList<>();
        for (String segment : segments) {
            if (segment == null || segment.isBlank()) {
                continue;
            }
            if (termScorer.estimateTokenCount(segment) > maxTokens) {
                safeSegments.addAll(hardSplit(segment));
            } else {
                safeSegments.add(segment);
            }
        }
        if (safeSegments.isEmpty()) {
            safeSegments.add(text.trim());
        }

        int start = 0;
        int position = 0;
        while (start < safeSegments.size()) {
            StringBuilder content = new StringBuilder();
            int end = start;
            while (end < safeSegments.size()) {
                String candidate = content.isEmpty()
                        ? safeSegments.get(end)
                        : content + " " + safeSegments.get(end);
                if (!content.isEmpty() && termScorer.estimateTokenCount(candidate) > maxTokens) {
                    break;
                }
                content.setLength(0);
                content.append(candidate);
                end++;
            }
            if (content.isEmpty()) {
                content.append(safeSegments.get(start));
                end = start + 1;
            }

            String chunkText = content.toString().trim();
            if (!chunkText.isEmpty()) {
                result.add(ChunkDTO.builder()
                        .textContent(chunkText)
                        .chunkLevel(chunkLevel)
                        .partName(partName)
                        .chapterName(chapterName)
                        .caseSection(caseSection)
                        .chunkStrategy(strategy == null ? ChunkStrategy.CHAR_OVERLAP.name() : strategy.name())
                        .documentType(documentType == null ? DocumentType.STRUCTURED_LAW.name() : documentType.name())
                        .chunkRole(ChunkRole.CHILD.name())
                        .contentType(detectContentType(chunkText))
                        .parentBlockId(buildParentId(chapterName, caseSection))
                        .chunkPosition(position++)
                        .tokenCount(termScorer.estimateTokenCount(chunkText))
                        .legalTermDensity(termScorer.computeLegalTermDensity(chunkText))
                        .articleIdDensity(termScorer.computeArticleIdDensity(chunkText))
                        .build());
            }

            if (end >= safeSegments.size()) {
                break;
            }
            int nextStart = end;
            int overlap = 0;
            while (nextStart > start) {
                int previousTokens = termScorer.estimateTokenCount(safeSegments.get(nextStart - 1));
                if (overlap + previousTokens > overlapTokens) {
                    break;
                }
                overlap += previousTokens;
                nextStart--;
            }
            start = nextStart > start ? nextStart : Math.min(start + MIN_PROGRESS_CHARS, end);
        }
        return result;
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

    private String buildParentId(String chapterName, String caseSection) {
        if (chapterName != null && !chapterName.isBlank()) {
            return "section-" + Integer.toHexString(chapterName.hashCode());
        }
        if (caseSection != null && !caseSection.isBlank()) {
            return "section-" + caseSection.toLowerCase();
        }
        return null;
    }

    /**
     * 把文本切成句子/行片段，并保留标点。
     */
    private List<String> splitIntoSegments(String text) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (ch == '\r') {
                continue;
            }
            if (ch == '\n') {
                addSegment(segments, current);
                continue;
            }
            current.append(ch);
            if (isSentenceEnd(ch)) {
                addSegment(segments, current);
            }
        }
        addSegment(segments, current);
        return segments;
    }

    private void addSegment(List<String> segments, StringBuilder current) {
        String value = current.toString().trim();
        if (!value.isEmpty()) {
            segments.add(value);
        }
        current.setLength(0);
    }

    private boolean isSentenceEnd(char ch) {
        return ch == '。' || ch == '！' || ch == '？'
                || ch == '!' || ch == '?' || ch == '；' || ch == ';';
    }

    /**
     * 单句超长时按 token 上限硬切，避免一个句子永远无法装入 chunk。
     */
    private List<String> hardSplit(String segment) {
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < segment.length()) {
            int end = Math.min(segment.length(), start + maxTokens);
            // 不要切断 surrogate pair
            if (end < segment.length() && Character.isHighSurrogate(segment.charAt(end - 1))) {
                end++;
            }
            while (end > start + 1 && termScorer.estimateTokenCount(segment.substring(start, end)) > maxTokens) {
                end--;
            }
            String part = segment.substring(start, end).trim();
            if (!part.isEmpty()) {
                result.add(part);
            }
            start = Math.max(end, start + 1);
        }
        return result;
    }
}
