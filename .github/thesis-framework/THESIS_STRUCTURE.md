# 毕设论文框架：面向法律领域的树状多级分块与向量-倒排混合检索 RAG 精度优化

> **论文题目（建议）**：基于树状多级分块与向量-倒排混合检索的法律知识 RAG 精度提升研究——以《民法典》与《刑法》为例
>
> **研究方向**：大语言模型在垂直法律领域的应用落地；RAG 检索精度优化；树状多级分块（Document → Part → Chapter → Section → Article → Paragraph）；向量检索（Dense）与倒排检索（BM25 / Elasticsearch inverted index）混合检索。
>
> **核心论点**：法律文本（尤其法典）具有天然的"分编-分章-分节-分条-款项"树状层次结构，传统的固定长度切分会粗暴切断法条之间的引用关系与语义上下文；而纯向量检索对"第123条"、"违约责任"、"自首"这类精确法律术语匹配能力弱。本文提出一套"树状多级分块 + 向量-倒排混合检索 + 法律术语加权"的召回策略，可显著提升法律场景下的答案召回精度。

---

## 📋 论文总体结构（建议 8–10 章）

### **第 1 章：绪论（4–5 页）**

- **1.1 研究背景与意义**
  - 中国法律体系的层次化特征：《民法典》（总则编 / 物权编 / 合同编 / 人格权编 / 婚姻家庭编 / 继承编 / 侵权责任编）、《刑法》（总则 / 分则 / 章节）均具有天然的树状结构
  - 法律检索场景的独特性：需要同时支持"精准法条引用"（倒排检索强项）与"语义相关条文"（向量检索强项）
  - 大语言模型 + RAG 成为法律问答（Legal QA）与合规检索的主流路径
  - **法律数据形态的特殊性**：条文（Article）+ 款（Paragraph）+ 项（Item）+ 但书 + 法律概念定义 + 引用指向（"参照本法第XX条"、"适用前款规定"）

- **1.2 问题分析（法律场景特有的挑战）**
  - **挑战 1：传统固定长度切分破坏法律条文的树状边界**
    - 按 512 token 切分会把"第123条"拆到两个 Chunk 中，也会把"章节标题 + 条文"的关联切断
    - 同一条文的"款/项"逻辑依赖链被切断 → 向量表示失真
  - **挑战 2：法律术语与引用符号的"语义稀释"**
    - 纯向量检索对"《民法典》第123条"、"刑法第232条"、"违约"、"侵权责任"这类精确术语匹配弱
    - 而纯 BM25 对"什么情况下可以解除合同"这类意图型问题召回噪音
  - **挑战 3：法律文本解析与法条边界识别困难**
    - 法律文件可能是 PDF / DOCX，解析过程需要识别"第X条"、"第X款"、"第X编/章/节"的结构化边界
    - 引用关系（引用其他条文、"参照"、"适用"）的解析是检索增强的关键

- **1.3 研究目标与创新点（三项核心贡献）**
  1. **树状多级分块器（Tree-aware Legal Chunker）**：基于法律文本的层次结构（Document → Part → Chapter → Section → Article → Paragraph）构建树状分块，对每一条文保持完整性，生成"条级 Chunk" + "节级 Chunk" + "粗粒度 Chunk" 的多级粒度，形成多尺度向量表示
  2. **向量-倒排混合检索与自适应融合策略（Hybrid Search with Adaptive α for Legal Text）**：在 Elasticsearch 中并行执行 BM25（精确术语/条文号/章节标题）与 KNN 向量检索（语义相似度），依据查询的"法律术语密度 / 条文编号特征"动态调整融合权重 α
  3. **法律评测基准 Legal-RAG-Bench**：基于《民法典》与《刑法》构建 50–100 个典型法律问题 + 人工标注相关法条 + 相关性分级；以 Recall@K、MRR、NDCG@K、Precision@K 作为量化指标

- **1.4 论文组织与贡献概览**

---

### **第 2 章：相关工作与现状分析（3–4 页）**

- **2.1 RAG 技术发展脉络**
  - 传统检索（BM25 / TF-IDF / Lucene 倒排索引）→ 向量检索（Dense Retrieval）→ Hybrid Search（RRF / 线性融合）→ 带重排（Cross-Encoder / ColBERT）
  - LangChain、LlamaIndex 等框架的默认切分器：RecursiveCharacterTextSplitter、MarkdownHeaderTextSplitter 的优缺点分析
  - 法律领域 RAG 综述：LexiLLM、LawGPT 等代表性工作及其在分块策略上的局限

- **2.2 文档分块（Chunking）研究现状**

  | 分块策略 | 切分依据 | 对法律结构敏感 | 对长文档友好 | 备注 |
  |----------|---------|-------------|-------------|------|
  | 固定长度（Naive） | 字符数 / token 数 | ❌ 极差 | ⭐ 稳定 | 业界基准 Baseline-1 |
  | 递归字符切分 | 分隔符列表 `\n\n, \n, . ` | ❌ | ⭐⭐ | LangChain 默认 |
  | 语义分块（Sentence-BERT） | 句子向量余弦相似度 | ⚠️ 对结构化引用弱 | ⭐⭐⭐ | |
  | 标题层级分块（MarkdownHeader） | `#` 层级 | ⚠️ 未区分法条边界 | ⭐⭐ | |
  | **本文提案：树状多级分块** | 法典自然结构（编 → 章 → 节 → 条 → 款） | ✅ 优秀 | ⭐⭐⭐⭐⭐ | **核心创新** |

- **2.3 混合检索（Hybrid Search）研究**
  - 倒排索引（BM25）的优势：精确法律术语匹配、条文号高判别力、对"第X条"极为敏感
  - 向量检索（Dense Vector）的优势：意图理解、同义改写（"解除合同"与"终止合同"）、跨概念检索
  - 融合方式：RRF（Reciprocal Rank Fusion）vs. 线性加权；为何在法律场景需要**自适应权重**（Adaptive α）——查询中条文号密度高时偏向 BM25，意图型问题偏向向量

- **2.4 法律领域的 LLM / RAG 应用**
  - 法律问答（Legal QA）、合同审阅、法条检索方向的代表性工作（引用 3–5 篇）
  - 指出"法律领域尚未有针对树状分层结构的系统分块 + 混合检索优化研究"——作为本文立题依据

---

### **第 3 章：法律文本数据特性分析（4–5 页）**

> 本章是整篇论文的"地基"：证明《民法典》与《刑法》确实存在树状结构，你的优化动机真实存在。

- **3.1 数据来源与统计画像**
  - 数据来源：`d:\VsCode\RagSystem\data\中华人民共和国民法典 - 中华人民共和国最高人民法院.pdf`、`d:\VsCode\RagSystem\data\中华人民共和国刑法_20201226.docx`
  - 统计指标：
    - 总条文数（《民法典》1260 条、《刑法》452 条）
    - 层级分布：编 / 章 / 节 / 条 / 款 / 项 数量分布（表格 + 柱状图）
    - **条文引用密度**：每 100 条文中"第X条"、"参照"、"适用"、"前款"、"本法"等引用标记的频次分布
    - **法律术语密度**："合同"、"侵权"、"违约"、"自首"、"正当防卫"等术语的 TF 分布
    - 法律条文平均长度（token 数）分布

- **3.2 典型法律文本结构剖析（3 个实例）**
  - 实例 1：《民法典》合同编（含"第465条 依法成立的合同..."、"第562条 合同解除"、引用"参照本法第XX条"）
  - 实例 2：《刑法》分则（含"第232条 故意杀人罪"、"第234条 故意伤害罪"、量刑层次）
  - 实例 3：《民法典》侵权责任编（含条、款、项结构与交叉引用）
  - 对每个实例做"破坏性分块演示"：展示 LangChain 固定长度切分时"第X条"被切断的位置截图 / 文本片段

- **3.3 失败案例分析：为何默认 RAG 在法律场景会退化**
  - 现象 A：查询"《民法典》第123条的内容是什么？" → 纯向量检索召回一批只含"123"字样但无关条文；纯 BM25 召回包含"123"的非条文位置（如页码引用）
  - 现象 B：查询"什么情况下可以解除合同？" → 纯 BM25 精确匹配"解除合同"但不理解"情况"的语义条件；纯向量理解语义但无法锁定包含"解除"关键词的具体条文
  - 将以上失败案例用定量方式呈现：不同策略下的 Recall@10 / MRR 对比（为第 6 章做铺垫）

---

### **第 4 章：树状多级分块策略（核心创新章，6–7 页）**

#### **4.1 总体设计思路**
```
输入文档（《民法典》.pdf / 《刑法》.docx → 解析为结构化文本）
  │
  ▼
Step 1: 结构解析 —— 识别法律树状结构
  ├─ 编（Part）："第一编 总则"、"第二编 物权"…
  ├─ 章（Chapter）："第一章 基本规定"、"第二章 自然人"…
  ├─ 节（Section）："第一节 民事权利能力和民事行为能力"…
  ├─ 条（Article）："第123条 民事主体依法享有知识产权" （核心切分单位）
  ├─ 款（Paragraph）："（一）作品；（二）发明、实用新型、外观设计…" （可选子切分）
  └─ 项（Item）：款内更小的分点
  │
  ▼
Step 2: 树状多级粒度生成
  ├─ 粒度 L1（条级）：每条文 = 1 个 Chunk（最小检索单位）
  ├─ 粒度 L2（节级）：同一节内的多条文合并为 1 个 Chunk（上下文扩展）
  ├─ 粒度 L3（章级）：同一章内的多条文合并为 1 个 Chunk（粗粒度匹配）
  └─ 保留"标题 + 条文"的拼接 Chunk（用于增强向量表示）
  │
  ▼
Step 3: 重叠与上下文注入策略
  ├─ 条级 Chunk 注入父级标题前缀："【第二编 物权·第一分编 通则·第一章 一般规定】第205条 本编调整..."
  ├─ 相邻 Chunk 保留引用关系（如"第123条"被引用时，注入被引条文摘要作为上下文）
  │
  ▼
Step 4: 元数据打标
  每个 Chunk 额外写入 meta 字段：
  - chunk_level: "article" | "section" | "chapter"
  - part_name: "第一编 总则"
  - chapter_name: "第一章 基本规定"
  - section_name: "第一节 民事权利能力和民事行为能力"（如适用）
  - article_id: "第123条"
  - legal_term_density: 条文中法律术语命中数 / 总 token 数
  - reference_density: 条文中"第X条"、"参照"、"适用"等引用标记密度
```

#### **4.2 法律结构解析与条级识别算法（伪代码）**
```python
def parse_legal_structure(text: str) -> Tree[LegalBlock]:
    """
    通过正则 + 层级堆栈构建法律树状结构：
    识别规则：
      - Part:      r'^第[一二三四五六七八九十]+编'
      - Chapter:   r'^第[一二三四五六七八九十百]+章'
      - Section:   r'^第[一二三四五六七八九十]+节'
      - Article:   r'^第[0-9零一二三四五六七八九十百千]+条'
      - Paragraph: r'^（[一二三四五六七八九十]+）' 或 r'^\(\d+\)'
    """
    stack = [RootNode()]
    for line in text.splitlines():
        if matches_part(line):
            flush_current(stack)
            stack.append(PartNode(text=line))
        elif matches_chapter(line):
            flush_current(stack)
            stack.append(ChapterNode(text=line))
        elif matches_section(line):
            flush_current(stack)
            stack.append(SectionNode(text=line))
        elif matches_article(line):
            flush_current(stack)
            stack.append(ArticleNode(text=line))
        elif matches_paragraph(line) and isinstance(stack[-1], ArticleNode):
            stack.append(ParagraphNode(text=line))
        else:
            if stack[-1]:
                stack[-1].content += line + "\n"
    return stack[0]
```

#### **4.3 条级 Chunk 的多级语义聚合**
- 每个条级 Chunk 含：完整条文原文 + 所属层级标题路径（"【编→章→节】"前缀）
- 同一 Section 下的多条文合并形成节级 Chunk，用于粗粒度召回
- 与传统递归切分器的复杂度对比分析（时间、空间）

#### **4.4 条间引用的上下文增强**
- 对包含"参照第X条"、"适用本法第X条"、"前款"的条文，注入被引条文摘要作为上下文
- **引用图构建**：构建条文引用邻接表，用于检索时的"一跳召回"（即召回本条 + 被引条文的 Chunk）

#### **4.5 与 Baseline 分块器的定性对比**

| 特性 | 固定长度 | 递归字符 | MarkdownHeader | **本文树状多级分块** |
|------|---------|---------|----------------|-------------------|
| 保留完整条文边界 | ❌ | ❌ | ⚠️ | ✅ |
| 感知编/章/节/条层级 | ❌ | ❌ | ⚠️（仅 Markdown 有） | ✅ |
| 生成多级粒度 Chunk | ❌ | ❌ | ❌ | ✅ |
| 注入层级标题上下文 | ❌ | ❌ | ⚠️ | ✅ |
| 条间引用感知 | ❌ | ❌ | ❌ | ✅ |
| 输出元数据（legal_term_density） | ❌ | ❌ | ⚠️ | ✅ |

---

### **第 5 章：向量-倒排混合检索与自适应权重（6–7 页）**

#### **5.1 Elasticsearch 中的两路检索设计**
- **路径 A：倒排检索（BM25）**
  - 字段：`text_content` + `article_id`（keyword） + `parent_section` + `legal_terms`
  - `article_id` 使用 keyword 字段 + term 查询，精确匹配"第123条"
  - `legal_terms` 字段对"合同、侵权、违约、自首"等概念词做 boost
- **路径 B：向量检索（KNN on dense_vector）**
  - 使用中文法律 Embedding（如 bge-m3、text2vec-base-chinese、或在法律语料上微调后的 BERT 向量，论文中明确选型并给出理由）
  - 向量维度 d = 768 / 1024（依模型而定）
  - 对条级 / 节级 / 章级 Chunk 使用同一向量空间（或实验双塔策略作为 ablation）

#### **5.2 融合公式：Adaptive α 的推导**
```
Score_hybrid(q, d) = α(q) · BM25_norm(q, d) + (1 - α(q)) · KNN_norm(q, d)

其中 α(q) = sigmoid(w1 · article_id_density(q)
                   + w2 · legal_term_density(q)
                   + bias)
  article_id_density(q) = (查询中正则匹配"第[0-9零一二三四五六七八九十百千]+条"的次数) / 查询总token数
  legal_term_density(q)  = (查询中命中法律术语词典的次数) / 查询总token数
  w1, w2, bias = 在验证集上 grid search 得到的权重系数
```
- **BM25_norm / KNN_norm**：用 min-max 归一化到 [0,1]，消除两个分数分布差异
- 对比策略：固定 α = 0.5（平均融合）、RRF（Reciprocal Rank Fusion）、本文 Adaptive α
- **显著性检验**：配对 t-test，p < 0.05

#### **5.3 重排阶段（可选，如果工期紧张可简化为 rerank 消融实验）**
- 使用 Cross-Encoder（如 `bge-reranker-v2-m3`）对 top-20 重排 → top-5
- 重排分数融合：`Rerank_score = β · CE(q, d) + (1-β) · Hybrid_score`

#### **5.4 检索链路的实现细节（与工程第 7 章呼应）**
- ES 索引 Mapping（含 `dense_vector` 字段 + 多层级元数据字段）
- 前端"检索溯源面板"数据结构：
  ```json
  {
    "chunk_id": "...",
    "source_doc": "中华人民共和国民法典.pdf",
    "chunk_level": "article",
    "article_id": "第123条",
    "part_chapter_section": "第一编 总则 · 第五章 民事权利",
    "bm25_score_norm": 0.85,
    "knn_score_norm": 0.62,
    "hybrid_score": 0.73,
    "alpha": 0.72,
    "snippet": "第123条 民事主体依法享有知识产权。知识产权是权利人依法就下列客体...",
    "hit_reason": "精确条文号匹配（第123条） + 语义相关（知识产权、民事权利）"
  }
  ```

---

### **第 6 章：实验设计与验证（6–8 页，核心量化章）**

#### **6.1 实验环境**
| 组件 | 选型 |
|------|------|
| 服务器 | 16 Core / 32 GB RAM / 200 GB SSD（或云上同等规格） |
| 搜索引擎 | Elasticsearch 8.10（开启 KNN + BM25 混合） |
| 向量模型 | bge-m3 / text2vec-base-chinese（论文中固定一个） |
| 重排模型 | bge-reranker-v2-m3（如做重排实验） |
| 主存储 | MySQL 8.0（文档元数据） |
| 缓存 / 会话 | Redis 7.0 |
| 消息队列 | Kafka 3.2（用于异步解析流水线） |
| **实验数据** | `d:\VsCode\RagSystem\data\中华人民共和国民法典 - 中华人民共和国最高人民法院.pdf` + `d:\VsCode\RagSystem\data\中华人民共和国刑法_20201226.docx` |

#### **6.2 自建评测集：Legal-RAG-Bench**
- **规模**：50–100 个问题（query），每题 3–8 篇相关文档；相关文档总数 ≈ 300–600 chunks
- **问题类型分布（建议）**

  | 类型 | 占比 | 示例 |
  |------|------|------|
  | 精确条文查询（Identifier） | 25% | `《民法典》第123条的内容是什么？`、`刑法第232条规定的罪名？` |
  | 概念/定义查询 | 20% | `什么是违约责任？`、`什么是正当防卫？` |
  | 意图/场景查询（Intent） | 35% | `什么情况下可以解除合同？`、`入室盗窃怎么判刑？` |
  | 综合/引用查询 | 20% | `合同无效后如何处理？涉及哪些条文？` |

- **标注等级**：`2 = 强相关（必须包含核心法条 / 完整条文）`、`1 = 弱相关（包含主题但非核心条文）`、`0 = 不相关`
- **标注流程**：两位标注者独立标注 → Cohen's κ 一致性检验 → 分歧讨论 → 最终 Gold Standard

#### **6.3 对比方案（≥ 4 个 Baseline）**
1. **Baseline-1：BM25 only** —— 纯倒排检索
2. **Baseline-2：KNN only（固定长度分块）** —— 纯向量检索
3. **Baseline-3：Hybrid with fixed α = 0.5（MarkdownHeader 分块）** —— 混合检索但无树状结构感知
4. **Baseline-4：Hybrid with RRF（MarkdownHeader 分块）** —— RRF 融合作为对照
5. **Proposed：本文方案（树状多级分块 + Adaptive α 向量-倒排混合检索 + 可选 rerank）**

> 关键变量控制：**同一组文档，同一 Embedding 模型，同一 top-K**，只有分块策略 / 融合策略不同。

#### **6.4 评估指标与计算公式**
| 指标 | 公式 | 目标（建议） |
|------|------|------------|
| **Recall@K** | $\frac{\|relevant \cap topK\|}{\|relevant\|}$ | K=10, ≥ 0.85 |
| **MRR** | $\frac{1}{\|Q\|}\sum_{q \in Q}\frac{1}{rank_{1st}(q)}$ | ≥ 0.80 |
| **NDCG@K** | 考虑相关性等级（0/1/2） | K=10, ≥ 0.82 |
| **Precision@K** | $\frac{\|relevant \cap topK\|}{K}$ | K=5, ≥ 0.75 |

- 显著性检验：配对 t-test（Proposed vs 每个 Baseline），给出 p-value
- 不同问题类型下的拆分指标：精确条文查询的提升预期会最大

#### **6.5 实验 A：分块策略对比（控制融合策略固定为 Adaptive α）**
- 比较"固定长度" vs "递归字符" vs "MarkdownHeader" vs "本文树状多级分块"
- 关键看点：**是否在条文边界完整性上显著拉开差距**（可用堆叠柱状图）

#### **6.6 实验 B：融合策略对比（控制分块为本文方案）**
- 比较 fixed α vs RRF vs Adaptive α；并画出 "Adaptive α 随 article_id_density + legal_term_density 变化" 折线图，展示权重自适应的物理含义

#### **6.7 实验 C：端到端检索 + 生成质量（答辩加分项）**
- 在最终 top-5 Chunk 送入 LLM（如 GPT / Qwen / DeepSeek / 法律专用 LLM）生成答案，由 GPT-4 作为裁判做盲评 win-rate，或由人工做 3–5 分打分
- 预期：本文方案在"条文完整性"、"引用链条完整"维度上显著优于 Baseline

#### **6.8 性能与延迟（工程维度）**
- 单查询端到端延迟（P50 / P95 / P99）：混合检索 + 重排
- 吞吐：≥ 50 QPS（可通过 JMeter / wrk 测得）

---

### **第 7 章：系统实现与工程展示（4–5 页）**

#### **7.1 系统架构总览（一张大图）**
```
┌──────────────────── 前端 (Vue 3) ──────────────────────┐
│  聊天页 · 知识库管理页 · 【检索溯源面板（新增·论文亮点）】│
└──────────────────────┬──────────────────────────────────┘
                       │ HTTPS / WebSocket
┌──────────────────────▼──────────────────────────────────┐
│  后端 (Spring Boot 3)                                    │
│   ├─ UploadController（支持 pdf/docx/txt 等法律文档）      │
│   ├─ DocumentService（元数据 / 预览）                       │
│   ├─ 【新增】LegalParseService（法律树状结构解析器）         │
│   ├─ 【新增】TreeAwareChunker（树状多级分块器）              │
│   ├─ VectorizationService（调用 Embedding API）             │
│   └─ 【新增】HybridSearchService（Adaptive α 向量-倒排融合） │
└─────────────┬────────────┬────────────┬────────────────────┘
              │            │            │
        MinIO(文件)   MySQL(元数据)  Redis(缓存)
                                       │
                                   Elasticsearch 8.10
                                   (倒排索引 + dense_vector)
                                       │
                              Kafka（异步解析流水线）
```

#### **7.2 解析流水线：PDF/DOCX → 法律树状结构 → 条级 Chunks**
- Step 1：用 Apache Tika / Python-docx 解析原始 PDF 与 DOCX 为纯文本
- Step 2：`LegalStructureParser` 识别"编→章→节→条→款"层次结构，构建树节点
- Step 3：`TreeAwareChunker` 生成条级（article）、节级（section）、章级（chapter）三级 Chunk，并注入层级标题上下文
- Step 4：每个 Chunk 写入 MySQL `document_vectors` 表 + ES 索引（含 dense_vector）

#### **7.3 工程亮点（答辩演示时重点讲）**
1. **"白盒"检索溯源面板**：用户提问后，前端不仅给出答案，还列出 `chunk_id / 来源法典 / 编-章-节 / 条文号 / bm25_norm / knn_norm / α / 命中 snippet / hit_reason`
2. **上传 / 解析进度 WebSocket 推送**：PENDING → PARSING（结构解析） → CHUNKING（树状分块） → EMBEDDING → INDEXING → DONE
3. **分块可视化**（小工具）：上传同一篇法律文档分别走 Baseline 切分与本文分块，左右对比展示条文边界被切断 vs 完整保留

#### **7.4 关键代码片段（放入论文附录或正文）**
- `LegalStructureParser`（正则识别 + 堆栈维护）核心函数
- `TreeAwareChunker`（多级粒度生成 + 上下文注入）核心函数
- `HybridSearchService`（Adaptive α 融合）核心函数 + ES Query DSL
- `IdentifierDensityScorer`（法律术语密度 + 条文号密度计算）核心函数

---

### **第 8 章：结论与展望（2–3 页）**

- **8.1 主要贡献总结**（三条与第 1 章呼应）
  1. 针对法律文本（《民法典》、《刑法》）的树状层次结构提出了新的树状多级分块器，生成条 / 节 / 章三级粒度的 Chunk，保留完整条文边界与层级上下文
  2. 提出了 Adaptive α 的向量-倒排混合检索策略，对精确条文查询（"第X条"）与意图型问题（"什么情况下..."）都有提升
  3. 构建了 Legal-RAG-Bench 评测基准（基于《民法典》与《刑法》的 50–100 个法律问题）并进行充分对比实验

- **8.2 局限与未来工作**
  - 局限 1：当前仅覆盖《民法典》与《刑法》两部法典，尚未扩展到行政法规、司法解释、案例汇编 → 未来可引入更多法律来源
  - 局限 2：分块器依赖正则识别法律结构，对排版不规范的 PDF 扫描件退化 → 未来可引入版面分析 / OCR 结构化解析
  - 局限 3：Adaptive α 的权重依赖 grid search → 未来可引入在线学习或 RLHF
  - 未来方向 1：引入法律专用 Embedding（如在裁判文书上微调 BERT）并做双塔对比
  - 未来方向 2：加入"引用图推理"（召回条文 + 被引条文的多跳扩展）支持 Agent 式工具调用
  - 未来方向 3：扩展到案例法检索（裁判文书），形成"法条 + 判例"的双源检索系统

---

## 📊 实验数据收集模板（供你实际跑实验用）

### 分块质量人工评估表（50 条文 × 3 分块器）
```csv
doc_id,doc_title,article_id,chunker_type,chunk_level,chunk_len_tokens,is_article_broken,is_context_cut,human_score(1-5)
L001,民法典,第123条,naive_char,article,512,1,1,2
L001,民法典,第123条,markdown_header,article,498,0,1,3
L001,民法典,第123条,tree_aware,article,380,0,0,5
...
```

### 检索精度实验（核心产出）
```csv
query_id,query_text,query_type,relevant_chunk_ids,
  baseline_bm25_recall10,baseline_bm25_mrr,baseline_bm25_ndcg10,
  baseline_knn_recall10,baseline_knn_mrr,baseline_knn_ndcg10,
  baseline_hybrid_fixed_recall10,baseline_hybrid_fixed_mrr,baseline_hybrid_fixed_ndcg10,
  proposed_recall10,proposed_mrr,proposed_ndcg10,
  alpha_used,article_id_density,legal_term_density
Q001,"《民法典》第123条的内容是什么？",identifier,"C123,C124,C125",0.60,0.65,0.62,0.55,0.58,0.56,0.70,0.72,0.70,0.90,0.92,0.91,0.78,0.45,0.30
...
```

### 性能基准数据
```csv
concurrency,p50_ms,p95_ms,p99_ms,qps,cpu_pct,mem_pct,es_cpu_pct
10,380,820,1200,12.5,30,26,42
50,700,1700,2900,5.2,52,45,70
100,1080,3400,5800,2.5,68,58,85
```

---

## 🎯 论文写作检查清单

- [ ] 每章均有定量指标或具体数据支撑，不做纯论述
- [ ] 第 3 章包含《民法典》与《刑法》的真实统计数据（条文数、层级分布、引用密度）与失败案例
- [ ] 第 4、5 章至少包含 1 段核心算法伪代码 + 1 张流程图
- [ ] 第 6 章 ≥ 4 个 Baseline，并给出配对 t-test p-value
- [ ] 对比实验在同一文档集、同一 Embedding 模型、同一 top-K 下进行
- [ ] 所有图表有数据来源与坐标轴标注
- [ ] 结论不超出实验证据范围
- [ ] 参考文献 ≥ 30 篇，核心期刊/顶会 ≥ 50%
- [ ] 通篇逻辑链：法律文本的树状层次特性 → 树状多级分块 → 向量-倒排混合检索自适应权重 → 实验证明提升
