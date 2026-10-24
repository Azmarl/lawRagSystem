# 私人案件文档支持设计说明

## 目标

在现有“法典上传 → 多级结构感知分块 → 向量化 → BM25 + KNN 混合检索”的基础上，新增用户私人案件文档（案件报告、起诉状、答辩状、证据材料等）的上传、隔离存储、专用解析和混合召回能力。

## 1. 文档类型与上传

上传接口新增 `documentType` 参数：

- `STRUCTURED_LAW`：法律文档，沿用原有的编/章/节/条结构感知解析；
- `PRIVATE_LEGAL`：私人案件文档，走案件文书结构解析。

私人案件文档的服务端约束：

- `isPublic` 必须为 `false`，否则返回 400；
- 组织标签强制为当前用户的私人组织 `PRIVATE_<username>`，忽略客户端传入的其他组织；
- `file_upload.document_type` 记录文档类型，历史数据为 null 时按法律文档处理。

前端上传弹窗 `frontend/src/views/knowledge-base/modules/upload-dialog.vue` 提供“法律文档 / 私人案件文档”单选。选择私人案件时，归属组织自动切到私人组织，组织选择与公开开关均被禁用。

## 2. 解析策略（策略模式）

`com.yizhaoqi.rag.parser.strategy`：

- `DocumentParsingStrategy`：解析策略接口；
- `StructuredLegalParsingStrategy`：`LegalStructureParser` + `TreeAwareChunker`，保留原法典解析流程；
- `PrivateCaseParsingStrategy`：识别“当事人信息 / 公诉机关指控 / 被告人辩称 / 经审理查明 / 证据 / 本院认为 / 裁判结果”等案件要素，输出“段落父块 + 段落内子块”的 `caseSection` 元数据分块；刑事案件分块细节见 [criminal-case-chunking.md](criminal-case-chunking.md)；
- `OverlapTextChunker`：结构化解析失败时的字符/句子重叠保底分块，保证任何非空文档至少产生一个 Chunk；
- `DocumentParsingStrategyFactory`：按用户选择的 `DocumentType` 选择策略。

`ParseService` 不再通过文本内容猜测文档类型：只要上传时选择了类型，就使用对应策略；只有历史数据/未传类型时才自动识别。

## 3. 存储隔离

| 层级 | 公开法典 | 私人案件 |
| --- | --- | --- |
| MinIO | `minio.bucketName` + `chunks/` / `merged/` | `minio.privateBucketName` + `private/{userId}/chunks/` / `private/{userId}/merged/` |
| MySQL 分块 | `document_vectors` | `private_document_vectors` |
| Elasticsearch | `knowledge_base` | `knowledge_private` |

补充说明：

- 私人分片的上传键使用 `sha256(fileMd5 + userId + documentType)` 的前 32 位，避免不同用户上传相同文件时在 `chunk_info` 的唯一约束上互相覆盖；
- `file_upload` 保存 `storage_bucket` / `storage_object_key`，读取和删除时按记录路由，不依赖硬编码 `uploads` bucket；
- 私人案件文档永远不进入公开/组织权限分支，检索权限过滤中只有 `userId` 分支可以命中私人文档。

## 4. 检索前意图识别

`IntentRecognitionService` 在召回前判断：

- `PUBLIC`：仅公开法典；
- `PRIVATE`：仅私人案件；
- `MIXED`：法典 + 私人案件。

优先调用 Jev / System-One 决策模型（国内推荐腾讯云 EdgeOne Makers 的 `@makers/jev`，也可用 TypeSafe 官方 API；接入说明见 [decision-model-jev.md](decision-model-jev.md)）。模型未配置、超时、401、结果无效或低于阈值时，使用关键词/上下文规则兜底。`ChatHandler` 会把最近几轮对话摘要传入，用于“那它的法律依据呢”这类指代消解。

召回仍复用原来的 BM25 + KNN 双路召回与 Adaptive α 融合，只是按意图限定索引范围；`MIXED` 时分别召回两个索引后合并，保证混合提问能同时拿到法典和案件材料。

## 5. 前端置信度展示

检索溯源抽屉新增：

- 检索意图标签与意图置信度；
- 法典召回置信度 / 私人案件召回置信度（仅在本次提问涉及私人案件时展示）；
- 私人案件未召回提示；
- 每个知识块标注“法律法规 / 私人案件”。

`ChatHandler` 会把 `documentType`、`sourceCorpus`、`intentCategory`、`intentConfidence`、`intentReason` 写入引用映射与检索结果，重连/历史加载后仍可恢复展示。

## 6. 主要配置

```yaml
minio:
  bucketName: uploads
  privateBucketName: private-uploads

file:
  parsing:
    private-chunk-size: 512
    private-overlap-size: 100

aliyun:
  decision:
    routing:
      confidence-threshold: 0.85
```

`MINIO_PRIVATE_BUCKET_NAME` 为空或与公开 bucket 相同时，私人对象仍会使用 `private/{userId}/` 前缀做二次隔离。

## 7. 验证

```bash
mvn -q -DskipTests compile
mvn -q -DskipTests test-compile
mvn -q -Dtest=IntentRecognitionServiceTest,OverlapTextChunkerTest,PrivateCaseParsingStrategyTest,DocumentTypeTest test
```

新增单元测试覆盖：文档类型归一化、意图识别规则/上下文兜底、案件段落解析、字符重叠保底分块。
