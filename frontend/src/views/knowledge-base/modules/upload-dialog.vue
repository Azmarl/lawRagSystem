<script setup lang="ts">
import type { UploadFileInfo } from 'naive-ui'
import {
  NAlert,
  NButton,
  NFormItem,
  NModal,
  NProgress,
  NRadioButton,
  NRadioGroup,
  NSelect,
  NSwitch,
  NUpload
} from 'naive-ui'
import SparkMD5 from 'spark-md5'
import { request } from '@/service/request'

defineOptions({ name: 'UploadDialog' })

interface Props {
  visible: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'update:visible', visible: boolean): void
  (e: 'success'): void
}>()

const CHUNK_SIZE = 5 * 1024 * 1024
const DEFAULT_FILE_ACCEPT = '.pdf,.doc,.docx,.txt,.md'

const documentType = ref<'STRUCTURED_LAW' | 'PRIVATE_LEGAL'>('STRUCTURED_LAW')
const orgTag = ref('')
const isPublic = ref(false)
const selectedFile = ref<File | null>(null)
const uploading = ref(false)
const progress = ref(0)
const configLoading = ref(false)
const orgTags = ref<string[]>([])
const privateOrgTag = ref('')

const isPrivateDocument = computed(() => documentType.value === 'PRIVATE_LEGAL')

const orgOptions = computed(() =>
  Array.from(new Set([...orgTags.value, privateOrgTag.value].filter(Boolean))).map(tag => ({
    label: tag === privateOrgTag.value ? `${tag}（私人空间）` : tag,
    value: tag
  }))
)

const canSubmit = computed(() => Boolean(selectedFile.value) && !uploading.value && Boolean(orgTag.value))

function closeDialog() {
  if (uploading.value) return
  emit('update:visible', false)
}

async function loadUploadConfig() {
  configLoading.value = true
  try {
    const { data, error } = await request<Api.KnowledgeBase.UploadConfig>({ url: '/users/upload-orgs' })
    if (error || !data) return
    orgTags.value = data.orgTags || []
    privateOrgTag.value = data.privateOrgTag || ''
    orgTag.value = data.primaryOrg || data.orgTags?.[0] || ''
    applyDocumentTypeDefaults()
  } finally {
    configLoading.value = false
  }
}

function applyDocumentTypeDefaults() {
  if (isPrivateDocument.value) {
    isPublic.value = false
    orgTag.value = privateOrgTag.value || orgTag.value
  } else if (!orgTag.value) {
    orgTag.value = orgTags.value[0] || ''
  }
}

watch(documentType, () => applyDocumentTypeDefaults())
watch(
  () => props.visible,
  visible => {
    if (visible) {
      selectedFile.value = null
      progress.value = 0
      documentType.value = 'STRUCTURED_LAW'
      isPublic.value = false
      loadUploadConfig()
    }
  }
)

async function handleFileChange(options: { file?: UploadFileInfo }) {
  selectedFile.value = (options.file?.file as File) || null
  progress.value = 0
}

async function calculateMD5(file: File): Promise<string> {
  const spark = new SparkMD5.ArrayBuffer()
  const chunkSize = 2 * 1024 * 1024
  let offset = 0
  while (offset < file.size) {
    const slice = file.slice(offset, offset + chunkSize)
    // 分片按顺序读取，保证 MD5 与文件字节顺序一致
    // eslint-disable-next-line no-await-in-loop
    const buffer = await slice.arrayBuffer()
    spark.append(buffer)
    offset += chunkSize
  }
  return spark.end()
}

async function uploadFile() {
  if (!selectedFile.value || !canSubmit.value) return
  uploading.value = true
  progress.value = 0
  try {
    const file = selectedFile.value
    const fileMd5 = await calculateMD5(file)
    const totalChunks = Math.max(1, Math.ceil(file.size / CHUNK_SIZE))

    for (let chunkIndex = 0; chunkIndex < totalChunks; chunkIndex += 1) {
      const start = chunkIndex * CHUNK_SIZE
      const chunk = file.slice(start, Math.min(file.size, start + CHUNK_SIZE))
      const formData = new FormData()
      formData.append('fileMd5', fileMd5)
      formData.append('chunkIndex', String(chunkIndex))
      formData.append('totalSize', String(file.size))
      formData.append('fileName', file.name)
      formData.append('totalChunks', String(totalChunks))
      formData.append('orgTag', orgTag.value)
      formData.append('isPublic', String(isPrivateDocument.value ? false : isPublic.value))
      formData.append('documentType', documentType.value)
      formData.append('file', chunk, file.name)

      // 分片必须串行上传，避免并发写入同一文件的分片状态
      // eslint-disable-next-line no-await-in-loop
      const { error } = await request({
        url: '/upload/chunk',
        method: 'post',
        data: formData
      })
      if (error) throw new Error(error.message || '分片上传失败')
      progress.value = Math.round(((chunkIndex + 1) / totalChunks) * 90)
    }

    const { error } = await request({
      url: '/upload/merge',
      method: 'post',
      data: { fileMd5, fileName: file.name }
    })
    if (error) throw new Error(error.message || '文件合并失败')

    progress.value = 100
    window.$message?.success(
      isPrivateDocument.value ? '私人案件文档上传成功，正在解析入库' : '法律文档上传成功，正在解析入库'
    )
    emit('success')
    emit('update:visible', false)
  } catch (error: unknown) {
    window.$message?.error(error instanceof Error ? error.message : '文件上传失败')
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <NModal
    :show="props.visible"
    preset="card"
    title="上传文档"
    class="upload-dialog-card"
    :style="{ width: '640px', maxWidth: 'calc(100vw - 48px)' }"
    :mask-closable="!uploading"
    :closable="!uploading"
    @update:show="value => !value && closeDialog()"
  >
    <div class="upload-dialog-body flex flex-col gap-18px">
      <NFormItem label="文档类型" required>
        <NRadioGroup v-model:value="documentType" :disabled="uploading">
          <NRadioButton value="STRUCTURED_LAW">法律文档</NRadioButton>
          <NRadioButton value="PRIVATE_LEGAL">私人案件文档</NRadioButton>
        </NRadioGroup>
      </NFormItem>

      <NAlert v-if="isPrivateDocument" type="warning" :bordered="false">
        私人案件文档默认归属你的私人组织，仅你本人可访问；禁止设置为公开，也不会进入公开法典检索范围。
      </NAlert>

      <NFormItem label="归属组织" required>
        <NSelect
          v-model:value="orgTag"
          :options="orgOptions"
          :loading="configLoading"
          :disabled="uploading || isPrivateDocument"
          placeholder="请选择归属组织"
        />
      </NFormItem>

      <NFormItem label="公开范围">
        <div class="flex items-center gap-8px">
          <NSwitch v-model:value="isPublic" :disabled="uploading || isPrivateDocument" />
          <span class="text-3 text-stone-500">
            {{ isPrivateDocument ? '私人案件文档固定为私有' : isPublic ? '组织内公开' : '仅组织内可见' }}
          </span>
        </div>
      </NFormItem>

      <NFormItem label="选择文件" required>
        <NUpload
          :default-upload="false"
          :max="1"
          :accept="DEFAULT_FILE_ACCEPT"
          :disabled="uploading"
          @change="handleFileChange"
        >
          <NButton :disabled="uploading">{{ selectedFile ? '重新选择文件' : '选择文件' }}</NButton>
        </NUpload>
        <div v-if="selectedFile" class="mt-2 text-3 text-stone-500">
          已选择：{{ selectedFile.name }}（{{ (selectedFile.size / 1024 / 1024).toFixed(2) }} MB）
        </div>
      </NFormItem>

      <NProgress v-if="uploading || progress > 0" type="line" :percentage="progress" :processing="uploading" />
    </div>

    <template #footer>
      <div class="flex justify-end gap-12px">
        <NButton :disabled="uploading" @click="closeDialog">取消</NButton>
        <NButton type="primary" :loading="uploading" :disabled="!canSubmit" @click="uploadFile">开始上传</NButton>
      </div>
    </template>
  </NModal>
</template>

<style scoped>
.upload-dialog-card {
  width: 640px;
  max-width: calc(100vw - 48px);
  border-radius: 14px;
}

.upload-dialog-body {
  padding: 4px 4px 0;
}

.upload-dialog-body :deep(.n-form-item) {
  margin-bottom: 2px;
}

.upload-dialog-body :deep(.n-form-item-label) {
  font-weight: 600;
}

.upload-dialog-body :deep(.n-upload) {
  width: 100%;
}

@media (max-width: 640px) {
  .upload-dialog-card {
    width: calc(100vw - 32px);
    max-width: calc(100vw - 32px);
  }

  .upload-dialog-body {
    padding: 0;
  }
}
</style>
