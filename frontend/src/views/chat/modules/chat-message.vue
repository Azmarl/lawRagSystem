<script setup lang="ts">
import { router } from '@/router'
import { request } from '@/service/request'
import { formatDate } from '@/utils/common'
import { VueMarkdownIt } from '@/vendor/vue-markdown-shiki'
defineOptions({ name: 'ChatMessage' });

const props = defineProps<{
  msg: Api.Chat.Message,
  sessionId?: string,
  retrievalQueryFallback?: string
}>();

const emit = defineEmits<{
  (e: 'open-retrieval', results: Api.Chat.RetrievalResult[]): void;
}>();

const drawerState = inject<any>('retrievalDrawerState', null);

/**
 * 从 referenceMappings 合成 retrievalResults（用于重连/历史加载后的数据恢复）。
 */
function buildRetrievalResultItem(
  ref: Record<string, any>,
  key: number
): Api.Chat.RetrievalResult {
  return {
    fileMd5: ref.fileMd5,
    fileName: ref.fileName,
    chunkId: ref.chunkId ?? key,
    score: ref.score ?? 0,
    bm25NormScore: ref.bm25NormScore ?? null,
    knnNormScore: ref.knnNormScore ?? null,
    alpha: ref.alpha ?? null,
    hitReason: ref.hitReason ?? null,
    retrievalMode: ref.retrievalMode ?? null,
    textContent: ref.matchedChunkText ?? ref.textContent ?? null,
    pageNumber: ref.pageNumber ?? null,
    anchorText: ref.anchorText ?? null,
    chunkLevel: ref.chunkLevel ?? null,
    partName: ref.partName ?? null,
    chapterName: ref.chapterName ?? null,
    sectionName: ref.sectionName ?? null,
    articleId: ref.articleId ?? null
  }
}

function synthesizeRetrievalResults(): Api.Chat.RetrievalResult[] {
  const refs = props.msg.referenceMappings
  if (!refs) return []
  const results: Api.Chat.RetrievalResult[] = []
  const keys = Object.keys(refs).map(Number).sort((a, b) => a - b)
  for (const key of keys) {
    const ref = refs[String(key)] as Record<string, any>
    if (ref && ref.fileMd5) {
      results.push(buildRetrievalResultItem(ref, key))
    }
  }
  return results
}

function getEffectiveRetrievalResults(): Api.Chat.RetrievalResult[] {
  return props.msg.retrievalResults && props.msg.retrievalResults.length > 0
    ? props.msg.retrievalResults
    : synthesizeRetrievalResults();
}

const hasRetrievalData = computed(() => {
  if (props.msg.retrievalResults && props.msg.retrievalResults.length > 0) return true;
  return synthesizeRetrievalResults().length > 0;
});

function handleOpenRetrievalTrace() {
  const results = getEffectiveRetrievalResults();
  if (results.length === 0) return;
  if (drawerState) {
    drawerState.results = results;
    drawerState.visible = true;
  } else {
    emit('open-retrieval', results);
  }
}

const authStore = useAuthStore();

function handleCopy(content: string) {
  navigator.clipboard.writeText(content);
  window.$message?.success('已复制');
}

const chatStore = useChatStore();

const sourceFiles = ref<Array<{fileName: string, id: string, referenceNumber: number, fileMd5?: string, pageNumber?: number}>>([]);
const bareUrlPattern = /https?:\/\/[A-Za-z0-9\-._~:/?#[\]@!$&'()*+,;=%]+/g

function splitTrailingUrlPunctuation(rawUrl: string) {
  let url = rawUrl;
  let trailing = '';
  while (url) {
    const lastChar = url.at(-1);
    if (!lastChar) break;
    if (/[，。！？；：、,.!?;:]/.test(lastChar)) {
      trailing = `${lastChar}${trailing}`;
      url = url.slice(0, -1);
    } else if (lastChar === ')' || lastChar === '）') {
      const openingChar = lastChar === ')' ? '(' : '（';
      const openingCount = (url.match(new RegExp(`\\${openingChar}`, 'g')) || []).length;
      const closingCount = (url.match(new RegExp(`\\${lastChar === ')' ? ')' : '）'}`, 'g')) || []).length;
      if (closingCount > openingCount) {
        trailing = `${lastChar}${trailing}`;
        url = url.slice(0, -1);
      } else break;
    } else break;
  }
  return { url, trailing };
}

function normalizeBareUrls(text: string) {
  return text.replace(bareUrlPattern, (match, offset: number, source: string) => {
    const previousChar = source[offset - 1] || '';
    const previousTwoChars = source.slice(Math.max(0, offset - 2), offset);
    const previousTenChars = source.slice(Math.max(0, offset - 10), offset).toLowerCase();
    if (previousChar === '<' || previousTwoChars === '](' || /(?:href|src)=["']?$/.test(previousTenChars)) return match;
    const { url, trailing } = splitTrailingUrlPunctuation(match);
    return url ? `<${url}>${trailing}` : match;
  });
}

function createSourceLink(
  sourceNum: string,
  fileName: string,
  extras?: { fileMd5?: string; pageNumber?: number; displayName?: string }
): string {
  const trimmedFileName = fileName.trim();
  const fileId = `source-file-${sourceFiles.value.length}`;
  sourceFiles.value.push({
    fileName: trimmedFileName, id: fileId,
    referenceNumber: Number.parseInt(sourceNum, 10),
    fileMd5: extras?.fileMd5, pageNumber: extras?.pageNumber
  });
  return `来源#${sourceNum}: <span class="source-file-link" data-file-id="${fileId}">${extras?.displayName || trimmedFileName}</span>`;
}

function processSourceLinks(text: string): string {
  sourceFiles.value = [];
  const entryBoundary = '(?=\\s*(?:[;；,，、。！？!?\\)）]|$))';
  let processed = text.replace(
    new RegExp(`来源#(\\d+):\\s*([^|;；,，、。！？!?\\n\\r]+?)\\s*\\|\\s*第(\\d+)页${entryBoundary}`, 'g'),
    (...args: string[]) => {
      const sn = args[1]
      const fn = args[2]
      const pn = args[3]
      return createSourceLink(sn, fn, {
        pageNumber: Number.parseInt(pn, 10),
        displayName: `${fn.trim()} (第${pn}页)`
      })
    }
  );
  processed = processed.replace(
    new RegExp(`来源#(\\d+):\\s*([^|;；,，、。！？!?\\n\\r]+?)\\s*\\|\\s*MD5:\\s*([a-fA-F0-9]+)${entryBoundary}`, 'g'),
    (...args: string[]) => {
      const sn = args[1]
      const fn = args[2]
      const md5 = args[3]
      return createSourceLink(sn, fn, { fileMd5: md5.trim() })
    }
  );
  processed = processed.replace(
    new RegExp(`来源#(\\d+):\\s*([^<>\\n\\r|;；,，、。！？!?]+?)${entryBoundary}`, 'g'),
    (_m, sn, fn) => createSourceLink(sn, fn)
  );
  return processed;
}

const content = computed(() => {
  chatStore.scrollToBottom?.();
  const rawContent = props.msg.content ?? '';
  return props.msg.role === 'assistant' ? normalizeBareUrls(processSourceLinks(rawContent)) : rawContent;
});

function extractContextAnchorText(target: HTMLElement) {
  const scope = target.closest('li, p, blockquote, td, th');
  return (scope?.textContent?.replace(/\s+/g, ' ').trim() || '').split(/(?:\(|（)?来源#\d+:/)[0]
    .replace(/^\s*\d+\.\s*/, '').replace(/[（(]\s*$/, '').replace(/\s+/g, ' ').trim();
}

function openReferencePreviewPage(payload: Record<string, any>) {
  const previewKey = `reference-preview:${Date.now()}:${Math.random().toString(36).slice(2, 8)}`;
  localStorage.setItem(previewKey, JSON.stringify(payload));
  window.open(router.resolve({ path: '/chat', query: { preview: 'reference', previewKey } }).href, '_blank', 'noopener,noreferrer');
}

function handleContentClick(event: MouseEvent) {
  const target = event.target as HTMLElement;
  if (target.classList.contains('source-file-link')) {
    const file = sourceFiles.value.find(f => f.id === target.getAttribute('data-file-id'));
    if (file) handleSourceFileClick({
      fileName: file.fileName, referenceNumber: file.referenceNumber,
      fileMd5: file.fileMd5, anchorText: extractContextAnchorText(target)
    });
  }
}

async function fetchReferenceDetailFromApi(
  sessionId: string,
  referenceNumber: number
): Promise<Api.Document.ReferenceDetailResponse | null> {
  try {
    const res = await request<Api.Document.ReferenceDetailResponse>({
      url: 'documents/reference-detail',
      params: { sessionId, referenceNumber: referenceNumber.toString() },
      baseURL: '/proxy-api'
    })
    if (!res.error && res.data?.fileMd5) {
      return res.data
    }
  } catch (e) {
    console.warn('通过API查询引用详情失败:', e)
  }
  return null
}

// eslint-disable-next-line complexity
async function handleSourceFileClick(fileInfo: {
  fileName: string
  referenceNumber: number
  fileMd5?: string
  anchorText?: string
}) {
  const { fileName, referenceNumber, fileMd5: extractedMd5, anchorText: clickedAnchorText } =
    fileInfo
  const persistedDetail =
    props.msg.referenceMappings?.[String(referenceNumber)] ||
    props.msg.referenceMappings?.[referenceNumber]
  const refSessionId = props.msg.generationId || props.msg.conversationId || props.sessionId

  try {
    let detail: Api.Document.ReferenceDetailResponse | null = null
    if (
      refSessionId &&
      (!persistedDetail?.retrievalQuery ||
        !persistedDetail?.matchedChunkText ||
        !persistedDetail?.evidenceSnippet)
    ) {
      detail = await fetchReferenceDetailFromApi(refSessionId, referenceNumber)
    }

    const source = detail || (persistedDetail as any)
    const effectiveMd5 = source?.fileMd5 || extractedMd5 || null
    const effectiveAnchor = source?.anchorText || clickedAnchorText || ''

    openReferencePreviewPage({
      fileName: source?.fileName || fileName,
      fileMd5: effectiveMd5,
      pageNumber: source?.pageNumber,
      anchorText: effectiveAnchor,
      retrievalMode: source?.retrievalMode,
      retrievalLabel: source?.retrievalLabel,
      retrievalQuery: source?.retrievalQuery || props.retrievalQueryFallback || '',
      evidenceSnippet: source?.evidenceSnippet,
      matchedChunkText: source?.matchedChunkText,
      score: source?.score,
      chunkId: source?.chunkId,
      sessionId: refSessionId,
      referenceNumber
    })
  } catch (err) {
    console.error('文件下载失败:', err)
    window.$message?.error(`文件下载失败: ${fileName}`)
  }
}
</script>

<template>
  <div class="mb-8 flex-col gap-2">
    <div v-if="msg.role === 'user'" class="flex items-center gap-4">
      <NAvatar class="bg-success">
        <SvgIcon icon="ph:user-circle" class="text-icon-large color-white" />
      </NAvatar>
      <div class="flex-col gap-1">
        <NText class="text-4 font-bold">{{ msg.username || authStore.userInfo.username }}</NText>
        <NText class="text-3 color-gray-500">{{ formatDate(msg.timestamp) }}</NText>
      </div>
    </div>
    <div v-else class="flex items-center gap-4">
      <NAvatar class="bg-primary">
        <SystemLogo class="text-6 text-white" />
      </NAvatar>
      <div class="flex-col gap-1">
        <NText class="text-4 font-bold">律法知识检索系统</NText>
        <NText class="text-3 color-gray-500">{{ formatDate(msg.timestamp) }}</NText>
      </div>
    </div>
    <NText v-if="msg.status === 'pending'">
      <icon-eos-icons:three-dots-loading class="ml-12 mt-2 text-8" />
    </NText>
    <NText v-else-if="msg.status === 'error'" class="ml-12 mt-2 italic color-#d03050">
      {{ msg.content || '服务器繁忙，请稍后再试' }}
    </NText>
    <div v-else-if="msg.role === 'assistant'" class="mt-2 pl-12" @click="handleContentClick">
      <VueMarkdownIt :content="content" />
    </div>
    <NText v-else-if="msg.role === 'user'" class="ml-12 mt-2 text-4">{{ content }}</NText>
    <NDivider class="ml-12 w-[calc(100%-3rem)] mb-0! mt-2!" />
    <div class="ml-12 flex gap-4">
      <NButton quaternary @click="handleCopy(msg.content)">
        <template #icon>
          <icon-mynaui:copy />
        </template>
      </NButton>
      <NButton
        v-if="hasRetrievalData"
        quaternary
        size="small"
        @click="handleOpenRetrievalTrace"
      >
        检索溯源
      </NButton>
    </div>
  </div>
</template>

<style scoped lang="scss">
:deep(.source-file-link) {
  color: #1890ff; cursor: pointer; text-decoration: underline;
  transition: color 0.2s;
  &:hover { color: #40a9ff; text-decoration: none; }
  &:active { color: #096dd9; }
}
</style>