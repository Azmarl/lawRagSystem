package com.yizhaoqi.rag.service;

import com.yizhaoqi.rag.client.AliyunDecisionClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 检索前意图识别。
 *
 * <p>优先调用阿里云决策模型（可替换为其他国产策略模型）；模型不可用、超时或置信度低于阈值时，
 * 使用确定性的规则分类兜底，保证检索链路不会被外部模型阻塞。</p>
 */
@Service
public class IntentRecognitionService {

    private static final Logger logger = LoggerFactory.getLogger(IntentRecognitionService.class);

    private static final Pattern ARTICLE_ID = Pattern.compile("第[0-9零一二三四五六七八九十百千]+条");

    private static final Pattern PRONOUN_FOLLOW_UP = Pattern.compile(
            "^(那|那么|它|这个|该|上述|继续|再|还有|然后|依据呢|怎么办|怎么处理)[，,。？?\\s]*.*$");

    private static final List<String> PRIVATE_SIGNALS = List.of(
            "我的案件", "我这个案子", "我这边", "本案", "该案", "这起案件", "这个案子",
            "案件材料", "案件报告", "案件分析", "我上传", "我上传的", "我的材料", "我的文件",
            "我的文档", "我的资料", "私人", "私有", "这份合同", "这个合同", "我的合同",
            "这份报告", "这个报告", "报告书", "起诉状", "答辩状", "上诉状", "代理词", "辩护词",
            "判决书", "裁定书", "调解书", "委托代理", "当事人", "案情", "证据材料", "证据清单",
            "诉讼请求", "争议焦点", "我方", "被告方", "原告方", "案号", "我的案件材料",
            // 用户经常用“那个案件 / 这个案子 / 立项的案件”指代自己上传的案件，不一定会说“我的案件”
            "案件", "案子", "立案", "立项", "案件背景", "案件情况", "那个案件", "这个案件",
            "该案件", "这起案件", "承办", "承办案件", "代理案件", "在办案件"
    );

    private static final List<String> PUBLIC_SIGNALS = List.of(
            "民法典", "刑法典", "刑法", "宪法", "刑事诉讼法", "民事诉讼法", "行政诉讼法",
            "公司法", "劳动法", "合同法", "物权法", "侵权责任法", "司法解释", "法条", "条文",
            "法律规定", "法律责任", "量刑", "罪名", "构成要件", "如何处罚", "如何认定",
            "法律依据", "法定", "条例", "办法", "法典"
    );

    private static final List<String> MIXED_SIGNALS = List.of(
            "结合", "对照", "同时分析", "分别", "既要", "又需要", "是否符合", "是否合法",
            "如何定性", "诉讼策略", "风险评估", "合规分析", "法律意见", "依据法律",
            "根据法律", "按照法律", "法律上", "如何维权", "怎么维权", "能否胜诉",
            "胜诉可能", "法律责任如何"
    );

    private final AliyunDecisionClient decisionClient;
    private final double confidenceThreshold;

    public IntentRecognitionService(
            AliyunDecisionClient decisionClient,
            @Value("${aliyun.decision.routing.confidence-threshold:0.85}") double confidenceThreshold) {
        this.decisionClient = decisionClient;
        this.confidenceThreshold = confidenceThreshold;
    }

    /**
     * 识别本次提问的检索范围。
     *
     * @param query               用户当前问题
     * @param conversationContext 最近几轮对话/检索摘要，用于指代消解
     * @param historyTags         历史检索命中的语料标签
     */
    public RetrievalIntent recognize(String query, String conversationContext, List<String> historyTags) {
        String safeQuery = query == null ? "" : query;
        String context = conversationContext == null ? "" : conversationContext;

        RetrievalIntent modelIntent = tryDecisionModel(safeQuery, context, historyTags);
        if (modelIntent != null) {
            logger.info("意图识别（决策模型）: query='{}', category={}, confidence={}",
                    safeQuery, modelIntent.category(), modelIntent.confidence());
            return modelIntent;
        }

        RetrievalIntent ruleIntent = ruleBased(safeQuery, context);
        logger.info("意图识别（规则兜底）: query='{}', category={}, confidence={}, reason={}",
                safeQuery, ruleIntent.category(), ruleIntent.confidence(), ruleIntent.reason());
        return ruleIntent;
    }

    private RetrievalIntent tryDecisionModel(String query, String context, List<String> historyTags) {
        if (decisionClient == null || !decisionClient.isConfigured()) {
            return null;
        }
        try {
            AliyunDecisionClient.DecisionResult result = decisionClient.classify(query, context, historyTags);
            if (!result.available()) {
                return null;
            }
            IntentCategory category = IntentCategory.fromCode(result.category());
            if (category == null || result.confidence() < confidenceThreshold) {
                logger.debug("决策模型结果未达阈值或分类无效，回退规则: category={}, confidence={}, threshold={}",
                        result.category(), result.confidence(), confidenceThreshold);
                return null;
            }
            return new RetrievalIntent(category, clamp(result.confidence()), "ALIYUN_DECISION",
                    "决策模型判断为" + category.getLabel());
        } catch (Exception e) {
            logger.warn("决策模型意图识别失败，回退规则: {}", e.getMessage());
            return null;
        }
    }

    private RetrievalIntent ruleBased(String query, String context) {
        int privateHits = countHits(query, PRIVATE_SIGNALS);
        int publicHits = countHits(query, PUBLIC_SIGNALS);
        int mixedHits = countHits(query, MIXED_SIGNALS);
        boolean hasArticleId = ARTICLE_ID.matcher(query).find();
        if (hasArticleId) {
            publicHits += 2;
        }

        if (privateHits > 0 && (publicHits > 0 || mixedHits > 0)) {
            return new RetrievalIntent(IntentCategory.MIXED, confidence(privateHits + publicHits + mixedHits, 0.72),
                    "RULE", "问题同时出现私人案件与法律依据信号，执行混合检索");
        }
        if (privateHits > 0) {
            return new RetrievalIntent(IntentCategory.PRIVATE, confidence(privateHits, 0.68),
                    "RULE", "问题命中私人案件/上传材料信号，仅检索私人案件文档");
        }

        int contextPrivateHits = countHits(context, PRIVATE_SIGNALS);
        boolean followUp = PRONOUN_FOLLOW_UP.matcher(query).matches() || query.length() <= 12;
        if (contextPrivateHits > 0 && followUp) {
            if (publicHits > 0) {
                return new RetrievalIntent(IntentCategory.MIXED, 0.72,
                        "CONTEXT", "当前问题是承接私人案件的追问，且涉及法律依据，执行混合检索");
            }
            return new RetrievalIntent(IntentCategory.PRIVATE, 0.60,
                    "CONTEXT", "当前问题较短且上下文涉及私人案件，沿用私人案件检索");
        }

        if (publicHits > 0) {
            return new RetrievalIntent(IntentCategory.PUBLIC, confidence(publicHits, 0.70),
                    "RULE", "问题命中法典/法条信号，仅检索公开法典");
        }

        return new RetrievalIntent(IntentCategory.PUBLIC, 0.55,
                "RULE", "未发现私人案件信号，默认仅检索公开法典");
    }

    private int countHits(String text, List<String> signals) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String signal : signals) {
            if (normalized.contains(signal.toLowerCase(Locale.ROOT))) {
                hits++;
            }
        }
        return hits;
    }

    private double confidence(int hits, double base) {
        return clamp(base + Math.min(0.20, hits * 0.06));
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
