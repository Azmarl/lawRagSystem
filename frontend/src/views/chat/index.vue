<script setup lang="ts">
import { computed, provide, reactive } from 'vue';
import { useRoute } from 'vue-router';
import ChatList from './modules/chat-list.vue';
import InputBox from './modules/input-box.vue';
import ConversationSidebar from './modules/conversation-sidebar.vue';
import ReferencePreviewPage from './modules/reference-preview-page.vue';
import RetrievalTraceDrawer from './modules/retrieval-trace-drawer.vue';

const route = useRoute();
const showReferencePreview = computed(() => route.query.preview === 'reference');

// Phase 4: 共享检索溯源抽屉状态 (provide/inject 跨组件)
const retrievalDrawerState = reactive({ visible: false, results: [] as Api.Chat.RetrievalResult[] });
provide('retrievalDrawerState', retrievalDrawerState);
</script>

<template>
  <div v-if="showReferencePreview" class="h-full">
    <ReferencePreviewPage />
  </div>
  <div v-else class="flex h-full gap-0">
    <ConversationSidebar />
    <div class="flex min-w-0 flex-1 flex-col bg-white dark:bg-[#1c1c1c]">
      <ChatList />
      <InputBox />
    </div>
  </div>

  <!-- Phase 4: 检索溯源抽屉 (Teleport to body, 页面级) -->
  <RetrievalTraceDrawer />
</template>

<style scoped></style>
