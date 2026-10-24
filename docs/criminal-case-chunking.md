# 刑事案件文档分块策略

## 为什么不是纯父子分块

刑事案件判决书/审查报告有很强的模板结构，单纯按字符或按父子切分都会丢掉“这个块属于哪个诉讼要素”的信息。当前采用：

> **案件要素识别 → 段落级父块 → 段落内子块 → 子块召回、父块回补上下文**

也就是“元素感知 + 分层分块”，父子结构只是其中一层，不是全部。

## 识别哪些案件要素

`PrivateCaseParsingStrategy` 支持民事和刑事文书，刑事案件重点识别：

| 要素 | 典型标题 | caseSection |
|---|---|---|
| 首部/概况 | 案件基本情况、案件由来 | OVERVIEW / PROCEDURE |
| 当事人 | 被告人信息、当事人信息、辩护人 | PARTIES |
| 公诉/指控 | 公诉机关指控、起诉书、量刑建议 | PROSECUTION |
| 辩解/辩护 | 被告人辩称、被告人辩解、辩护人意见 | DEFENSE |
| 审理查明 | 经审理查明、审理查明、本院查明 | FACTS |
| 证据 | 证据、证据目录、质证意见 | EVIDENCE |
| 本院认为 | 本院认为、裁判理由、争议焦点 | COURT_OPINION |
| 裁判结果 | 判决如下、裁定如下 | JUDGMENT |
| 法律依据 | 法律依据、适用法律 | LEGAL_BASIS |
| 分析建议 | 法律分析、风险提示、结论、建议 | ANALYSIS |

同时兼容 `一、`、`（一）`、`1.`、`第一部分`、`【】`、纯关键词标题等格式。

## 父子块设计

每个案件段落生成：

1. **父块** `chunkRole=PARENT`
   - `chunkLevel=case-parent`
   - `blockId=case-xxx`
   - 保存整段原文（含表格/图片标记）
   - 只写 MySQL `private_document_vectors`，**不进入 ES、不做 Embedding**

2. **子块** `chunkRole=CHILD`
   - `chunkLevel=case-child`
   - `blockId=case-xxx-c0`
   - `parentBlockId=case-xxx`
   - 段落内按句子/换行 + token 上限切分
   - 进入 ES `knowledge_private`，参与 BM25 + KNN

3. **召回后回补**
   - 命中子块后，`HybridSearchService` 取 `parentBlockId`；
   - `DocumentChunkContextService` 从 MySQL 批量读取父块；
   - `SearchResult.contextText` 写入父段原文；
   - `ChatHandler` 优先用 `contextText` 构造 LLM 上下文（上限 1200 字）。

这样既保留子块的检索精度，又避免模型只看到 300 字的碎片。

## 表格与图表

### DOCX / HTML

`ParseService` 的 `StructuredTextExtractionHandler` 在 Tika 解析时保留结构：

- 段落/标题：补换行，避免整个 DOCX 被抽成一行；
- 表格：输出为

  ```text
  [TABLE]
  | 证据名称 | 证明目的 |
  | 转账记录 | 资金流向 |
  [/TABLE]
  ```

- 图片：输出 `[图片]` 占位；
- 表格所在的父块/子块标记 `contentType=TABLE`，图片标记 `contentType=FIGURE`。

### PDF

PDF 目前仍走 PDFBox 文本提取，能保留正文，但**不保证恢复表格的行列结构**；扫描件也没有 OCR。要支持复杂 PDF 表格/扫描件，需要接入版面分析或 OCR（例如 PaddleOCR、腾讯云文档解析等），当前没有默认启用。

### 图片内容

只保留 `[图片]` 占位和图注，不做图片内容识别。若后续需要“伤情照片/转账截图”检索，建议：

1. OCR 后把文字写入对应段落子块；
2. 或调用多模态模型生成图片描述，作为 FIGURE 子块入库。

## 检索过滤

- 父块不进入 ES，因此不会被直接召回；
- 查询额外加 `must_not chunkRole=PARENT`，防止历史/误索引父块被召回；
- 父子回补失败时自动退回子块文本，不影响主链路。

## 回退策略

以下情况仍走 `OverlapTextChunker`（`CHAR_OVERLAP`）：

- 没有识别出任何案件段落标题；
- 标题识别到了但段落没有有效内容；
- 文档是纯连续叙述、没有可识别结构。

回退块 `chunkRole=CHILD`，但没有父块，检索与回答仍可工作，只是没有父段上下文增强。

## 配置

```yaml
file:
  parsing:
    private-chunk-size: 512
    private-overlap-size: 100
```

父块大小不设硬上限，因为它只用于上下文回补；`ChatHandler` 最终会截断到 1200 字。
