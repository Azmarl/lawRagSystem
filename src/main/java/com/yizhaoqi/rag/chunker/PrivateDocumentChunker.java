package com.yizhaoqi.rag.chunker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public class PrivateDocumentChunker {

    private static final Pattern HEADING = Pattern.compile(
            "^\\s*(?:[一二三四五六七八九十百千万]+、|（[一二三四五六七八九十百千万]+）|\\(?\\d+(?:\\.\\d+)*\\)?[、.)．]|第[一二三四五六七八九十百千万]+条)\\s*.*$");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？!?；;])\\s+|\\R+");

    private final LegalTermScorer termScorer;
    private final int maxTokens;
    private final double overlapRatio;

    public PrivateDocumentChunker(LegalTermScorer termScorer, int maxTokens, double overlapRatio) {
        this.termScorer = termScorer;
        this.maxTokens = maxTokens;
        this.overlapRatio = overlapRatio;
    }

    public List<ChunkDTO> chunk(String text) {
        List<ChunkDTO> result = new ArrayList<>();
        for (String parent : splitParents(text)) {
            List<String> sentences = splitSentences(parent);
            appendPackedChunks(result, parent, sentences);
        }
        return result;
    }

    private void appendPackedChunks(List<ChunkDTO> result, String parent, List<String> sentences) {
        if (sentences.isEmpty()) {
            return;
        }
        int start = 0;
        int position = 0;
        int overlapCount = Math.max(1, (int) Math.ceil(sentences.size() * overlapRatio));
        while (start < sentences.size()) {
            StringBuilder content = new StringBuilder();
            int end = start;
            while (end < sentences.size()) {
                String candidate = content.length() == 0
                        ? sentences.get(end)
                        : content + " " + sentences.get(end);
                if (content.length() > 0 && termScorer.estimateTokenCount(candidate) > maxTokens) {
                    break;
                }
                content.setLength(0);
                content.append(candidate);
                end++;
            }
            if (end == start) {
                content.append(sentences.get(start));
                end++;
            }
            String chunkText = content.toString().trim();
            result.add(ChunkDTO.builder()
                    .textContent(chunkText)
                    .chunkLevel("private")
                    .partName(parentHeading(parent))
                    .parentBlockId("parent-" + Integer.toHexString(parent.hashCode()))
                    .chunkPosition(position)
                    .tokenCount(termScorer.estimateTokenCount(chunkText))
                    .legalTermDensity(termScorer.computeLegalTermDensity(chunkText))
                    .articleIdDensity(termScorer.computeArticleIdDensity(chunkText))
                    .build());
            position++;
            if (end >= sentences.size()) {
                break;
            }
            start = Math.max(start + 1, end - overlapCount);
        }
    }

    private List<String> splitParents(String text) {
        List<String> parents = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.replace("\r", "").split("\n")) {
            String normalized = line.trim();
            if (normalized.isEmpty()) {
                continue;
            }
            if (current.length() > 0 && HEADING.matcher(normalized).matches()) {
                parents.add(current.toString().trim());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(normalized);
        }
        if (current.length() > 0) {
            parents.add(current.toString().trim());
        }
        return parents;
    }

    private List<String> splitSentences(String parent) {
        return Arrays.stream(SENTENCE_END.split(parent))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    private String parentHeading(String parent) {
        int newline = parent.indexOf('\n');
        return newline < 0 ? parent : parent.substring(0, newline).trim();
    }
}
