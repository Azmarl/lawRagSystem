package com.yizhaoqi.rag.config;

import co.elastic.clients.transport.endpoints.BooleanResponse;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.elasticsearch.indices.GetMappingRequest;
import co.elastic.clients.elasticsearch.indices.GetMappingResponse;
import co.elastic.clients.elasticsearch.indices.PutMappingRequest;
import org.apache.http.ConnectionClosedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.io.StringReader;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// 确保在应用启动阶段优先初始化 Elasticsearch 索引
@Order(2)
@Component
@ConditionalOnProperty(name = "elasticsearch.init.enabled", havingValue = "true", matchIfMissing = true)
public class EsIndexInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(EsIndexInitializer.class);

    @Autowired
    private ElasticsearchClient esClient;

    @Value("classpath:es-mappings/knowledge_base.json") // 加载 JSON 文件
    private org.springframework.core.io.Resource mappingResource;

    @Value("${elasticsearch.host}")
    private String host;

    @Value("${elasticsearch.port}")
    private int port;

    @Value("${elasticsearch.scheme:https}")
    private String scheme;

    @Value("${elasticsearch.username:elastic}")
    private String username;

    @Override
    public void run(String... args) throws Exception {
        try {
            logger.info("正在初始化索引 'knowledge_base' / 'knowledge_private'... endpoint={}://{}:{}, username={}",
                    scheme, host, port, maskUsername(username));
            initializeIndex("knowledge_base");
            initializeIndex("knowledge_private");
        } catch (Exception exception) {
            // 特别处理连接关闭异常，尝试重新连接
            if (exception instanceof ConnectionClosedException || (exception.getCause() != null && exception.getCause() instanceof ConnectionClosedException)) {
                logger.error("Elasticsearch连接已关闭，等待5秒后重试...");
                try {
                    Thread.sleep(5000); // 等待5秒后重试
                    // 重新尝试初始化索引
                    initializeIndex("knowledge_base");
                    initializeIndex("knowledge_private");
                } catch (Exception retryException) {
                    String diagnostic = buildDiagnosticMessage(retryException);
                    logger.error("重试初始化索引失败。{}", diagnostic, retryException);
                    throw new RuntimeException("初始化索引失败，重试也未能成功。" + diagnostic, retryException);
                }
            } else {
                String diagnostic = buildDiagnosticMessage(exception);
                logger.error("初始化索引失败。{}", diagnostic, exception);
                throw new RuntimeException("初始化索引失败。" + diagnostic, exception);
            }
        }
    }

    /**
     * 初始化索引的核心逻辑。
     * 如果索引不存在则创建；如果已存在则比较 _meta.version，
     * 仅当本地版本高于 ES 中版本时才更新 mapping。
     */
    private void initializeIndex(String indexName) throws Exception {
        BooleanResponse existsResponse = esClient.indices().exists(ExistsRequest.of(e -> e.index(indexName)));
        if (!existsResponse.value()) {
            createIndex(indexName);
        } else {
            int localVersion = getLocalMappingVersion();
            int esVersion = getEsMappingVersion(indexName);
            if (localVersion > esVersion) {
                logger.info("索引 '{}' mapping 版本变更 (ES:{} → 本地:{})，执行增量更新...",
                        indexName, esVersion, localVersion);
                tryUpdateMapping(indexName);
            } else {
                logger.info("索引 '{}' mapping 已是最新版本 (ES:{}，本地:{})，跳过更新。",
                        indexName, esVersion, localVersion);
            }
        }
    }

    /**
     * 从本地 mapping JSON 文件中读取 _meta.version。
     * 若无法解析则默认返回 0（始终触发更新，保证向前兼容）。
     */
    private int getLocalMappingVersion() {
        try (var inputStream = mappingResource.getInputStream()) {
            String mappingJson = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> root = mapper.readValue(mappingJson, Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> mappings = (Map<String, Object>) root.get("mappings");
            if (mappings == null) return 0;
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) mappings.get("_meta");
            if (meta == null) return 0;
            Object version = meta.get("version");
            if (version instanceof Number) {
                return ((Number) version).intValue();
            }
            return 0;
        } catch (Exception e) {
            logger.warn("无法读取本地 mapping 版本号，将按需触发更新: {}", safeMessage(e));
            return 0;
        }
    }

    /**
     * 查询 ES 中指定索引的 _meta.version。
     * 若索引无 _meta 或未找到 version，则默认返回 0。
     */
    private int getEsMappingVersion(String indexName) {
        try {
            GetMappingResponse response = esClient.indices()
                    .getMapping(GetMappingRequest.of(g -> g.index(indexName)));
            var indexMappings = response.result();
            if (indexMappings.isEmpty()) return 0;
            // 取第一个索引的 mapping
            var mapping = indexMappings.values().iterator().next().mappings();
            var meta = mapping.meta();
            if (meta == null) return 0;
            Object version = meta.get("version");
            if (version instanceof JsonData) {
                return ((JsonData) version).to(Integer.class);
            }
            return 0;
        } catch (Exception e) {
            logger.warn("无法读取 ES 中的 mapping 版本号，将按需触发更新: {}", safeMessage(e));
            return 0;
        }
    }

    /**
     * 尝试在已有索引上增量添加新字段的 mapping。
     * 使用 PutMapping API 仅添加新字段，不会影响已有字段或数据。
     */
    private void tryUpdateMapping(String indexName) {
        try {
            String mappingJson;
            try (var inputStream = mappingResource.getInputStream()) {
                mappingJson = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }

            // 从完整 mapping JSON 中提取 properties 部分，
            // 用 withJson 方式提交（与 createIndex 使用相同模式）
            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> root = mapper.readValue(mappingJson, Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> mappings = (Map<String, Object>) root.get("mappings");
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) mappings.get("properties");

            // 构造只含 properties 的 JSON body，通过 withJson 传给 PutMapping
            String propertiesJson = mapper.writeValueAsString(Map.of("properties", properties));

            PutMappingRequest putMappingRequest = PutMappingRequest.of(p -> p
                    .index(indexName)
                    .withJson(new StringReader(propertiesJson))
            );

            esClient.indices().putMapping(putMappingRequest);
            logger.info("索引 '{}' mapping 更新成功（新增文档类型/案件段落字段）", indexName);
        } catch (Exception e) {
            String msg = safeMessage(e);
            logger.warn("索引 '{}' mapping 增量更新失败: {}。" +
                            "如果是因为 mapping 参数冲突，请手动执行以下步骤清空 ES 数据卷后重启：" +
                            "\n  1. docker-compose -f data/docker-compose.yaml down -v" +
                            "\n  2. docker-compose -f data/docker-compose.yaml up -d" +
                            "\n  3. 重新上传文档",
                    indexName, msg);
        }
    }

    /**
     * 创建索引
     * @throws Exception
     */
    private void createIndex(String indexName) throws Exception {
        // 读取 JSON 文件内容，使用 InputStream 方式支持 JAR 包内资源
        String mappingJson;
        try (var inputStream = mappingResource.getInputStream()) {
            mappingJson = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
        // 创建索引并应用映射
        CreateIndexRequest createIndexRequest = CreateIndexRequest.of(c -> c
                .index(indexName)
                .withJson(new StringReader(mappingJson))
        );
        esClient.indices().create(createIndexRequest);
        logger.info("索引 '{}' 已创建", indexName);
    }

    private String buildDiagnosticMessage(Exception exception) {
        Throwable rootCause = getRootCause(exception);
        String rootMessage = safeMessage(rootCause);
        String normalizedMessage = rootMessage.toLowerCase(Locale.ROOT);
        String endpoint = scheme + "://" + host + ":" + port;
        List<String> hints = new ArrayList<>();

        hints.add("ES地址=" + endpoint);

        if (isConnectionProblem(rootCause, normalizedMessage)) {
            hints.add("当前看起来是连接失败，请先确认 Elasticsearch 已启动，并且 " + endpoint + " 能访问");
        }

        if (isSslMismatch(normalizedMessage)) {
            hints.add("当前更像是 HTTP/HTTPS 协议不匹配，请核对 ELASTICSEARCH_SCHEME 与实际 ES 配置");
        }

        if (isAuthenticationProblem(normalizedMessage)) {
            hints.add("当前更像是账号或密码不正确，请核对 ELASTICSEARCH_USERNAME / ELASTICSEARCH_PASSWORD");
        }

        if (normalizedMessage.contains("ik_max_word") || normalizedMessage.contains("ik_smart")) {
            hints.add("当前索引 mapping 依赖 IK 分词器，请确认 ES 已安装 analysis-ik 插件");
        }

        if (normalizedMessage.contains("dense_vector") && normalizedMessage.contains("dims")) {
            hints.add("当前更像是向量字段维度不匹配，请确认 embedding.dimension 与索引 mapping 中的 dims 一致");
        }

        if (hints.size() == 1) {
            hints.add("请查看根因异常后再排查 ES 协议、端口、认证和 mapping 配置");
        }

        return " 根因类型=" + rootCause.getClass().getSimpleName()
                + "，根因信息=" + rootMessage
                + "。排查建议：" + String.join("；", hints);
    }

    private Throwable getRootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private boolean isConnectionProblem(Throwable rootCause, String normalizedMessage) {
        return rootCause instanceof ConnectException
                || normalizedMessage.contains("connection refused")
                || normalizedMessage.contains("connect timed out")
                || normalizedMessage.contains("connection timed out")
                || normalizedMessage.contains("failed to connect")
                || normalizedMessage.contains("no such host")
                || normalizedMessage.contains("unknownhost")
                || normalizedMessage.contains("no reachable node")
                || normalizedMessage.contains("connection reset");
    }

    private boolean isSslMismatch(String normalizedMessage) {
        return normalizedMessage.contains("pkix")
                || normalizedMessage.contains("ssl")
                || normalizedMessage.contains("tls")
                || normalizedMessage.contains("handshake")
                || normalizedMessage.contains("plaintext connection")
                || normalizedMessage.contains("unrecognized ssl message");
    }

    private boolean isAuthenticationProblem(String normalizedMessage) {
        return normalizedMessage.contains("security_exception")
                || normalizedMessage.contains("authentication")
                || normalizedMessage.contains("unauthorized")
                || normalizedMessage.contains("status line [http/1.1 401")
                || normalizedMessage.contains("status line [http/1.1 403");
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return (message == null || message.isBlank()) ? "<无异常消息>" : message;
    }

    private String maskUsername(String rawUsername) {
        if (rawUsername == null || rawUsername.isBlank()) {
            return "<empty>";
        }
        if (rawUsername.length() <= 2) {
            return rawUsername.charAt(0) + "*";
        }
        return rawUsername.charAt(0) + "***" + rawUsername.charAt(rawUsername.length() - 1);
    }
}
