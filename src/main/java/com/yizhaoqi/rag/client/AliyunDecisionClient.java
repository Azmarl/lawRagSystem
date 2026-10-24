package com.yizhaoqi.rag.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AliyunDecisionClient {

    private static final Logger logger = LoggerFactory.getLogger(AliyunDecisionClient.class);

    private final WebClient webClient;
    private final String model;
    private final Duration timeout;
    private final ObjectMapper objectMapper;
    private final boolean configured;

    public AliyunDecisionClient(
            @Value("${aliyun.decision.api.url:https://ai-gateway.edgeone.link/v1/systemone}") String apiUrl,
            @Value("${aliyun.decision.api.key:}") String apiKey,
            @Value("${aliyun.decision.api.model:@makers/jev}") String model,
            @Value("${aliyun.decision.api.timeout-ms:2000}") long timeoutMs,
            ObjectMapper objectMapper) {
        WebClient.Builder builder = WebClient.builder().baseUrl(apiUrl);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        this.webClient = builder.build();
        this.model = model;
        this.configured = apiKey != null && !apiKey.isBlank() && model != null && !model.isBlank();
        this.timeout = Duration.ofMillis(Math.max(100, timeoutMs));
        this.objectMapper = objectMapper;
        logger.info("决策模型配置: enabled={}, endpoint={}, model={}", configured, apiUrl, model);
    }

    /**
     * 未配置 API Key 时不发起网络调用，由调用方走规则兜底。
     */
    public boolean isConfigured() {
        return configured;
    }

    public DecisionResult classify(String query, String pageContext, List<String> historyTags) {
        if (!configured) {
            return DecisionResult.unavailable();
        }

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("query", safe(query));
        state.put("page_context", safe(pageContext));
        state.put("history_tags", historyTags == null ? List.of() : historyTags);

        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put("PUBLIC", "用户只询问公共法律法规、法条、条文或司法解释，不涉及自己的上传材料");
        criteria.put("PRIVATE", "用户只询问自己上传的合同、报告、诉状、合规文件或其他私有材料");
        criteria.put("BOTH", "用户需要将公共法律依据与自己上传的私有材料进行对照、分析或联动检索");

        Map<String, Object> questions = Map.of(
            "search_scope", Map.of(
                "type", "choice",
                "instructions", "根据 state 中的用户问题判断本次检索应访问哪个知识域",
                "criteria", criteria
            )
        );
        Map<String, Object> request = Map.of(
            "state", state,
            "model", model,
            "questions", questions
        );

        try {
            String body = webClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(timeout)
                    .onErrorResume(error -> {
                        logger.warn("决策模型调用失败: {}", error.getMessage());
                        return Mono.empty();
                    })
                    .block();
            if (body == null || body.isBlank()) {
                return DecisionResult.unavailable();
            }
            JsonNode root = objectMapper.readTree(body);
            JsonNode decision = firstPresent(
                    root.path("answers").path("search_scope"),
                    root.path("answer").path("search_scope"),
                    root.path("data").path("answers").path("search_scope"),
                    root.path("data"),
                    root.path("result")
            );
            String category = firstText(decision,
                    "choice", "category", "search_scope", "answer", "value").toUpperCase();
            double confidence = firstDouble(decision, 0.9, "confidence", "score", "probability");
            if (!SetOfCategories.contains(category)) {
                return DecisionResult.unavailable();
            }
            return new DecisionResult(category, Math.max(0.0, Math.min(1.0, confidence)), true);
        } catch (Exception error) {
            logger.warn("解析决策模型结果失败: {}", error.getMessage());
            return DecisionResult.unavailable();
        }
    }

    private JsonNode firstPresent(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && !node.isMissingNode() && !node.isNull() && !node.isEmpty()) {
                return node;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private String firstText(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode()) {
            return "";
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText().trim();
            }
        }
        return node.isTextual() ? node.asText().trim() : "";
    }

    private double firstDouble(JsonNode node, double fallback, String... fields) {
        if (node == null || node.isMissingNode()) {
            return fallback;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isNumber()) {
                return value.asDouble();
            }
            if (value.isTextual()) {
                try {
                    return Double.parseDouble(value.asText());
                } catch (NumberFormatException ignored) {
                    // 继续尝试下一个字段
                }
            }
        }
        return fallback;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record DecisionResult(String category, double confidence, boolean available) {
        public static DecisionResult unavailable() {
            return new DecisionResult("", 0.0, false);
        }
    }

    private static final class SetOfCategories {
        private static boolean contains(String value) {
            return "PUBLIC".equals(value) || "PRIVATE".equals(value) || "BOTH".equals(value);
        }
    }
}
