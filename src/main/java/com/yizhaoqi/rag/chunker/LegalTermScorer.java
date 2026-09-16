package com.yizhaoqi.rag.chunker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 法律术语与条文号密度计算器。
 *
 * 功能：
 * - 计算 legalTermDensity（法律术语命中数 / 总 token 数）
 * - 计算 articleIdDensity（条文号命中数 / 总 token 数）
 * - 计算 referenceDensity（引用标记命中数 / 总 token 数）
 *
 * 同时可用于查询端，计算 query 的 articleIdDensity 和 legalTermDensity，
 * 以推导 Adaptive α 权重。
 */
public class LegalTermScorer {

    private static final Logger logger = LoggerFactory.getLogger(LegalTermScorer.class);

    // ===== 法律术语词典 =====
    private static final Set<String> LEGAL_TERMS = new HashSet<>(Arrays.asList(
            "合同", "违约", "履行", "解除", "撤销", "无效", "效力", "代理",
            "物权", "债权", "侵权", "责任", "赔偿", "损失", "过错",
            "民事", "刑事", "刑法", "罪名", "犯罪", "量刑",
            "自首", "立功", "累犯", "正当防卫", "紧急避险",
            "故意", "过失",
            "知识产权", "著作权", "专利", "商标",
            "婚姻", "继承", "收养", "监护", "遗嘱",
            "时效", "期间", "管辖", "诉讼",
            "担保", "抵押", "质押", "留置",
            "法人", "自然人", "非法人组织",
            "法律行为", "意思表示", "无效",
            "诉讼时效", "除斥期间",
            // 常见法典名称（用于提升法律查询的 legalTermDensity）
            "民法典", "刑法典", "宪法", "行政法",
            "刑事诉讼法", "民事诉讼法", "行政诉讼法",
            "公司法", "劳动法", "合同法", "物权法", "侵权责任法"
    ));

    // ===== 引用标记词典 =====
    private static final Set<String> REFERENCE_MARKERS = new HashSet<>(Arrays.asList(
            "参照", "适用", "前款", "本法", "本条", "前述", "上述", "下列", "前条"
    ));

    // 条文号正则：第[数字]+条
    private static final Pattern ARTICLE_ID_PATTERN = Pattern.compile(
            "第[0-9零一二三四五六七八九十百千]+条");

    // 中文数字 → 阿拉伯数字映射
    private static final Map<Character, Integer> CN_DIGIT_MAP = Map.ofEntries(
            Map.entry('零', 0), Map.entry('一', 1), Map.entry('二', 2),
            Map.entry('三', 3), Map.entry('四', 4), Map.entry('五', 5),
            Map.entry('六', 6), Map.entry('七', 7), Map.entry('八', 8),
            Map.entry('九', 9)
    );

    private static final Map<Character, Integer> CN_UNIT_MAP = Map.of(
            '十', 10, '百', 100, '千', 1000
    );

    // 阿拉伯数字 → 中文数字（简单映射，仅支持 1-9 的个位转换，完整转换见 convertArabicToChineseArticleId）
    private static final char[] CN_DIGIT_CHARS = {'零', '一', '二', '三', '四', '五', '六', '七', '八', '九'};
    private static final char[] CN_UNITS = {' ', '十', '百', '千'};

    /**
     * 估算文本的 token 数（中文按 1 字 1 token，英文按空格分割）。
     */
    public int estimateTokenCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int count = 0;
        for (char c : text.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION) {
                count++;
            } else if (!Character.isWhitespace(c)) {
                count++;
            }
        }
        // 英文单词也按1:1估算
        return Math.max(1, count);
    }

    /**
     * 计算法律术语密度。
     *
     * @param text 文本内容
     * @return legalTermDensity = 法律术语命中数 / 总 token 数
     */
    public double computeLegalTermDensity(String text) {
        if (text == null || text.isBlank()) {
            return 0.0;
        }
        int tokenCount = estimateTokenCount(text);
        if (tokenCount == 0) {
            return 0.0;
        }
        int hitCount = 0;
        for (String term : LEGAL_TERMS) {
            int idx = 0;
            while ((idx = text.indexOf(term, idx)) != -1) {
                hitCount++;
                idx += term.length();
            }
        }
        return (double) hitCount / tokenCount;
    }

    /**
     * 计算条文号密度。
     *
     * @param text 文本内容
     * @return articleIdDensity = 条文号命中数 / 总 token 数
     */
    public double computeArticleIdDensity(String text) {
        if (text == null || text.isBlank()) {
            return 0.0;
        }
        int tokenCount = estimateTokenCount(text);
        if (tokenCount == 0) {
            return 0.0;
        }
        Matcher m = ARTICLE_ID_PATTERN.matcher(text);
        int hitCount = 0;
        while (m.find()) {
            hitCount++;
        }
        return (double) hitCount / tokenCount;
    }

    /**
     * 计算引用标记密度。
     */
    public double computeReferenceDensity(String text) {
        if (text == null || text.isBlank()) {
            return 0.0;
        }
        int tokenCount = estimateTokenCount(text);
        if (tokenCount == 0) {
            return 0.0;
        }
        int hitCount = 0;
        for (String marker : REFERENCE_MARKERS) {
            int idx = 0;
            while ((idx = text.indexOf(marker, idx)) != -1) {
                hitCount++;
                idx += marker.length();
            }
        }
        return (double) hitCount / tokenCount;
    }

    /**
     * 计算查询文本的条文号密度（供 Adaptive α 使用）。
     * 同时返回匹配到的条文号列表和 token 数。
     */
    public ArticleIdQueryInfo analyzeQuery(String queryText) {
        if (queryText == null || queryText.isBlank()) {
            return new ArticleIdQueryInfo(0.0, 0.0, 0, Collections.emptyList());
        }

        int tokenCount = estimateTokenCount(queryText);
        if (tokenCount == 0) {
            return new ArticleIdQueryInfo(0.0, 0.0, 0, Collections.emptyList());
        }

        Matcher m = ARTICLE_ID_PATTERN.matcher(queryText);
        int articleHitCount = 0;
        List<String> matchedArticleIds = new ArrayList<>();
        while (m.find()) {
            articleHitCount++;
            matchedArticleIds.add(m.group());
        }
        double articleIdDensity = (double) articleHitCount / tokenCount;

        // 法律术语命中
        int termHitCount = 0;
        for (String term : LEGAL_TERMS) {
            int idx = 0;
            while ((idx = queryText.indexOf(term, idx)) != -1) {
                termHitCount++;
                idx += term.length();
            }
        }
        double legalTermDensity = (double) termHitCount / tokenCount;

        return new ArticleIdQueryInfo(articleIdDensity, legalTermDensity, tokenCount, matchedArticleIds);
    }

    /**
     * 计算 Adaptive α 权重。
     * α = sigmoid(w1 * articleIdDensity + w2 * legalTermDensity + bias)
     *
     * @param articleIdDensity 查询中文条号密度
     * @param legalTermDensity 查询中法律术语密度
     * @return α ∈ [0, 1]
     */
    public double computeAlpha(double articleIdDensity, double legalTermDensity) {
        // 默认权重参数（可通过离线 grid search 调优）
        double w1 = 8.0;
        double w2 = 3.0;
        double bias = -2.5;

        double z = w1 * articleIdDensity + w2 * legalTermDensity + bias;
        return sigmoid(z);
    }

    /**
     * 计算 Adaptive α 权重（带精确条文查询补偿）。
     *
     * 当查询包含条文号（第X条）且查询较短时，IK 分词会将条文号拆散，
     * 导致 BM25 match 查询匹配到所有引用该条文的其他条文（而非条文正文本身）。
     * 此时需要大幅提升 α，让 BM25 + match_phrase 精确匹配主导召回。
     *
     * 补偿策略：
     * - 基础 α = sigmoid(w1 * articleIdDensity + w2 * legalTermDensity + bias)
     * - 如果 matchedArticleIds 非空且 tokenCount < 30：
     *   compensation = min(0.65, articleIdCount / tokenCount * 4.0)
     *   最终 α = max(基础α, compensation)
     *
     * 示例："民法典第123条的内容是什么？" (14 token, 1 articleId)
     *   基础 α ≈ 0.175（因为"民法典"现在在词典中）
     *   compensation = min(0.65, 1/14 * 4.0) ≈ 0.286
     *   最终 α ≈ 0.286 — 仍偏低，但实际上 compensation 公式需要更激进
     *
     * 修正后补偿公式（更激进）：
     *   compensation = clamp(0.5 + 2.0 * articleIdDensity - 0.02 * tokenCount, 0.35, 0.85)
     *   示例：0.5 + 2.0*0.071 - 0.02*14 = 0.5 + 0.143 - 0.28 = 0.363 → clamp → 0.363
     *
     * @param queryInfo 查询分析结果
     * @return α ∈ [0, 1]
     */
    public double computeAlpha(ArticleIdQueryInfo queryInfo) {
        double baseAlpha = computeAlpha(queryInfo.articleIdDensity(), queryInfo.legalTermDensity());

        if (queryInfo.matchedArticleIds().isEmpty()) {
            return baseAlpha;
        }

        // 精确条文查询补偿: 当查询包含条文号时，大幅偏向 BM25
        // 补偿公式: compensation = 0.75 + 0.15*articleIdCount - 0.01*tokenCount
        // 对 "民法典第123条的内容是什么？" (14 token, 1 articleId):
        //   compensation = 0.75 + 0.15 - 0.14 = 0.76 → final α = max(base, 0.76) ≥ 0.76
        double articleCount = queryInfo.matchedArticleIds().size();
        double tokenCount = queryInfo.tokenCount();
        double compensation = 0.75 + 0.15 * articleCount - 0.01 * tokenCount;
        compensation = clamp(compensation, 0.55, 0.90);

        double finalAlpha = Math.max(baseAlpha, compensation);
        logger.debug("Adaptive α 补偿: base={:.4f}, compensation={:.4f}, final={:.4f}, " +
                        "articleIdDensity={:.4f}, legalTermDensity={:.4f}, articleIds={}",
                baseAlpha, compensation, finalAlpha,
                queryInfo.articleIdDensity(), queryInfo.legalTermDensity(),
                queryInfo.matchedArticleIds());

        return finalAlpha;
    }

    private double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 将匹配到的条文号展开为双向格式（阿拉伯数字 + 中文数字两种形式）。
     *
     * 场景：用户输入"第123条"（阿拉伯数字），但民法典索引中文档原文是"第一百二十三条"（中文数字），
     * 由于 articleId 是 keyword 字段，精确匹配会失败。同时 textContent 中 match_phrase
     * 也仅匹配完全相同的字符串。
     *
     * 解法：将每个匹配到的 articleId 展开为两种格式，同时做 term 精确匹配和 match_phrase 短语匹配。
     *
     * 例：
     *   输入 ["第123条"] → 输出 ["第123条", "第一百二十三条"]
     *   输入 ["第一百二十三条"] → 输出 ["第一百二十三条", "第123条"]
     *   输入 ["第123条", "第124条"] → 输出 ["第123条", "第一百二十三条", "第124条", "第一百二十四条"]
     */
    public List<String> expandArticleIds(List<String> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> expanded = new LinkedHashSet<>(articleIds);
        for (String id : articleIds) {
            if (isArabicArticleId(id)) {
                String cnForm = arabicToChineseArticleId(id);
                if (cnForm != null && !cnForm.equals(id)) {
                    expanded.add(cnForm);
                }
            } else {
                String arForm = chineseToArabicArticleId(id);
                if (arForm != null && !arForm.equals(id)) {
                    expanded.add(arForm);
                }
            }
        }
        return new ArrayList<>(expanded);
    }

    /**
     * 判断条文号是否为阿拉伯数字格式（"第123条"）。
     */
    private boolean isArabicArticleId(String id) {
        return id != null && id.matches("第[0-9]+条");
    }

    /**
     * 阿拉伯数字条文号 → 中文数字条文号。
     * "第123条" → "第一百二十三条"
     */
    private String arabicToChineseArticleId(String id) {
        java.util.regex.Matcher m = Pattern.compile("第([0-9]+)条").matcher(id);
        if (!m.find()) {
            return null;
        }
        int num = Integer.parseInt(m.group(1));
        String cn = arabicNumberToChinese(num);
        return "第" + cn + "条";
    }

    /**
     * 中文数字条文号 → 阿拉伯数字条文号。
     * "第一百二十三条" → "第123条"
     */
    private String chineseToArabicArticleId(String id) {
        java.util.regex.Matcher m = Pattern.compile("第([零一二三四五六七八九十百千]+)条").matcher(id);
        if (!m.find()) {
            return null;
        }
        String cnNum = m.group(1);
        int result = convertChineseNumberToArabic(cnNum);
        return "第" + result + "条";
    }

    /**
     * 中文数字字符串 → 阿拉伯数字 int。
     * 支持 "一百二十三"、"一千零一"、"十" 等格式。
     */
    private int convertChineseNumberToArabic(String cn) {
        if (cn == null || cn.isEmpty()) {
            return 0;
        }
        int result = 0;
        int section = 0;
        for (int i = 0; i < cn.length(); i++) {
            char c = cn.charAt(i);
            Integer digit = CN_DIGIT_MAP.get(c);
            Integer unit = CN_UNIT_MAP.get(c);

            if (digit != null) {
                section = digit;
            } else if (unit != null) {
                if (section == 0) {
                    section = 1;
                }
                section = section * unit;
                result += section;
                section = 0;
            }
        }
        result += section;
        return result;
    }

    /**
     * 阿拉伯数字 → 中文数字字符串。
     * 123 → "一百二十三", 10 → "十"
     */
    private String arabicNumberToChinese(int num) {
        if (num == 0) {
            return "零";
        }
        StringBuilder sb = new StringBuilder();
        int[] digits = new int[4];
        int temp = num;
        int pos = 0;
        while (temp > 0) {
            digits[pos++] = temp % 10;
            temp /= 10;
        }
        // pos 现在是数字的实际位数
        boolean hasZero = false;
        for (int i = pos - 1; i >= 0; i--) {
            int d = digits[i];
            if (d == 0) {
                hasZero = true;
            } else {
                if (hasZero) {
                    sb.append('零');
                    hasZero = false;
                }
                // 对于十位，如果值是 1 且前面已经有过内容（如 "一十" 简化为 "十"）
                // 规则：如果是最高位的 1 且单位是十，省略 "一"（"一十" → "十"）
                if (i == 1 && d == 1 && sb.length() == 0) {
                    // "十" 开头，省略 "一"
                } else {
                    sb.append(CN_DIGIT_CHARS[d]);
                }
                if (i > 0) {
                    sb.append(CN_UNITS[i]);
                }
            }
        }
        return sb.toString();
    }

    /**
     * 查询分析结果 DTO。
     */
    public record ArticleIdQueryInfo(
            double articleIdDensity,
            double legalTermDensity,
            int tokenCount,
            List<String> matchedArticleIds
    ) {
    }
}