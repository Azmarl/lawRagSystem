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

    public AliyunDecisionClient(
            @Value("${aliyun.decision.api.url:https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/systemone}") String apiUrl,
            @Value("${aliyun.decision.api.key:}") String apiKey,
            @Value("${aliyun.decision.api.model:}") String model,
            @Value("${aliyun.decision.api.timeout-ms:500}") long timeoutMs,
            ObjectMapper objectMapper) {
        WebClient.Builder builder = WebClient.builder().baseUrl(apiUrl);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        this.webClient = builder.build();
        this.model = model;
        this.timeout = Duration.ofMillis(Math.max(100, timeoutMs));
        this.objectMapper = objectMapper;
    }

    public DecisionResult classify(String query, String pageContext, List<String> historyTags) {
        if (model == null || model.isBlank()) {
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
                        logger.warn("阿里云决策模型调用失败: {}", error.getMessage());
                        return Mono.empty();
                    })
                    .block();
            if (body == null || body.isBlank()) {
                return DecisionResult.unavailable();
            }
            JsonNode root = objectMapper.readTree(body);
            JsonNode decision = root.path("answers").path("search_scope");
            String category = decision.path("choice").asText("").toUpperCase();
            double confidence = decision.path("confidence").asDouble(0.0);
            if (!SetOfCategories.contains(category)) {
                return DecisionResult.unavailable();
            }
            return new DecisionResult(category, Math.max(0.0, Math.min(1.0, confidence)), true);
        } catch (Exception error) {
            logger.warn("解析阿里云决策模型结果失败: {}", error.getMessage());
            return DecisionResult.unavailable();
        }
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
