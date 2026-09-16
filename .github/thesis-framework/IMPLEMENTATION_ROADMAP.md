# 毕设工程实现路线图（法律领域 RAG 精度优化版：树状多级分块 + 向量-倒排混合检索）

> 本文件用于把《论文框架》中的创新点（树状多级分块、Adaptive α 向量-倒排混合检索、Legal-RAG-Bench 评测基准）映射到本项目的真实工程模块、类与接口。所有"验收标准"可作为答辩前自测 checklist。
>
> 工程环境：Java 17 + Spring Boot 3 + MySQL 8 + Redis 7 + Elasticsearch 8.10 + Kafka 3.2 + Vue 3
> 实验数据：`d:\VsCode\RagSystem\data\中华人民共和国民法典 - 中华人民共和国最高人民法院.pdf`、`d:\VsCode\RagSystem\data\中华人民共和国刑法_20201226.docx`

---

## 🏗️ 总体模块分解

```
Phase 1 · 法律文档解析基线（论文第 3 章数据画像的工程支撑）
Phase 2 · 树状多级分块器（论文第 4 章核心创新）
Phase 3 · 向量-倒排混合检索 + 自适应权重（论文第 5 章核心创新）
Phase 4 · 检索溯源面板 + 分块可视化（论文第 7 章答辩亮点）
Phase 5 · Legal-RAG-Bench 评测与基线实验（论文第 6 章量化产出）
```

---

## Phase 1 · 法律文档解析基线（支撑第 3 章数据画像）

**目标**：让系统能完整解析《民法典》.pdf 与《刑法》.docx，并输出"层级分布、条文引用密度、法律术语密度"的统计数据。

### 模块 1.1：法律文档数据源（上传双路径）

**位置**
- `src/main/java/com/yizhaoqi/rag/service/DocumentService.java`（已有）
- 新增：`src/main/java/com/yizhaoqi/rag/service/legal/LegalDocumentIngestService.java`

**功能**
1. **手动上传路径**：通过 `UploadController#uploadChunk`（已有）上传 `.pdf/.docx/txt` 等法律文档
2. **批量导入路径**（可选加分项）：支持本地目录批量导入（命令行 CLI 或后端一个一次性接口即可）
3. **文件统一落点**：MinIO 存原始文件；`document_vectors` 表（MySQL）存 chunk 文本；Elasticsearch 索引存文本 + 向量

**验收标准**
- [ ] 支持 `pdf / docx / md / txt / html` 至少 5 种格式上传成功
- [ ] MySQL 中每条 chunk 可反查原始文档、文件 MD5、所属编/章/节/条

### 模块 1.2：法律结构解析器 —— 识别"编→章→节→条→款"

**位置**
- 新增：`src/main/java/com/yizhaoqi/rag/parser/LegalStructureParser.java`
- 相关：`ParseService`（已有），会把此解析器作为内部第一步

**职责**
- 逐行扫描解析后的法律文本，维护"层级堆栈"状态机
- 把文档切分为以下 Block 类型（输出 `Tree<LegalBlock>`）
  - `PART(level=1, text)`："第X编"、"总则"、"分则"
  - `CHAPTER(level=2, text)`："第X章"
  - `SECTION(level=3, text)`："第X节"
  - `ARTICLE(level=4, text)`："第X条"（核心切分单位）
  - `PARAGRAPH(level=5, text)`："（一）"、"(1)" 等款项
- 正则识别规则（论文第 4.2 节伪代码对应的 Java 实现）：
  - Part：`^第[一二三四五六七八九十]+编` / `^总则` / `^分则`
  - Chapter：`^第[一二三四五六七八九十百]+章`
  - Section：`^第[一二三四五六七八九十]+节`
  - Article：`^第[0-9零一二三四五六七八九十百千]+条`
  - Paragraph：`^（[一二三四五六七八九十]+）` / `^\(\d+\)`

**伪代码骨架（Java）**
```java
public LegalNode parse(String text) {
    Deque<LegalNode> stack = new ArrayDeque<>();
    LegalNode root = new RootNode();
    stack.push(root);

    for (String line : text.split("\\R")) {
        if (matchesPart(line)) {
            flushTo(stack, new PartNode(line));
        } else if (matchesChapter(line)) {
            flushTo(stack, new ChapterNode(line));
        } else if (matchesSection(line)) {
            flushTo(stack, new SectionNode(line));
        } else if (matchesArticle(line)) {
            flushTo(stack, new ArticleNode(line));
        } else if (matchesParagraph(line) && stack.peek() instanceof ArticleNode) {
            stack.push(new ParagraphNode(line));
        } else {
            if (!stack.isEmpty()) {
                stack.peek().appendContent(line);
            }
        }
    }
    return root;
}
```

**验收标准**
- [ ] 正确识别《民法典》全部 7 编 → 章 → 节 → 条的层级，代码中不与文本混杂
- [ ] 正确识别《刑法》总则 / 分则 → 章 → 节 → 条的层级
- [ ] 单元测试：`LegalStructureParserTest` —— 覆盖嵌套层级、"第X条"紧跟内容、空行等边界 case

### 模块 1.3：法律语料统计服务（支撑论文第 3.1 节数据画像）

**位置**
- 新增：`src/main/java/com/yizhaoqi/rag/service/legal/LegalCorpusStatsService.java`
- 接口：`GET /api/v1/legal/stats`（只对开发/管理员可见，论文用）

**功能**
- 层级分布统计：各编 / 章 / 节 / 条的数量（饼图 / 柱状图数据）
- **法律术语密度**：统计"合同、违约、侵权、民事、刑事、自首、正当防卫、知识产权"等法律术语的命中频次（可使用预定义法律术语词典）
- **条文引用密度**：统计 "第X条"、"参照"、"适用"、"前款"、"本法" 等引用标记的频次分布
- 条文平均长度（token 数）分布
- 输出 CSV + 直方图数据（前端可直接画图）

**法律术语词典（最小可运行集）**
```
合同, 违约, 履行, 解除, 撤销, 无效, 效力, 代理, 物权, 债权, 侵权, 责任, 赔偿, 损失, 过错,
民事, 刑事, 刑法, 罪名, 犯罪, 量刑, 自首, 立功, 累犯, 正当防卫, 紧急避险, 故意, 过失,
知识产权, 著作权, 专利, 商标, 婚姻, 继承, 收养, 监护, 遗嘱, 时效, 期间, 管辖, 诉讼
```

**验收标准**
- [ ] 统计接口 ≤ 30 秒完成《民法典》+《刑法》两部文档的扫描
- [ ] 输出 CSV 与论文第 3 章统计口径一致

---

## Phase 2 · 树状多级分块器（支撑论文第 4 章）

**目标**：实现 `TreeAwareChunker`，其输出的 chunk 满足：条文边界完整、保留"编→章→节"层级上下文、生成条/节/章三级粒度。

### 模块 2.1：条级 Chunk 生成器

**位置**
- 新增：`src/main/java/com/yizhaoqi/rag/chunker/ArticleLevelChunker.java`

**策略**
- 每个 `ARTICLE` 节点作为一个 Chunk（条级）
- 注入父级标题前缀：`【第一编 总则 · 第五章 民事权利】第123条 民事主体依法享有知识产权...`
- 若条文过长（> 512 tokens），按款/项切分，但保留完整条文编号
- 不做"调用 Embedding 的句子相似度合并"以避免依赖外部 API 调用

**验收标准**
- [ ] 生成的条级 chunk 平均长度在 `[100, 800]` tokens 区间
- [ ] 不会把"第X条"粗暴从中间切断（通过测试用例保证）
- [ ] 每个条级 chunk 都包含正确的 `part/chapter/section/article_id` 元数据

### 模块 2.2：节级 / 章级 Chunk 生成器（多级粒度）

**位置**
- 新增：`src/main/java/com/yizhaoqi/rag/chunker/HierarchyLevelChunker.java`

**策略**
- **节级 Chunk**（Section-level）：同一 Section 下的所有条文合并为 1 个 Chunk（若超过 1024 tokens 则按等长切分）
- **章级 Chunk**（Chapter-level）：同一 Chapter 下的所有条文合并为 1 个 Chunk（用于粗粒度召回）
- 保持 chunk 类型标记：`chunk_level = "article" | "section" | "chapter"`

**验收标准**
- [ ] 对《民法典》合同编，能生成节级 chunk，且节级 chunk 内部条文顺序正确
- [ ] 短章节（< 200 tokens）整体保留 1 个章级 chunk

### 模块 2.3：综合分块器（TreeAwareChunker）

**位置**
- 新增：`src/main/java/com/yizhaoqi/rag/chunker/TreeAwareChunker.java`
- 集成到：`ParseService#parseAndSave`（已有方法） —— **把原来的 Tika 流式解析替换为"先 LegalStructureParser，再 TreeAwareChunker"**

**算法主流程**
```java
public List<Chunk> chunk(LegalNode root, int maxTokens, int overlapTokens) {
    List<Chunk> result = new ArrayList<>();

    // Step 1: 生成条级 Chunk
    for (ArticleNode article : root.collectArticles()) {
        Chunk articleChunk = buildArticleChunk(article, maxTokens, overlapTokens);
        result.add(articleChunk);
    }

    // Step 2: 生成节级 Chunk（每 n 个 Section 合并）
    for (SectionNode section : root.collectSections()) {
        if (section.getArticles().size() >= 3) {
            result.add(buildSectionChunk(section, maxTokens, overlapTokens));
        }
    }

    // Step 3: 生成章级 Chunk（粗粒度召回补充）
    for (ChapterNode chapter : root.collectChapters()) {
        if (chapter.getArticles().size() >= 10) {
            result.add(buildChapterChunk(chapter, maxTokens, overlapTokens));
        }
    }

    // Step 4: 元数据打标
    for (Chunk c : result) {
        c.setLegalTermDensity(computeLegalTermDensity(c.getTextContent()));
        c.setReferenceDensity(computeReferenceDensity(c.getTextContent()));
        c.setArticleIdDensity(computeArticleIdDensity(c.getTextContent()));
    }
    return result;
}
```

**Chunk 的数据结构（写入 ES + MySQL）**
```java
public class Chunk {
    String fileMd5;           // 对应 FileUpload.fileMd5
    String sourceDoc;         // "中华人民共和国民法典" 或 "中华人民共和国刑法"
    int chunkIndex;           // 在本文件中的序号
    String chunkLevel;        // "article" | "section" | "chapter"
    String partName;          // "第一编 总则"
    String chapterName;       // "第五章 民事权利"
    String sectionName;       // （如有）
    String articleId;         // "第123条"（仅 article 有）
    String textContent;       // 文本内容（用于 Embedding），含层级前缀
    int tokenCount;           // 估算 token 数
    double legalTermDensity;  // 法律术语命中数 / 总 token 数
    double referenceDensity;  // 引用标记（第X条、参照、适用等）密度
    double articleIdDensity;  // 条文号匹配密度（用于检索端）
}
```

**验收标准**
- [ ] 能跑通：上传《民法典》.pdf → 解析 → 产出 chunks → 写入 `document_vectors` 表 → ES 索引成功
- [ ] 能跑通：上传《刑法》.docx → 同上
- [ ] 与 Baseline 分块器（固定长度 512）做 50 条文人工打分对比，本方案平均分 ≥ 4/5，Baseline ≤ 3/5
- [ ] 产生的 chunk 数比 Baseline 多 20–50%（因多级粒度生成）但条文完整性显著更好

### 模块 2.4：Chunk 元数据打标 —— 法律术语密度 + 条文号密度

**位置**
- 新增：`src/main/java/com/yizhaoqi/rag/chunker/LegalTermScorer.java`

**规则**
- `第[0-9零一二三四五六七八九十百千]+条` 命中计数 → `article_id_density = count / tokenCount`
- 法律术语词典命中计数 → `legal_term_density = count / tokenCount`
- `参照|适用|前款|本法|本条|前述|上述` 等引用标记命中计数 → `reference_density = count / tokenCount`
- 这些字段会在 Phase 3 的 `Adaptive α` 里被查询端使用

---

## Phase 3 · 向量-倒排混合检索 + 自适应权重（支撑论文第 5 章）

**目标**：实现 Elasticsearch 的 BM25（倒排）+ KNN（向量）两路检索，并在应用层做 Adaptive α 融合。

### 模块 3.1：Elasticsearch 索引 Mapping

**位置**
- 相关：当前 `document_vectors` 表 / ES 索引。建议索引名 `legal-chunks-*` 或沿用现有索引名，确保 `dense_vector` 字段存在。
- 关键字段：

```json
{
  "properties": {
    "file_md5":            { "type": "keyword" },
    "source_doc":          { "type": "keyword" },
    "chunk_index":         { "type": "integer" },
    "chunk_level":         { "type": "keyword" },
    "part_name":           { "type": "text", "analyzer": "ik_max_word", "fields": { "raw": { "type": "keyword" } } },
    "chapter_name":        { "type": "text", "analyzer": "ik_max_word", "fields": { "raw": { "type": "keyword" } } },
    "section_name":        { "type": "text", "analyzer": "ik_max_word" },
    "article_id":          { "type": "keyword" },
    "text_content":        { "type": "text", "analyzer": "ik_max_word" },
    "token_count":         { "type": "integer" },
    "legal_term_density":  { "type": "float" },
    "reference_density":   { "type": "float" },
    "article_id_density":  { "type": "float" },
    "org_tag":             { "type": "keyword" },
    "is_public":           { "type": "boolean" },
    "user_id":             { "type": "keyword" },
    "vector":              { "type": "dense_vector", "dims": 1024, "index": true, "similarity": "dot_product" }
  }
}
```

**验收标准**
- [ ] 索引通过 PUT 创建，mapping 经 `GET legal-chunks/_mapping` 验证正确
- [ ] `article_id` 使用 `keyword` 类型，可被 term 查询精确命中
- [ ] `vector` 字段 `index: true`，能走 ES 原生 KNN

### 模块 3.2：HybridSearchService（核心）

**位置**
- 新增：`src/main/java/com/yizhaoqi/rag/search/HybridSearchService.java`

**接口签名**
```java
public HybridSearchResponse search(String queryText,
                                   Set<String> allowedOrgTags,
                                   int topK,
                                   Double alphaOverride /* 传入 null 表示 Adaptive */);
```

**内部流程（对应论文第 5.2 节公式）**
```
1) 计算 query.article_id_density + query.legal_term_density
   —— 复用 LegalTermScorer 的正则与术语词典

2) 计算 α = sigmoid(w1 * article_id_density
                    + w2 * legal_term_density
                    + bias)
   —— w1, w2, bias 由离线 grid search 得到；离线可写成 Python 脚本 / Java 工具类

3) 并行执行两路检索：
   a) BM25（倒排）：
      - term 查询 `article_id`（精确匹配"第123条"这类条文号）
      - match 查询 `text_content` + `parent_section` 全文
      - boost 命中 `article_id` 的文档
      - 返回 top-3*K 候选，分数归一化 [0,1]

   b) KNN（向量）：
      - 使用相同 query_text 的 embedding，对 `vector` 字段做 knn
      - 返回 top-3*K 候选，分数归一化 [0,1]

4) 合并两个有序列表：
   score_hybrid(d) = α * bm25_norm(d) + (1-α) * knn_norm(d)
   取 top-K 作为最终排序

5) 把 α、bm25_norm、knn_norm、snippet、hit_reason 原样返回，
   供前端【检索溯源面板】渲染。
```

**ES Query 示例**
```json
// 路径 A：倒排检索
{
  "query": {
    "bool": {
      "filter": [ { "terms": { "org_tag": [ ... ] } },
                  { "term":  { "is_public": true } } ],
      "should": [
        { "term": { "article_id": "第123条" } },
        { "multi_match": {
            "query": "知识产权",
            "fields": ["text_content^3", "part_name^1", "chapter_name^1"]
        } }
      ]
    }
  },
  "size": 50
}

// 路径 B：向量检索
{
  "knn": {
    "field": "vector",
    "query_vector": [...],
    "k": 50,
    "num_candidates": 200,
    "filter": { "bool": { "filter": [ ...同上面 filter... ] } }
  },
  "size": 50
}
```

> 最简单的工程方案是：**分别发两次请求**（BM25 一次、knn 一次），在应用层按分数归一化后线性融合。实现简单、可解释性强、论文图表更清晰。

**验收标准**
- [ ] 能在 ≤ 1 秒内完成一次查询（含 ES BM25 + KNN + 融合），top-K = 10
- [ ] 对 `《民法典》第123条的内容是什么？`（条文号密度高），α 自动调大（α > 0.6），BM25 贡献主导
- [ ] 对 `什么情况下可以解除合同？`（意图型、条文号密度低），α 自动调小（α < 0.5），向量检索贡献主导
- [ ] 权限过滤正常：无 org_tag 的查询返回空（除非文档 is_public = true）

### 模块 3.3：重排器（可选，论文 ablation）

**位置**
- 新增：`src/main/java/com/yizhaoqi/rag/search/RerankService.java`
- 对 top-20 调用 Cross-Encoder（bge-reranker-v2-m3）或 LLM 打分，输出 top-5
- 打分融合：`rerank_score = β * ce_score + (1-β) * hybrid_score`，β 固定 0.5

**验收标准**
- [ ] NDCG@5 比不重排提升 ≥ 2%（如果不显著，论文中说明"在本数据规模下 Cross-Encoder 收益有限"即可）

---

## Phase 4 · 检索溯源面板 + 分块可视化（答辩亮点，支撑论文第 7 章）

### 模块 4.1：前端检索溯源面板

**位置**
- 新增文件（或集成到聊天页底部 Tab）：
  - `frontend/src/views/knowledge-base/modules/retrieval-trace-panel.vue`
- 后端接口：`HybridSearchService#search` 已返回需要的字段（chunk_id / source_doc / 编-章-节 / 条文号 / bm25_norm / knn_norm / α / snippet / hit_reason）

**UI 要点**
1. **顶部**：问题文本框 + 搜索按钮
2. **中部**：LLM 生成的答案（流式渲染，沿用现有聊天页逻辑）
3. **底部（核心论文创新点可视化）**：
   - 一张卡 = 一个命中 chunk
   - 卡内展示：
     - `chunk_id` · `来源法典（民法典/刑法）` · `【第一编 总则 · 第五章 民事权利】第123条`
     - 三个 badge：`BM25=0.85`、`KNN=0.62`、`α=0.72`
     - snippet 高亮（用 query 中的法律术语 / 条文号做高亮）
     - `hit_reason`："精确条文号（第123条）匹配 + 语义相关（知识产权、民事权利）"
4. **对比模式**：可切换 `Baseline-BM25` / `Baseline-KNN` / `Proposed-Hybrid` 三种模式查看同一 query 的召回差异 —— **答辩时来回切换会非常直观**

**验收标准**
- [ ] 提问 3 个典型法律问题，都能正常展示 ≥ 3 个溯源卡片
- [ ] 卡片上的 `α` 值随 query 中条文号密度变化而变化（可肉眼观察到）

### 模块 4.2：分块可视化对比页（小而亮的工具页）

**位置**
- `frontend/src/views/knowledge-base/modules/chunk-visualizer.vue`

**功能**
- 上传/选择一篇法律文档（《民法典》或《刑法》章节）
- 左右两栏：
  - 左：Baseline（固定长度 512 tokens）切分结果 —— 红色标记"条文被切断的位置"
  - 右：树状多级分块结果 —— 绿色标记"完整条文 + 层级上下文"
- 输出对比统计：条文切断次数、平均 chunk 大小、chunk 总数

**验收标准**
- [ ] 同一文档，Baseline 条文切断次数 > 0 时，Proposed 切断次数 = 0（或明显更少）
- [ ] 该页在 Demo 环境可直接打开，支持内置样例（无需上传真实文件）

### 模块 4.3：上传 / 解析进度 WebSocket 推送（已有 + 可扩展）

**位置**
- `ParseService` / `VectorizationService` 处理 pipeline（已有）
- 前端已有进度条（可直接复用；如尚未接入 WebSocket，则用轮询先打通 —— 工程上够用）

**验收标准**
- [ ] 上传 1 篇《民法典》.pdf，用户能看到 PENDING → PARSING（结构解析）→ CHUNKING（树状分块）→ EMBEDDING → INDEXING → DONE 全流程状态

---

## Phase 5 · Legal-RAG-Bench 评测与基线实验（支撑论文第 6 章）

### 模块 5.1：评测数据集构建脚本（Python 最方便，放 `experiments/` 目录）

**目录**
```
d:\VsCode\RagSystem\experiments\
  build_legal_benchmark.py    # 1. 生成 queries.jsonl + relevant_chunks.jsonl
  eval_legal_retrieval.py     # 2. 跑 4 个 baseline + proposed，输出 evaluation_report.csv
  eval_legal_plot.py          # 3. 画图：柱状图 / α 自适应曲线
```

**queries.jsonl 格式**
```jsonl
{"query_id": "Q001", "query_text": "《民法典》第123条的内容是什么？", "query_type": "identifier", "difficulty": "easy"}
{"query_id": "Q002", "query_text": "什么情况下可以解除合同？", "query_type": "intent", "difficulty": "medium"}
{"query_id": "Q003", "query_text": "刑法第232条规定的罪名是什么？", "query_type": "identifier", "difficulty": "easy"}
{"query_id": "Q004", "query_text": "什么是正当防卫？", "query_type": "definition", "difficulty": "easy"}
{"query_id": "Q005", "query_text": "合同无效后如何处理？涉及哪些条文？", "query_type": "comprehensive", "difficulty": "hard"}
...
```

**relevant_chunks.jsonl 格式**
```jsonl
{"query_id": "Q001", "chunk_id": "C-民法典-第123条", "relevance": 2}
{"query_id": "Q001", "chunk_id": "C-民法典-第124条", "relevance": 1}
{"query_id": "Q002", "chunk_id": "C-民法典-第562条", "relevance": 2}
...
```

**验收标准**
- [ ] queries 数量 ≥ 50（理想 100），覆盖 4 种类型
- [ ] 每个 query 的 relevant chunks 数量 ≥ 3
- [ ] 两位标注者一致性 Cohen's κ ≥ 0.7（如时间紧张，可把"一致性检验"作为附加实验写在论文里，但至少做过一次）

### 模块 5.2：检索评估器（可 Java 实现，也可 Python 直接调 ES）

**位置**
- `experiments/eval_legal_retrieval.py`

**评测内容**
- Baseline-1：BM25 only
- Baseline-2：KNN only（用 Baseline-固定长度 分块的 chunk）
- Baseline-3：Hybrid with fixed α = 0.5
- Baseline-4：Hybrid with RRF
- Proposed：本文方案（树状多级分块 + Adaptive α）

**输出指标**
- Recall@5, Recall@10
- MRR
- NDCG@5, NDCG@10
- Precision@5

**统计检验**
- 配对 t-test（Proposed vs Baseline-3）
- 输出 p-value（若 p < 0.05 则显著）

**验收标准**
- [ ] Proposed 至少在 2 个指标上显著超过 Baseline-3（显著性检验 p < 0.05）
- [ ] `identifier` 类型问题的 Recall@10 提升幅度明显高于其他类型 —— 这是验证"Adaptive α 确实工作"的关键证据

### 模块 5.3：性能基准（最小化，以防论文要写）

**位置**
- `experiments/benchmark_perf.py`（或用 JMeter / wrk）

**指标**
- P50 / P95 / P99 延迟
- 并发 QPS（至少 10 / 50 / 100 三档）
- ES CPU、后端 CPU、内存

**验收标准**
- [ ] 有一张性能表（即使数字不大）就能支撑"工程可部署"这一论点

---

## 📋 验收 Checklist（总览）

| Phase | 模块 | 关键产物 | 验收 |
|-------|-----|---------|------|
| 1 | 法律文档解析基线 | `LegalStructureParser` + `LegalCorpusStatsService` + CSV 统计数据 | 解析《民法典》+《刑法》无误；统计脚本可复现 |
| 2 | 树状多级分块器 | `TreeAwareChunker` + `ArticleLevelChunker` + 单元测试 + 人工评分表 | 比 Baseline 人工评分高 1 分以上；条文无切断 |
| 3 | 混合检索 + Adaptive α | `HybridSearchService` + ES mapping（含 `article_id` keyword） | 三类典型法律 query 都能正确调整 α |
| 4 | 检索溯源面板 + 分块可视化 | 2 个新前端页面（retrieval-trace-panel + chunk-visualizer） | 答辩可演示"三种模式切换对比" |
| 5 | Legal-RAG-Bench 评测 | `evaluation_report.csv` + α 自适应图 + 分块对比图 | Proposed 在 ≥ 2 指标上显著优于 Baseline |

---

## 📅 推荐工期与里程碑（约 8–10 周，可按学校答辩周期压缩）

| 时间 | Phase | 核心产物 | 关键自查点 |
|-----|-------|---------|-----------|
| Week 1 | Phase 1 | 《民法典》+《刑法》入库 + 层级分布/引用密度 CSV | "第X条"是否被正确识别？统计数据是否合理？ |
| Week 2 | Phase 2 · 2.1/2.2 | `ArticleLevelChunker` + `HierarchyLevelChunker` 初版 | 单元测试全部通过？ |
| Week 3 | Phase 2 · 2.3/2.4 | `TreeAwareChunker` + `LegalTermScorer` | 是否真正保留了条文完整性？ |
| Week 4 | Phase 3 · 3.1 | ES Mapping + 索引重建；向量写入打通 | `article_id` 可被 keyword 精确命中？ |
| Week 5 | Phase 3 · 3.2/3.3 | `HybridSearchService` + 重排器 | α 是否能随 query 自动变化？ |
| Week 6 | Phase 4 | 前端溯源面板 + 分块可视化 | 能否在 5 分钟内演示完论文亮点？ |
| Week 7–8 | Phase 5 | 评测数据集 50–100 queries + 跑实验 → evaluation_report.csv + 图表 | Proposed 是否在 Recall@10 显著超过 Baseline？ |
| Week 9–10 | 论文写作 | 初稿 → 二稿 → 终稿（对应论文框架 8 章） | 第 3、4、5、6 章都有数据支撑 |

---

## 🔑 工程风险与降级方案

1. **法律文档 PDF 解析不精确** → 先用 Apache Tika + 手工修正正则；极端情况下人工把 PDF 转成结构化 TXT（论文中诚实说明预处理过程即可）
2. **"第X条"正则误命中（如正文中的"第123条"也被当成标题）** → 加入"行首位置 + 字体大小 / 换行模式"的启发式过滤（`LegalStructureParserTest` 中测）
3. **Embedding API 调用费用或网络抖动** → 可缓存 query 向量（Redis 以 `sha256(query)` 作为 key）
4. **标注者不足** → 你自己做 100 queries × 3 级相关性 + 1 个同学交叉标注 50 条，足可以做一次 κ 检验
5. **实验性能指标不理想** → 论文中诚实说明"在 X vCPU 单机环境下，系统支持 50 QPS；可通过多机分片进一步扩容"

---

## 📄 最终提交物清单（便于论文装订）

1. 论文正文（8 章，按框架）
2. 附录 A · 核心算法伪代码（`LegalStructureParser`、`TreeAwareChunker`、`HybridSearchService`）
3. 附录 B · `Legal-RAG-Bench` 数据集抽样（queries + relevant chunks 各 20 条）
4. 附录 C · 实验图表（层级分布图、α 自适应曲线图、Recall/MRR/NDCG 对比图、分块质量对比图、性能表）
5. 答辩演示 PPT（核心：5 分钟走 4 个亮点）
   - 研究动机：法律文本的树状结构 → 传统 RAG 失败案例 demo
   - 创新点 1：树状多级分块（分块可视化对比页 demo）
   - 创新点 2：Adaptive α 向量-倒排混合检索（溯源面板 α 实时变化 demo）
   - 量化证据：evaluation_report.csv 表格 + 图表
