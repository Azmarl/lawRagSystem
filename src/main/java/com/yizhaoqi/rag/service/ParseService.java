package com.yizhaoqi.rag.service;

import com.yizhaoqi.rag.chunker.ChunkDTO;
import com.yizhaoqi.rag.chunker.ChunkRole;
import com.yizhaoqi.rag.chunker.ChunkStrategy;
import com.yizhaoqi.rag.chunker.ContentType;
import com.yizhaoqi.rag.chunker.DocumentType;
import com.yizhaoqi.rag.chunker.OverlapTextChunker;
import com.yizhaoqi.rag.model.DocumentVector;
import com.yizhaoqi.rag.model.PrivateDocumentVector;
import com.yizhaoqi.rag.model.PublicDocumentVector;
import com.yizhaoqi.rag.parser.strategy.DocumentParsingStrategyFactory;
import com.yizhaoqi.rag.repository.DocumentVectorRepository;
import com.yizhaoqi.rag.repository.PrivateDocumentVectorRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ParseService {

    private static final Logger logger = LoggerFactory.getLogger(ParseService.class);
    private static final int PDF_BOUNDARY_SCAN_LINES = 3;
    private static final int PDF_BOILERPLATE_MIN_LENGTH = 4;
    private static final int PDF_BOILERPLATE_MAX_LENGTH = 120;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired
    private PrivateDocumentVectorRepository privateDocumentVectorRepository;

    @Autowired
    private UsageQuotaService usageQuotaService;

    @Autowired
    private DocumentParsingStrategyFactory parsingStrategyFactory;

    @Autowired
    private OverlapTextChunker overlapTextChunker;

    @Value("${file.parsing.chunk-size:512}")
    private int chunkSize;

    @Value("${file.parsing.overlap-size:100}")
    private int overlapSize = 100;

    @Value("${file.parsing.min-chunk-size:100}")
    private int minChunkSize = 100;

    @Value("${file.parsing.parent-chunk-size:1048576}")
    private int parentChunkSize;

    @Value("${file.parsing.buffer-size:8192}")
    private int bufferSize;

    @Value("${file.parsing.max-memory-threshold:0.8}")
    private double maxMemoryThreshold;

    public ParseService() {
    }

    /**
     * 兼容旧调用：未指定文档类型时按“法律文档”处理。
     */
    public void parseAndSave(String fileMd5, InputStream fileStream,
            String userId, String orgTag, boolean isPublic) throws IOException, TikaException {
        parseAndSave(fileMd5, fileStream, userId, orgTag, isPublic, null);
    }

    /**
     * 解析并保存文件。
     *
     * @param documentType 用户上传时选择的文档类型；为 null 时兼容旧数据，自动识别法典结构
     */
    public void parseAndSave(String fileMd5, InputStream fileStream, String userId,
            String orgTag, boolean isPublic, DocumentType documentType) throws IOException, TikaException {
        logger.info("开始流式解析文件，fileMd5: {}, userId: {}, orgTag: {}, isPublic: {}, requestedType: {}",
                fileMd5, userId, orgTag, isPublic, documentType);

        checkMemoryThreshold();

        try (BufferedInputStream bufferedStream = new BufferedInputStream(fileStream, bufferSize)) {
            String fullText = extractFullText(bufferedStream);
            if (fullText.isBlank()) {
                logger.warn("提取到空文本，跳过解析，fileMd5: {}", fileMd5);
                return;
            }

            DocumentType effectiveType = documentType == null ? detectDocumentType(fullText) : documentType;
            List<ChunkDTO> chunks = buildChunks(fullText, effectiveType);
            if (chunks.isEmpty()) {
                logger.warn("未生成任何分块，fileMd5: {}, documentType: {}", fileMd5, effectiveType);
                return;
            }
            saveChunks(fileMd5, chunks, effectiveType, userId, orgTag, isPublic);
            logger.info("文件解析和入库完成，fileMd5: {}, documentType: {}, chunkCount: {}",
                    fileMd5, effectiveType, chunks.size());
        } catch (SAXException e) {
            logger.error("文档解析失败，fileMd5: {}", fileMd5, e);
            throw new RuntimeException("文档解析失败", e);
        }
    }

    /**
     * 兼容旧版本的解析方法。
     */
    public void parseAndSave(String fileMd5, InputStream fileStream) throws IOException, TikaException {
        parseAndSave(fileMd5, fileStream, "unknown", "DEFAULT", false, null);
    }

    public EmbeddingEstimate estimateEmbeddingUsage(InputStream fileStream) throws IOException, TikaException {
        return estimateEmbeddingUsage(fileStream, null);
    }

    public EmbeddingEstimate estimateEmbeddingUsage(InputStream fileStream, DocumentType documentType)
            throws IOException, TikaException {
        logger.info("开始估算文档 Embedding Token，requestedType: {}", documentType);
        checkMemoryThreshold();

        try (BufferedInputStream bufferedStream = new BufferedInputStream(fileStream, bufferSize)) {
            String fullText = extractFullText(bufferedStream);
            if (fullText.isBlank()) {
                return new EmbeddingEstimate(0, 0);
            }
            DocumentType effectiveType = documentType == null ? detectDocumentType(fullText) : documentType;
            List<ChunkDTO> chunks = buildChunks(fullText, effectiveType);
            long tokens = usageQuotaService.estimateEmbeddingTokens(
                    chunks.stream().map(ChunkDTO::getTextContent).toList());
            return new EmbeddingEstimate(tokens, chunks.size());
        } catch (SAXException e) {
            logger.error("文档 Embedding Token 估算失败", e);
            throw new RuntimeException("文档 Embedding Token 估算失败", e);
        }
    }

    // ========================
    // 文本提取
    // ========================

    private String extractFullText(BufferedInputStream stream) throws IOException, TikaException, SAXException {
        if (isPdfDocument(stream)) {
            return extractPdfText(stream);
        }
        StructuredTextExtractionHandler handler = new StructuredTextExtractionHandler();
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();
        AutoDetectParser parser = new AutoDetectParser();
        parser.parse(stream, handler, metadata, context);
        return handler.getFullText();
    }

    private String extractPdfText(BufferedInputStream stream) throws IOException {
        try (PDDocument document = PDDocument.load(stream)) {
            List<String> cleanedPageTexts = extractCleanPdfPageTexts(document);
            return String.join("\n", cleanedPageTexts);
        }
    }

    // ========================
    // 策略分块
    // ========================

    private DocumentType detectDocumentType(String text) {
        return isStructuredLegalText(text) ? DocumentType.STRUCTURED_LAW : DocumentType.PRIVATE_LEGAL;
    }

    private List<ChunkDTO> buildChunks(String fullText, DocumentType documentType) {
        DocumentType resolved = documentType == null ? DocumentType.STRUCTURED_LAW : documentType;
        List<ChunkDTO> chunks = new ArrayList<>(parsingStrategyFactory.get(resolved).parse(fullText));
        if (!chunks.isEmpty()) {
            return chunks;
        }

        // 结构化解析失败时的统一保底：按句子/字符重叠分块，保证文档仍可被检索
        String reason = resolved.isPrivateDocument() ? "私人案件结构解析失败" : "法典结构解析失败";
        logger.info("{}，使用字符重叠保底分块，documentType: {}", reason, resolved);
        return overlapTextChunker.chunk(fullText, resolved);
    }

    // ========================
    // 分块持久化
    // ========================

    private void saveChunks(String fileMd5, List<ChunkDTO> chunks, DocumentType documentType,
            String userId, String orgTag, boolean isPublic) {
        int currentChunkId = 0;
        boolean privateDocument = documentType.isPrivateDocument();
        for (ChunkDTO chunk : chunks) {
            if (chunk.getTextContent() == null || chunk.getTextContent().isBlank()) {
                continue;
            }
            currentChunkId++;

            DocumentVector vector = privateDocument ? new PrivateDocumentVector() : new PublicDocumentVector();
            vector.setFileMd5(fileMd5);
            vector.setChunkId(currentChunkId);
            vector.setTextContent(chunk.getTextContent());
            vector.setPageNumber(null);
            vector.setAnchorText(buildAnchorText(chunk.getTextContent()));
            vector.setUserId(userId);
            vector.setOrgTag(orgTag);
            vector.setPublic(!privateDocument && isPublic);

            vector.setChunkLevel(chunk.getChunkLevel());
            vector.setPartName(chunk.getPartName());
            vector.setChapterName(chunk.getChapterName());
            vector.setSectionName(chunk.getSectionName());
            vector.setArticleId(chunk.getArticleId());
            vector.setArticleIdDensity(chunk.getArticleIdDensity());
            vector.setLegalTermDensity(chunk.getLegalTermDensity());
            vector.setParentBlockId(chunk.getParentBlockId());
            vector.setChunkPosition(chunk.getChunkPosition());
            vector.setDocumentType(documentType.name());
            vector.setChunkStrategy(chunk.getChunkStrategy() == null
                    ? defaultChunkStrategy(documentType).name()
                    : chunk.getChunkStrategy());
            vector.setCaseSection(chunk.getCaseSection());
            vector.setBlockId(chunk.getBlockId());
            vector.setChunkRole(chunk.getChunkRole() == null
                    ? ChunkRole.CHILD.name() : chunk.getChunkRole());
            vector.setContentType(chunk.getContentType() == null
                    ? ContentType.TEXT.name() : chunk.getContentType());

            if (privateDocument) {
                privateDocumentVectorRepository.save((PrivateDocumentVector) vector);
            } else {
                documentVectorRepository.save((PublicDocumentVector) vector);
            }
        }
        logger.info("成功保存 {} 个分块到 {}，documentType: {}",
                currentChunkId, privateDocument ? "private_document_vectors" : "document_vectors", documentType);
    }

    private ChunkStrategy defaultChunkStrategy(DocumentType documentType) {
        return documentType.isPrivateDocument() ? ChunkStrategy.CASE_STRUCTURE : ChunkStrategy.TREE_AWARE;
    }

    private String buildAnchorText(String chunk) {
        if (chunk == null || chunk.isBlank()) {
            return null;
        }
        String normalized = chunk.replaceAll("\\s+", " ").trim();
        int maxLength = 120;
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "…";
    }

    // ========================
    // PDF 文本清理（保留原有护城河逻辑）
    // ========================

    private List<String> extractCleanPdfPageTexts(PDDocument document) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        List<List<String>> rawPageLines = new ArrayList<>();

        for (int pageNumber = 1; pageNumber <= document.getNumberOfPages(); pageNumber++) {
            stripper.setStartPage(pageNumber);
            stripper.setEndPage(pageNumber);
            String pageText = stripper.getText(document);
            rawPageLines.add(splitPdfLines(pageText));
        }

        Map<String, Integer> topLineCounts = collectBoundaryLineCounts(rawPageLines, true);
        Map<String, Integer> bottomLineCounts = collectBoundaryLineCounts(rawPageLines, false);
        int repeatedThreshold = Math.max(2, Math.min(3, document.getNumberOfPages()));

        List<String> cleanedPages = new ArrayList<>(rawPageLines.size());
        for (int pageIndex = 0; pageIndex < rawPageLines.size(); pageIndex++) {
            List<String> cleanedLines = removePdfBoilerplateLines(
                    rawPageLines.get(pageIndex), topLineCounts, bottomLineCounts, repeatedThreshold);
            cleanedPages.add(String.join("\n", cleanedLines).trim());
        }
        return cleanedPages;
    }

    private List<String> splitPdfLines(String pageText) {
        if (pageText == null || pageText.isBlank()) {
            return new ArrayList<>();
        }
        String[] lines = pageText.split("\\R");
        List<String> result = new ArrayList<>(lines.length);
        for (String line : lines) {
            result.add(line == null ? "" : line.strip());
        }
        return result;
    }

    private Map<String, Integer> collectBoundaryLineCounts(List<List<String>> pageLines, boolean topBoundary) {
        Map<String, Integer> counts = new HashMap<>();
        for (List<String> lines : pageLines) {
            List<String> boundaryLines = topBoundary
                    ? firstMeaningfulLines(lines, PDF_BOUNDARY_SCAN_LINES)
                    : lastMeaningfulLines(lines, PDF_BOUNDARY_SCAN_LINES);
            for (String line : boundaryLines) {
                String key = normalizePdfBoundaryLine(line);
                if (key != null) {
                    counts.merge(key, 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    private List<String> firstMeaningfulLines(List<String> lines, int maxCount) {
        List<String> result = new ArrayList<>(maxCount);
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            result.add(line);
            if (result.size() >= maxCount) {
                break;
            }
        }
        return result;
    }

    private List<String> lastMeaningfulLines(List<String> lines, int maxCount) {
        List<String> result = new ArrayList<>(maxCount);
        for (int index = lines.size() - 1; index >= 0; index--) {
            String line = lines.get(index);
            if (line == null || line.isBlank()) {
                continue;
            }
            result.add(0, line);
            if (result.size() >= maxCount) {
                break;
            }
        }
        return result;
    }

    private List<String> removePdfBoilerplateLines(List<String> lines,
            Map<String, Integer> topLineCounts,
            Map<String, Integer> bottomLineCounts,
            int repeatedThreshold) {
        int start = 0;
        int remainingTopChecks = PDF_BOUNDARY_SCAN_LINES;
        while (start < lines.size() && remainingTopChecks > 0) {
            String line = lines.get(start);
            if (line == null || line.isBlank()) {
                start++;
                continue;
            }
            String key = normalizePdfBoundaryLine(line);
            if (key == null || topLineCounts.getOrDefault(key, 0) < repeatedThreshold) {
                break;
            }
            logger.debug("过滤 PDF 页眉文本: {}", line);
            start++;
            remainingTopChecks--;
        }

        int end = lines.size() - 1;
        int remainingBottomChecks = PDF_BOUNDARY_SCAN_LINES;
        while (end >= start && remainingBottomChecks > 0) {
            String line = lines.get(end);
            if (line == null || line.isBlank()) {
                end--;
                continue;
            }
            String key = normalizePdfBoundaryLine(line);
            if (key == null || bottomLineCounts.getOrDefault(key, 0) < repeatedThreshold) {
                break;
            }
            logger.debug("过滤 PDF 页脚文本: {}", line);
            end--;
            remainingBottomChecks--;
        }

        List<String> cleanedLines = new ArrayList<>();
        for (int index = start; index <= end; index++) {
            cleanedLines.add(lines.get(index));
        }
        return cleanedLines;
    }

    private String normalizePdfBoundaryLine(String line) {
        if (line == null) {
            return null;
        }
        String normalized = Normalizer.normalize(line, Normalizer.Form.NFKC)
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .replaceAll("\\d+", "#")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (normalized.length() < PDF_BOILERPLATE_MIN_LENGTH
                || normalized.length() > PDF_BOILERPLATE_MAX_LENGTH) {
            return null;
        }
        return normalized;
    }

    private boolean isPdfDocument(BufferedInputStream stream) throws IOException {
        stream.mark(bufferSize);
        byte[] header = stream.readNBytes(5);
        stream.reset();
        return header.length == 5 && "%PDF-".equals(new String(header, StandardCharsets.US_ASCII));
    }

    private boolean isStructuredLegalText(String text) {
        int articleCount = 0;
        java.util.regex.Matcher articleMatcher = java.util.regex.Pattern
                .compile("(?m)^\\s*第[0-9零一二三四五六七八九十百千万]+条")
                .matcher(text);
        while (articleMatcher.find()) {
            articleCount++;
        }
        boolean hasHierarchy = java.util.regex.Pattern
                .compile("(?m)^\\s*(第[一二三四五六七八九十百千万]+编|第[一二三四五六七八九十百千万]+章|第[一二三四五六七八九十百千万]+节)")
                .matcher(text)
                .find();
        return hasHierarchy || articleCount >= 3;
    }

    // ========================
    // 旧版解析工具（保留供既有测试/调用方使用）
    // ========================

    private List<String> splitLongSentence(String sentence, int chunkSize) {
        List<String> result = new ArrayList<>();
        if (sentence == null || sentence.isEmpty() || chunkSize <= 0) {
            return result;
        }
        int start = 0;
        while (start < sentence.length()) {
            int end = Math.min(sentence.length(), start + chunkSize);
            if (end < sentence.length() && Character.isHighSurrogate(sentence.charAt(end - 1))) {
                end++;
            }
            result.add(sentence.substring(start, end));
            start = end;
        }
        return result;
    }

    private List<String> splitTextIntoChunksWithSemantics(String text, int chunkSize) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank() || chunkSize <= 0) {
            return result;
        }

        List<String> chunks = new ArrayList<>();
        for (String rawParagraph : text.split("\\n\\n+")) {
            String paragraph = rawParagraph.strip();
            if (paragraph.isEmpty()) {
                continue;
            }
            if (paragraph.length() <= chunkSize) {
                chunks.add(paragraph);
                continue;
            }
            List<String> sentences = new ArrayList<>();
            for (String sentence : paragraph.split("(?<=[。！？!?；;])")) {
                if (!sentence.isBlank()) {
                    sentences.add(sentence.strip());
                }
            }
            if (sentences.isEmpty()) {
                chunks.addAll(splitLongSentence(paragraph, chunkSize));
                continue;
            }

            StringBuilder current = new StringBuilder();
            for (String sentence : sentences) {
                if (current.length() > 0 && current.length() + sentence.length() > chunkSize) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                if (sentence.length() > chunkSize) {
                    chunks.addAll(splitLongSentence(sentence, chunkSize));
                } else {
                    current.append(sentence);
                }
            }
            if (current.length() > 0) {
                chunks.add(current.toString());
            }
        }

        // 合并过短 chunk，避免出现只有标题的碎片
        int minSize = Math.max(1, minChunkSize);
        List<String> merged = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            String current = chunks.get(index);
            if (current.length() < minSize && index + 1 < chunks.size()) {
                merged.add(current + "\n\n" + chunks.get(++index));
            } else {
                merged.add(current);
            }
        }

        // 给后续 chunk 增加上一 chunk 的尾句重叠
        for (int index = 1; index < merged.size(); index++) {
            String previous = merged.get(index - 1);
            String overlap = trailingOverlap(previous);
            if (!overlap.isEmpty()) {
                merged.set(index, overlap + "\n\n" + merged.get(index));
            }
        }
        return merged;
    }

    private String trailingOverlap(String previous) {
        if (previous == null || previous.isBlank() || overlapSize <= 0) {
            return "";
        }
        String[] sentences = previous.split("(?<=[。！？!?；;])");
        StringBuilder overlap = new StringBuilder();
        for (int index = sentences.length - 1; index >= 0; index--) {
            String sentence = sentences[index].strip();
            if (sentence.isEmpty()) {
                continue;
            }
            if (overlap.length() + sentence.length() > overlapSize) {
                break;
            }
            overlap.insert(0, sentence);
        }
        return overlap.toString();
    }

    // ========================
    // 工具方法
    // ========================

    private void checkMemoryThreshold() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        double memoryUsage = (double) usedMemory / maxMemory;

        if (memoryUsage > maxMemoryThreshold) {
            logger.warn("内存使用率过高: {}%，触发垃圾回收", String.format("%.2f", memoryUsage * 100));
            System.gc();
            usedMemory = runtime.totalMemory() - runtime.freeMemory();
            memoryUsage = (double) usedMemory / maxMemory;
            if (memoryUsage > maxMemoryThreshold) {
                throw new RuntimeException("内存不足，无法处理大文件。当前使用率: "
                        + String.format("%.2f%%", memoryUsage * 100));
            }
        }
    }

    /**
     * Tika 文本提取处理器：
     * - 按段落/标题补换行，避免 DOCX 被抽成一整行后案件标题无法识别；
     * - 把表格转成带 [TABLE] / | 分隔的文本，保留证据清单、金额表等结构；
     * - 图片只保留 [图片] 占位，OCR 由后续扩展处理。
     */
    private static class StructuredTextExtractionHandler extends BodyContentHandler {
        private final StringBuilder fullBuffer = new StringBuilder();
        private boolean inTable;
        private boolean cellInRow;

        StructuredTextExtractionHandler() {
            super(-1);
        }

        @Override
        public void startElement(String uri, String localName, String qName, org.xml.sax.Attributes attributes) {
            String name = localName == null || localName.isBlank() ? qName : localName;
            switch (name) {
                case "p", "h1", "h2", "h3", "h4", "h5", "h6", "div", "li", "section" -> ensureNewline();
                case "table" -> {
                    ensureNewline();
                    append("[TABLE]");
                    ensureNewline();
                    inTable = true;
                }
                case "tr" -> {
                    if (inTable) {
                        ensureNewline();
                        append("| ");
                        cellInRow = false;
                    }
                }
                case "td", "th" -> {
                    if (inTable && cellInRow) {
                        append(" | ");
                    }
                    cellInRow = true;
                }
                case "img" -> append("[图片]");
                default -> {
                    // ignore other structural elements
                }
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String name = localName == null || localName.isBlank() ? qName : localName;
            switch (name) {
                case "p", "h1", "h2", "h3", "h4", "h5", "h6", "div", "li", "section" -> ensureNewline();
                case "tr" -> {
                    if (inTable) {
                        append(" |");
                        ensureNewline();
                    }
                }
                case "table" -> {
                    ensureNewline();
                    append("[/TABLE]");
                    ensureNewline();
                    inTable = false;
                    cellInRow = false;
                }
                default -> {
                    // ignore
                }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            fullBuffer.append(ch, start, length);
        }

        private void append(String text) {
            fullBuffer.append(text);
        }

        private void ensureNewline() {
            if (fullBuffer.length() > 0 && fullBuffer.charAt(fullBuffer.length() - 1) != '\n') {
                fullBuffer.append('\n');
            }
        }

        public String getFullText() {
            return fullBuffer.toString();
        }
    }

    public record EmbeddingEstimate(long estimatedTokens, int estimatedChunkCount) {
    }
}
