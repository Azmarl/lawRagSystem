package com.yizhaoqi.rag.service;

import com.yizhaoqi.rag.chunker.ChunkDTO;
import com.yizhaoqi.rag.chunker.TreeAwareChunker;
import com.yizhaoqi.rag.model.DocumentVector;
import com.yizhaoqi.rag.parser.LegalStructureParser;
import com.yizhaoqi.rag.parser.LegalNode;
import com.yizhaoqi.rag.repository.DocumentVectorRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import java.io.*;
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
    private UsageQuotaService usageQuotaService;

    @Value("${file.parsing.chunk-size}")
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

    // ===== 新流水线组件 =====
    private final LegalStructureParser legalStructureParser = new LegalStructureParser();

    public ParseService() {
    }

    /**
     * 以流式方式解析文件并保存到数据库。
     * 新流水线：Tika/PDFBox 提取纯文本 → LegalStructureParser 建树 → TreeAwareChunker 分块 → 入库。
     *
     * @param fileMd5    文件的MD5哈希值
     * @param fileStream 文件输入流
     * @param userId     上传用户ID
     * @param orgTag     组织标签
     * @param isPublic   是否公开
     * @throws IOException   如果文件读取过程中发生错误
     * @throws TikaException 如果文件解析过程中发生错误
     */
    public void parseAndSave(String fileMd5, InputStream fileStream,
            String userId, String orgTag, boolean isPublic) throws IOException, TikaException {
        logger.info("开始流式解析文件，fileMd5: {}, userId: {}, orgTag: {}, isPublic: {}",
                fileMd5, userId, orgTag, isPublic);

        checkMemoryThreshold();

        try (BufferedInputStream bufferedStream = new BufferedInputStream(fileStream, bufferSize)) {
            if (isPdfDocument(bufferedStream)) {
                parsePdfAndSave(fileMd5, bufferedStream, userId, orgTag, isPublic);
                logger.info("PDF 文件树状分块解析和入库完成，fileMd5: {}", fileMd5);
                return;
            }

            // Tika 提取纯文本 → 新分块流水线
            FullTextExtractionHandler handler = new FullTextExtractionHandler();
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            AutoDetectParser parser = new AutoDetectParser();

            parser.parse(bufferedStream, handler, metadata, context);
            String fullText = handler.getFullText();

            if (fullText.isBlank()) {
                logger.warn("Tika 提取到空文本，fileMd5: {}", fileMd5);
                return;
            }

            // 新流水线：结构解析 → 树状分块
            LegalNode root = legalStructureParser.parse(fullText);
            List<ChunkDTO> chunkDTOs = newChunker().chunk(root);

            if (chunkDTOs.isEmpty()) {
                // 降级：非法律文本回退到简单段落分块
                logger.info("未检测到法律层级结构，使用简单分块作为降级方案，fileMd5: {}", fileMd5);
                List<String> fallbackChunks = splitTextIntoChunksFallback(fullText, chunkSize);
                saveChildChunksFallback(fileMd5, fallbackChunks, userId, orgTag, isPublic);
                return;
            }

            // 保存分块结果
            saveChildChunks(fileMd5, chunkDTOs, userId, orgTag, isPublic);
            logger.info("文件流式解析和树状分块入库完成，fileMd5: {}", fileMd5);

        } catch (SAXException e) {
            logger.error("文档解析失败，fileMd5: {}", fileMd5, e);
            throw new RuntimeException("文档解析失败", e);
        }
    }

    /**
     * 兼容旧版本的解析方法
     */
    public void parseAndSave(String fileMd5, InputStream fileStream) throws IOException, TikaException {
        parseAndSave(fileMd5, fileStream, "unknown", "DEFAULT", false);
    }

    public EmbeddingEstimate estimateEmbeddingUsage(InputStream fileStream) throws IOException, TikaException {
        logger.info("开始估算文档 Embedding Token");
        checkMemoryThreshold();

        try (BufferedInputStream bufferedStream = new BufferedInputStream(fileStream, bufferSize)) {
            if (isPdfDocument(bufferedStream)) {
                return estimatePdfEmbeddingUsage(bufferedStream);
            }

            FullTextExtractionHandler handler = new FullTextExtractionHandler();
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            AutoDetectParser parser = new AutoDetectParser();
            parser.parse(bufferedStream, handler, metadata, context);

            String fullText = handler.getFullText();
            LegalNode root = legalStructureParser.parse(fullText);
            List<ChunkDTO> chunkDTOs = newChunker().chunk(root);

            if (chunkDTOs.isEmpty()) {
                List<String> fallbackChunks = splitTextIntoChunksFallback(fullText, chunkSize);
                long tokens = usageQuotaService.estimateEmbeddingTokens(fallbackChunks);
                return new EmbeddingEstimate(tokens, fallbackChunks.size());
            }

            List<String> texts = chunkDTOs.stream().map(ChunkDTO::getTextContent).toList();
            long tokens = usageQuotaService.estimateEmbeddingTokens(texts);
            return new EmbeddingEstimate(tokens, chunkDTOs.size());
        } catch (SAXException e) {
            logger.error("文档 Embedding Token 估算失败", e);
            throw new RuntimeException("文档 Embedding Token 估算失败", e);
        }
    }

    // ========================
    // 内存检查（保留）
    // ========================

    private void checkMemoryThreshold() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        double memoryUsage = (double) usedMemory / maxMemory;

        if (memoryUsage > maxMemoryThreshold) {
            logger.warn("内存使用率过高: {:.2f}%, 触发垃圾回收", memoryUsage * 100);
            System.gc();

            usedMemory = runtime.totalMemory() - runtime.freeMemory();
            memoryUsage = (double) usedMemory / maxMemory;

            if (memoryUsage > maxMemoryThreshold) {
                throw new RuntimeException("内存不足，无法处理大文件。当前使用率: " +
                    String.format("%.2f%%", memoryUsage * 100));
            }
        }
    }

    // ========================
    // 新流水线：Tika 全文本提取
    // ========================

    /**
     * Tika 全文本提取处理器（不在此处做任何切分）。
     */
    private class FullTextExtractionHandler extends BodyContentHandler {
        private final StringBuilder fullBuffer = new StringBuilder();

        public FullTextExtractionHandler() {
            super(-1);
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            fullBuffer.append(ch, start, length);
        }

        public String getFullText() {
            return fullBuffer.toString();
        }
    }

    // ========================
    // 新流水线：保存 ChunkDTO 列表
    // ========================

    /**
     * 将 ChunkDTO 列表保存到数据库（新版本，携带法律层级元数据）。
     */
    private int saveChildChunks(String fileMd5, List<ChunkDTO> chunks,
            String userId, String orgTag, boolean isPublic) {
        int currentChunkId = 0;
        for (ChunkDTO chunk : chunks) {
            if (chunk.getTextContent() == null || chunk.getTextContent().isBlank()) {
                continue;
            }
            currentChunkId++;
            var vector = new DocumentVector();
            vector.setFileMd5(fileMd5);
            vector.setChunkId(currentChunkId);
            vector.setTextContent(chunk.getTextContent());
            vector.setPageNumber(null);
            vector.setAnchorText(buildAnchorText(chunk.getTextContent()));
            vector.setUserId(userId);
            vector.setOrgTag(orgTag);
            vector.setPublic(isPublic);

            // 法律层级元数据
            vector.setChunkLevel(chunk.getChunkLevel());
            vector.setPartName(chunk.getPartName());
            vector.setChapterName(chunk.getChapterName());
            vector.setSectionName(chunk.getSectionName());
            vector.setArticleId(chunk.getArticleId());
            vector.setArticleIdDensity(chunk.getArticleIdDensity());
            vector.setLegalTermDensity(chunk.getLegalTermDensity());

            documentVectorRepository.save(vector);
        }
        logger.info("成功保存 {} 个子切片到数据库（树状分块模式）", currentChunkId);
        return currentChunkId;
    }

    /**
     * 降级保存：使用旧格式 List<String>。
     */
    private int saveChildChunksFallback(String fileMd5, List<String> chunks,
            String userId, String orgTag, boolean isPublic) {
        int currentChunkId = 0;
        for (String chunk : chunks) {
            if (chunk == null || chunk.isBlank()) {
                continue;
            }
            currentChunkId++;
            var vector = new DocumentVector();
            vector.setFileMd5(fileMd5);
            vector.setChunkId(currentChunkId);
            vector.setTextContent(chunk);
            vector.setPageNumber(null);
            vector.setAnchorText(buildAnchorText(chunk));
            vector.setUserId(userId);
            vector.setOrgTag(orgTag);
            vector.setPublic(isPublic);
            documentVectorRepository.save(vector);
        }
        logger.info("成功保存 {} 个子切片到数据库（降级模式）", currentChunkId);
        return currentChunkId;
    }

    // ========================
    // PDF 解析（保留核心逻辑 + 新分块流水线）
    // ========================

    private void parsePdfAndSave(String fileMd5, InputStream fileStream,
            String userId, String orgTag, boolean isPublic) throws IOException {
        try (PDDocument document = PDDocument.load(fileStream)) {

            // 保留：提取并清理每页文本
            List<String> cleanedPageTexts = extractCleanPdfPageTexts(document);

            // 拼接所有页的文本
            String fullText = String.join("\n", cleanedPageTexts);

            // 新流水线：结构解析 → 树状分块
            LegalNode root = legalStructureParser.parse(fullText);
            List<ChunkDTO> chunkDTOs = newChunker().chunk(root);

            if (chunkDTOs.isEmpty()) {
                logger.info("PDF 未检测到法律层级结构，使用简单分块作为降级方案，fileMd5: {}",
                        fileMd5);
                int savedChunkCount = 0;
                for (int pageNumber = 1; pageNumber <= cleanedPageTexts.size(); pageNumber++) {
                    String pageText = cleanedPageTexts.get(pageNumber - 1);
                    if (pageText == null || pageText.isBlank()) {
                        continue;
                    }
                    List<String> childChunks = splitTextIntoChunksFallback(pageText, chunkSize);
                    savedChunkCount = saveChildChunksFallback(fileMd5, childChunks,
                            userId, orgTag, isPublic);
                }
                return;
            }

            saveChildChunks(fileMd5, chunkDTOs, userId, orgTag, isPublic);
        }
    }

    private EmbeddingEstimate estimatePdfEmbeddingUsage(InputStream fileStream) throws IOException {
        try (PDDocument document = PDDocument.load(fileStream)) {
            List<String> cleanedPageTexts = extractCleanPdfPageTexts(document);
            String fullText = String.join("\n", cleanedPageTexts);

            LegalNode root = legalStructureParser.parse(fullText);
            List<ChunkDTO> chunkDTOs = newChunker().chunk(root);

            if (chunkDTOs.isEmpty()) {
                List<String> fallbackChunks = splitTextIntoChunksFallback(fullText, chunkSize);
                long tokens = usageQuotaService.estimateEmbeddingTokens(fallbackChunks);
                return new EmbeddingEstimate(tokens, fallbackChunks.size());
            }

            List<String> texts = chunkDTOs.stream().map(ChunkDTO::getTextContent).toList();
            long tokens = usageQuotaService.estimateEmbeddingTokens(texts);
            return new EmbeddingEstimate(tokens, chunkDTOs.size());
        }
    }

    // ========================
    // PDF 文本清理（护城河代码，完整保留）
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
                    rawPageLines.get(pageIndex),
                    topLineCounts,
                    bottomLineCounts,
                    repeatedThreshold
            );
            String cleanedText = String.join("\n", cleanedLines).trim();
            cleanedPages.add(cleanedText);
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

    private Map<String, Integer> collectBoundaryLineCounts(List<List<String>> pageLines,
            boolean topBoundary) {
        Map<String, Integer> counts = new HashMap<>();
        for (List<String> lines : pageLines) {
            List<String> boundaryLines = topBoundary
                    ? firstMeaningfulLines(lines, PDF_BOUNDARY_SCAN_LINES)
                    : lastMeaningfulLines(lines, PDF_BOUNDARY_SCAN_LINES);
            for (String line : boundaryLines) {
                String key = normalizePdfBoundaryLine(line);
                if (key == null) {
                    continue;
                }
                counts.merge(key, 1, Integer::sum);
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

    private List<String> removePdfBoilerplateLines(
            List<String> lines,
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
    // 降级分块（简单段落分块，非法律文本时使用）
    // ========================

    /**
     * 简单段落分块：按 \\n\\n+ 切分段落，不依赖 HanLP。
     */
    private List<String> splitTextIntoChunksFallback(String text, int chunkSize) {
        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\\n\\n+");
        StringBuilder currentChunk = new StringBuilder();

        for (String paragraph : paragraphs) {
            String p = paragraph.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (currentChunk.length() > 0
                    && currentChunk.length() + p.length() + 2 > chunkSize) {
                chunks.add(currentChunk.toString().trim());
                currentChunk = new StringBuilder();
            }
            if (currentChunk.length() > 0) {
                currentChunk.append("\n\n");
            }
            currentChunk.append(p);

            // 如果单段太长，按句子切
            if (p.length() > chunkSize) {
                // 完成当前 chunk
                if (!currentChunk.isEmpty() && !currentChunk.toString().equals(p)) {
                    chunks.add(currentChunk.toString().trim());
                }
                // 按句子切长段落
                int start = 0;
                while (start < p.length()) {
                    int end = Math.min(start + chunkSize, p.length());
                    // 尝试在句末切分
                    if (end < p.length()) {
                        int lastPeriod = p.lastIndexOf('。', end);
                        if (lastPeriod > start) {
                            end = lastPeriod + 1;
                        }
                    }
                    chunks.add(p.substring(start, end).trim());
                    start = end;
                }
                currentChunk = new StringBuilder();
            }
        }

        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk.toString().trim());
        }
        return chunks;
    }

    // ========================
    // 工具方法
    // ========================

    private TreeAwareChunker newChunker() {
        return new TreeAwareChunker(chunkSize, overlapSize);
    }

    public record EmbeddingEstimate(long estimatedTokens, int estimatedChunkCount) {
    }
}