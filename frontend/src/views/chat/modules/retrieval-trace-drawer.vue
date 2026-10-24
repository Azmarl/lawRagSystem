<script setup lang="ts">
import { NButton, NEmpty, NScrollbar } from 'naive-ui';
import type { TreeOption } from 'naive-ui';

defineOptions({ name: 'RetrievalTraceDrawer' });

export interface RetrievalDrawerState {
  visible: boolean;
  results: Api.Chat.RetrievalResult[];
}

interface LegalTreeLeaf extends TreeOption {
  score?: number;
  bm25?: number | null;
  knn?: number | null;
  textContent?: string | null;
  hitReason?: string | null;
}

const state = inject<RetrievalDrawerState>('retrievalDrawerState') || reactive({ visible: false, results: [] });

const alpha = computed(() => {
  if (!state.results || state.results.length === 0) return 0.5;
  return state.results[0].alpha ?? 0.5;
});

const bm25Percent = computed(() => Math.round(alpha.value * 100));
const vectorPercent = computed(() => Math.round((1 - alpha.value) * 100));

const alphaColor = computed(() => {
  const a = alpha.value;
  if (a >= 0.6) return '#f43f5e';
  if (a <= 0.4) return '#3b82f6';
  return '#a855f7';
});

const hitReason = computed(() => state.results?.[0]?.hitReason || '');

// ===== 私人案件检索意图与置信度 =====
function isPrivateResult(result?: Api.Chat.RetrievalResult | null) {
  return result?.documentType === 'PRIVATE_LEGAL' || result?.sourceCorpus === 'PRIVATE_CASE';
}

const privateResults = computed(() => (state.results || []).filter(isPrivateResult));
const publicResults = computed(() => (state.results || []).filter(result => !isPrivateResult(result)));

const intentCategory = computed(() => state.results?.[0]?.intentCategory || 'PUBLIC');
const hasPrivateIntent = computed(
  () =>
    intentCategory.value === 'PRIVATE' ||
    intentCategory.value === 'BOTH' ||
    privateResults.value.length > 0 ||
    (state.results || []).some(result => result.sourceCorpus === 'PRIVATE_CASE')
);
const intentLabel = computed(() => {
  if (intentCategory.value === 'PRIVATE') return '仅私人案件检索';
  if (intentCategory.value === 'BOTH') return '法典 + 私人案件混合检索';
  return '仅法典检索';
});
const intentConfidencePercent = computed(() => Math.round((state.results?.[0]?.intentConfidence ?? 0) * 100));
const intentReason = computed(() => state.results?.[0]?.intentReason || '');

function maxConfidence(results: Api.Chat.RetrievalResult[]) {
  if (!results || results.length === 0) return 0;
  return Math.max(...results.map(result => Number(result.score || 0)));
}

const privateConfidencePercent = computed(() => Math.round(maxConfidence(privateResults.value) * 100));
const publicConfidencePercent = computed(() => Math.round(maxConfidence(publicResults.value) * 100));

const legalTree = computed<TreeOption[]>(() => {
  if (!state.results || state.results.length === 0) return [];
  const rootMap = new Map<string, Map<string, Map<string, TreeOption[]>>>();

  for (const r of state.results) {
    const part = r.partName || '未分类';
    const chapter = r.chapterName || '';
    const section = r.sectionName || '';
    const article = r.articleId || `chunk-${r.chunkId}`;

    if (!rootMap.has(part)) rootMap.set(part, new Map());
    const chapterMap = rootMap.get(part)!;
    const chapterKey = chapter || part;
    if (!chapterMap.has(chapterKey)) chapterMap.set(chapterKey, new Map());
    const sectionMap = chapterMap.get(chapterKey)!;
    const sectionKey = section || chapterKey;
    if (!sectionMap.has(sectionKey)) sectionMap.set(sectionKey, []);
    const leaf: LegalTreeLeaf = {
      label: article, key: `chunk-${r.chunkId}-${article}`, isLeaf: true,
      score: r.score, bm25: r.bm25NormScore, knn: r.knnNormScore,
      textContent: r.textContent, hitReason: r.hitReason
    };
    sectionMap.get(sectionKey)!.push(leaf);
  }

  const tree: TreeOption[] = [];
  for (const [partName, chapterMap] of rootMap) {
    const partNode: TreeOption = { label: partName, key: `part-${partName}`, children: [] };
    for (const [chapterName, sectionMap] of chapterMap) {
      const chapterNode: TreeOption = { label: chapterName, key: `chapter-${chapterName}`, children: [] };
      for (const [sectionName, articles] of sectionMap) {
        chapterNode.children!.push({ label: sectionName, key: `section-${sectionName}`, children: articles as TreeOption[] });
      }
      partNode.children!.push(chapterNode);
    }
    tree.push(partNode);
  }
  return tree;
});

const defaultExpandedKeys = ref<string[]>([]);

function collectAllKeys(nodes: TreeOption[]): string[] {
  const keys: string[] = [];
  for (const node of nodes) {
    keys.push(node.key as string);
    if (node.children) {
      keys.push(...collectAllKeys(node.children));
    }
  }
  return keys;
}

watch(() => state.visible, v => {
  if (v) {
    defaultExpandedKeys.value = collectAllKeys(legalTree.value);
  }
});

// const selectedChunk = ref<any>(null);

// function handleTreeNodeClick(_keys: string[], options: (TreeOption | null)[]) {
//   const opt = options[options.length - 1] as any;
//   if (opt?.isLeaf && opt?.textContent) selectedChunk.value = opt;
// }
</script>

<template>
  <Teleport to="body">
    <Transition name="rd">
      <div v-if="state.visible" class="rd-overlay" @click.self="state.visible = false">
        <div class="rd-panel">
          <div class="rd-header">
            <h3 class="rd-title">
              <span class="title-icon">📊</span> 检索溯源面板
            </h3>
            <NButton text style="color: #94a3b8; font-size: 20px" @click="state.visible = false">
              <template #icon>×</template>
            </NButton>
          </div>
          
          <NScrollbar class="rd-body">
            <div class="rd-section">
              <div class="rd-section-title">检索策略总览 (Adaptive α)</div>
              <div class="alpha-box">
                <div class="alpha-row">
                  <span class="alpha-label" style="color: #fb7185">倒排检索 (BM25)</span>
                  <span class="alpha-val" style="color: #fb7185">{{ bm25Percent }}%</span>
                  <div style="flex: 1"></div>
                  <span class="alpha-val" style="color: #60a5fa">{{ vectorPercent }}%</span>
                  <span class="alpha-label" style="color: #60a5fa">向量语义 (KNN)</span>
                </div>
                <div class="alpha-track">
                  <div class="alpha-fill" :style="{ width: `${bm25Percent}%`, backgroundColor: alphaColor }"></div>
                  <div class="alpha-divider"></div>
                </div>
                <div class="alpha-hint">
                  <span class="hint-tag">α = {{ alpha.toFixed(3) }}</span>
                  <span class="hint-text">当前状态: {{ hitReason || (alpha >= 0.6 ? '倒排检索主导' : alpha <= 0.4 ? '向量检索主导' : '均衡双路混合') }}</span>
                </div>
              </div>
            </div>

            <div class="rd-section">
              <div class="rd-section-title">检索意图与语料召回置信度</div>
              <div class="intent-box">
                <div class="intent-head">
                  <span
                    class="intent-badge"
                    :class="{
                      'intent-public': intentCategory === 'PUBLIC',
                      'intent-private': intentCategory === 'PRIVATE',
                      'intent-mixed': intentCategory === 'BOTH'
                    }"
                  >
                    {{ intentLabel }}
                  </span>
                  <span v-if="intentConfidencePercent > 0" class="intent-confidence">
                    意图置信度 {{ intentConfidencePercent }}%
                  </span>
                </div>
                <div v-if="intentReason" class="intent-reason">{{ intentReason }}</div>

                <div class="corpus-confidence-grid">
                  <div class="corpus-confidence-item">
                    <div class="corpus-confidence-head">
                      <span>法典召回</span>
                      <span class="corpus-confidence-value">{{ publicConfidencePercent }}% · {{ publicResults.length }} 条</span>
                    </div>
                    <div class="confidence-track">
                      <div class="confidence-fill confidence-law" :style="{ width: `${publicConfidencePercent}%` }"></div>
                    </div>
                  </div>

                  <div v-if="hasPrivateIntent" class="corpus-confidence-item corpus-private">
                    <div class="corpus-confidence-head">
                      <span>私人案件召回</span>
                      <span class="corpus-confidence-value">{{ privateConfidencePercent }}% · {{ privateResults.length }} 条</span>
                    </div>
                    <div class="confidence-track">
                      <div class="confidence-fill confidence-private" :style="{ width: `${privateConfidencePercent}%` }"></div>
                    </div>
                  </div>
                </div>

                <div v-if="hasPrivateIntent && privateResults.length === 0" class="private-empty">
                  本次提问涉及私人案件，但未召回私人案件文档（可能尚未上传、尚未完成向量化或权限不匹配）。
                </div>
              </div>
            </div>

            <div class="rd-section">
              <div class="rd-section-title">
                召回知识块 (Top {{ state.results.length }})
              </div>
              <NEmpty v-if="state.results.length === 0" description="暂无检索结果" />
              <div v-else class="chunk-list">
                <div v-for="(r, i) in state.results" :key="`${r.fileMd5}-${r.documentType || 'PUBLIC'}-${r.chunkId}`" class="chunk-card">
                  <div class="chunk-header">
                    <div class="chunk-rank">TOP {{ i + 1 }}</div>
                    <div class="chunk-tags">
                      <span class="custom-tag" :class="isPrivateResult(r) ? 'tag-private' : 'tag-law'">
                        {{ isPrivateResult(r) ? '私人案件' : '法律法规' }}
                      </span>
                      <span v-if="r.contentType === 'TABLE'" class="custom-tag tag-table">表格</span>
                      <span v-else-if="r.contentType === 'FIGURE'" class="custom-tag tag-figure">图片</span>
                      <span v-if="r.articleId" class="custom-tag tag-warning">{{ r.articleId }}</span>
                      <span v-else class="custom-tag tag-primary">{{ 'chunk-' + r.chunkId }}</span>
                    </div>
                    <span class="chunk-file-name" :title="r.fileName">
                      {{ r.fileName || (isPrivateResult(r) ? '未知案件文件' : '未知法典文件') }}
                    </span>
                  </div>
                  
                  <div class="chunk-scores">
                    <div class="score-item">
                      <span class="sl">综合</span>
                      <span class="sv highlight">{{ (r.score * 100).toFixed(1) }}%</span>
                    </div>
                    <div v-if="r.bm25NormScore != null" class="score-item">
                      <span class="sl">BM25</span>
                      <span class="sv text-rose">{{ (r.bm25NormScore * 100).toFixed(1) }}%</span>
                    </div>
                    <div v-if="r.knnNormScore != null" class="score-item">
                      <span class="sl">KNN</span>
                      <span class="sv text-blue">{{ (r.knnNormScore * 100).toFixed(1) }}%</span>
                    </div>
                  </div>

                  <div class="chunk-body">
                    {{ (r.textContent || '').slice(0, 180) }}{{ (r.textContent || '').length > 180 ? '...' : '' }}
                  </div>
                  
                  <div v-if="r.hitReason" class="chunk-footer">
                    ⚡ 命中依据: {{ r.hitReason }}
                  </div>
                </div>
              </div>
            </div>
<!-- 
            <NDivider style="margin: 16px 0; opacity: 0.1" />

            <div class="rd-section">
              <div class="rd-section-title">底层法理层级解析树</div>
              <NEmpty v-if="legalTree.length === 0" description="无法律结构信息" />
              <div v-else class="tree-box">
                <NTree
                  :data="legalTree"
                  :default-expanded-keys="defaultExpandedKeys"
                  selectable
                  @update:selected-keys="handleTreeNodeClick"
                />
              </div>
              <div v-if="selectedChunk" class="sel-chunk">
                <div class="sel-chunk-title">📝 {{ selectedChunk.label }} 完整内容</div>
                <div class="chunk-full-text">{{ selectedChunk.textContent }}</div>
              </div>
            </div> -->
          </NScrollbar>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped lang="scss">
/* ====== 基础面板样式 (适配暗黑主题) ====== */
.rd-overlay {
  position: fixed; inset: 0; z-index: 2000;
  background: rgba(0, 0, 0, 0.6);
  backdrop-filter: blur(4px); /* 背景高斯模糊 */
  display: flex; justify-content: flex-end;
}

.rd-panel {
  width: 450px; min-width: 30%; max-width: 600px; height: 100vh;
  background: #18181c; /* 极客深灰底色 */
  color: #e2e8f0;
  display: flex; flex-direction: column;
  box-shadow: -10px 0 30px rgba(0, 0, 0, 0.5);
  border-left: 1px solid rgba(255, 255, 255, 0.05);
}

.rd-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 16px 20px;
  background: #1e1e24;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  flex-shrink: 0;
}

.rd-title {
  font-size: 16px; font-weight: 600; margin: 0; color: #f8fafc;
  display: flex; align-items: center; gap: 8px;
}

.rd-body {
  flex: 1; padding: 20px; overflow: auto;
}

.rd-section { margin-bottom: 24px; }
.rd-section-title {
  font-size: 14px; font-weight: 600; color: #cbd5e1;
  margin-bottom: 12px; display: flex; align-items: center;
}
.rd-section-title::before {
  content: ''; display: inline-block; width: 4px; height: 14px;
  background: #8b5cf6; border-radius: 2px; margin-right: 8px;
}

/* ====== Section 1: Adaptive α 策略栏 ====== */
.alpha-box {
  background: #24242b; border: 1px solid rgba(255,255,255,0.06);
  border-radius: 8px; padding: 14px;
}
.alpha-row { display: flex; align-items: center; margin-bottom: 8px; font-size: 12px; }
.alpha-label { font-weight: 500; }
.alpha-val { font-weight: 700; font-size: 14px; font-family: monospace; }
.alpha-track {
  height: 8px; border-radius: 4px; overflow: hidden; position: relative;
  background: linear-gradient(to right, #fb7185 0%, #fb7185 30%, #60a5fa 70%, #60a5fa 100%);
}
.alpha-fill {
  height: 100%; border-radius: 4px 0 0 4px;
  transition: width 0.4s cubic-bezier(0.4, 0, 0.2, 1);
}
.alpha-divider {
  position: absolute; top: 0; bottom: 0; left: 50%; width: 2px;
  background: rgba(255,255,255,0.3); transform: translateX(-50%);
}
.alpha-hint {
  margin-top: 10px; display: flex; align-items: center; gap: 8px; font-size: 12px; color: #94a3b8;
}
.hint-tag { background: rgba(139, 92, 246, 0.2); color: #c4b5fd; padding: 2px 6px; border-radius: 4px; font-family: monospace; }

/* ====== 检索意图与私人案件置信度 ====== */
.intent-box {
  background: #24242b; border: 1px solid rgba(255,255,255,0.06);
  border-radius: 8px; padding: 14px;
}
.intent-head {
  display: flex; align-items: center; justify-content: space-between; gap: 10px;
}
.intent-badge {
  font-size: 12px; font-weight: 600; padding: 3px 8px; border-radius: 6px;
}
.intent-public { color: #93c5fd; background: rgba(59, 130, 246, 0.16); }
.intent-private { color: #fdba74; background: rgba(249, 115, 22, 0.16); }
.intent-mixed { color: #d8b4fe; background: rgba(168, 85, 247, 0.18); }
.intent-confidence {
  font-size: 11px; color: #94a3b8; font-family: monospace;
}
.intent-reason {
  margin-top: 8px; font-size: 12px; line-height: 1.6; color: #94a3b8;
}
.corpus-confidence-grid {
  margin-top: 12px; display: flex; flex-direction: column; gap: 10px;
}
.corpus-confidence-item {
  background: rgba(0,0,0,0.18); border-radius: 6px; padding: 9px 10px;
}
.corpus-confidence-head {
  display: flex; align-items: center; justify-content: space-between;
  font-size: 12px; color: #cbd5e1; margin-bottom: 6px;
}
.corpus-confidence-value { font-family: monospace; color: #e2e8f0; }
.confidence-track {
  height: 6px; border-radius: 3px; overflow: hidden; background: rgba(255,255,255,0.08);
}
.confidence-fill {
  height: 100%; border-radius: 3px; transition: width 0.4s cubic-bezier(0.4, 0, 0.2, 1);
}
.confidence-law { background: linear-gradient(90deg, #3b82f6, #60a5fa); }
.confidence-private { background: linear-gradient(90deg, #f97316, #fbbf24); }
.private-empty {
  margin-top: 10px; font-size: 11px; line-height: 1.6; color: #fbbf24;
  background: rgba(245, 158, 11, 0.08); border-radius: 6px; padding: 8px 10px;
}

/* ====== Section 2: Chunk 卡片列表 ====== */
.chunk-list { display: flex; flex-direction: column; gap: 12px; }
.chunk-card {
  background: #202027; border: 1px solid rgba(255,255,255,0.04);
  border-radius: 8px; padding: 14px; transition: all 0.2s ease;
}
.chunk-card:hover {
  border-color: rgba(139, 92, 246, 0.4);
  background: #24242c; box-shadow: 0 4px 12px rgba(0,0,0,0.2);
}

.chunk-header {
  display: flex; align-items: center; gap: 8px; margin-bottom: 10px;
}
.chunk-rank {
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  color: #fff; font-size: 10px; font-weight: 700;
  padding: 2px 6px; border-radius: 4px; flex-shrink: 0;
}
.custom-tag {
  font-size: 11px; padding: 2px 6px; border-radius: 4px; border: 1px solid transparent;
}
.tag-warning { background: rgba(245, 158, 11, 0.1); color: #fbbf24; border-color: rgba(245, 158, 11, 0.2); }
.tag-primary { background: rgba(59, 130, 246, 0.1); color: #60a5fa; border-color: rgba(59, 130, 246, 0.2); }
.tag-law { background: rgba(59, 130, 246, 0.12); color: #93c5fd; border-color: rgba(59, 130, 246, 0.24); }
.tag-private { background: rgba(249, 115, 22, 0.12); color: #fdba74; border-color: rgba(249, 115, 22, 0.26); }
.tag-table { background: rgba(16, 185, 129, 0.12); color: #6ee7b7; border-color: rgba(16, 185, 129, 0.24); }
.tag-figure { background: rgba(168, 85, 247, 0.12); color: #d8b4fe; border-color: rgba(168, 85, 247, 0.24); }

.chunk-file-name {
  font-size: 12px; color: #94a3b8; white-space: nowrap;
  overflow: hidden; text-overflow: ellipsis; flex: 1; text-align: right;
}

.chunk-scores {
  display: flex; gap: 12px; margin-bottom: 10px;
  background: rgba(0,0,0,0.2); padding: 6px 10px; border-radius: 6px;
}
.score-item { display: flex; align-items: baseline; gap: 6px; font-size: 11px; }
.score-item .sl { color: #64748b; }
.score-item .sv { font-weight: 700; font-family: monospace; font-size: 13px; }
.highlight { color: #c4b5fd; }
.text-rose { color: #fb7185; }
.text-blue { color: #60a5fa; }

.chunk-body {
  font-size: 13px; color: #cbd5e1; line-height: 1.6;
  background: rgba(255,255,255,0.02); padding: 8px 10px; border-radius: 6px;
}
.chunk-footer {
  margin-top: 8px; font-size: 11px; color: #10b981; font-weight: 500;
}

/* ====== Section 3: 树形结构 ====== */
.tree-box {
  background: #202027; border: 1px solid rgba(255,255,255,0.04);
  border-radius: 8px; padding: 12px; max-height: 300px; overflow: auto;
}
/* 覆盖 Naive UI 树组件的默认颜色以适应暗黑主题 */
:deep(.n-tree) { color: #cbd5e1; font-size: 13px; }
:deep(.n-tree-node-content__text) { color: #cbd5e1; }
:deep(.n-tree-node--selected .n-tree-node-content__text) { color: #a78bfa !important; font-weight: 600; }

.sel-chunk {
  background: rgba(139, 92, 246, 0.1); border: 1px solid rgba(139, 92, 246, 0.2);
  border-radius: 8px; padding: 14px; margin-top: 12px;
}
.sel-chunk-title { font-size: 13px; font-weight: 600; color: #c4b5fd; margin-bottom: 8px; }
.chunk-full-text { font-size: 13px; line-height: 1.7; color: #e2e8f0; max-height: 250px; overflow-y: auto; }

/* 动画过渡 */
.rd-enter-active, .rd-leave-active { transition: opacity 0.3s ease; }
.rd-enter-active .rd-panel, .rd-leave-active .rd-panel { transition: transform 0.3s cubic-bezier(0.2, 0.8, 0.2, 1); }
.rd-enter-from, .rd-leave-to { opacity: 0; }
.rd-enter-from .rd-panel, .rd-leave-to .rd-panel { transform: translateX(100%); }
</style>