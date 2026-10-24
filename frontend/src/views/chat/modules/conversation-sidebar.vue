<script setup lang="ts">
import { NPopconfirm, NScrollbar } from 'naive-ui';

defineOptions({
  name: 'ConversationSidebar'
});

const chatStore = useChatStore();
const { conversationId, sessionsLoading, filteredSessions, activeTab } = storeToRefs(chatStore);

const collapsed = ref(false);

onMounted(() => {
  chatStore.loadSessions();
});

function handleNewChat() {
  chatStore.createNewSession();
}

function handleSelect(cid: string) {
  chatStore.switchSession(cid);
}

function handleArchive(cid: string) {
  chatStore.archiveSession(cid);
}

function handleUnarchive(cid: string) {
  chatStore.unarchiveSession(cid);
}

function setActiveTab(tab: 'active' | 'archived') {
  activeTab.value = tab;
}

function formatDate(dateStr?: string) {
  if (!dateStr) return '';
  const date = dayjs(dateStr);
  const now = dayjs();
  if (date.isSame(now, 'day')) {
    return date.format('HH:mm');
  }
  if (date.isSame(now, 'week')) {
    return date.format('ddd');
  }
  if (date.isSame(now, 'year')) {
    return date.format('MM-DD');
  }
  return date.format('YYYY-MM-DD');
}
</script>

<template>
  <div
    class="relative flex h-full flex-col border-r border-[rgb(var(--border-color)/0.3)] bg-white transition-all duration-300 dark:bg-[#1a1a1a]"
    :class="collapsed ? 'w-64px min-w-64px overflow-hidden' : 'w-[300px] min-w-[300px]'"
  >
    <!-- Collapsed rail：参照 global-sidebar 收起态设计 -->
    <div v-if="collapsed" class="flex min-h-0 w-full flex-1 flex-col items-center">
      <div class="flex flex-col items-center gap-1 pb-8px pt-8px">
        <MenuToggler arrow-icon :collapsed="true" @click="collapsed = false" />
        <button
          type="button"
          class="h-48px w-48px flex-col-center cursor-pointer rounded-8px transition-300 hover:bg-[rgb(var(--primary-color)/0.08)]"
          title="新对话"
          @click="handleNewChat"
        >
          <icon-material-symbols:add-rounded class="text-icon-large text-[rgb(var(--primary-color))]" />
        </button>
      </div>

      <!-- 会话图标导航：收起后仍可切换对话 -->
      <div class="flex min-h-0 w-full flex-1 flex-col overflow-hidden pb-8px">
        <NScrollbar class="w-full">
          <div class="flex flex-col items-center">
            <div
              v-for="session in filteredSessions"
              :key="session.conversationId"
              class="mb-6px flex h-40px w-40px flex-col-center cursor-pointer rounded-8px transition-300"
              :class="session.conversationId === conversationId
                ? 'bg-[rgb(var(--primary-color)/0.1)] text-[rgb(var(--primary-color))]'
                : 'text-#999 hover:bg-[rgb(0,0,0,0.08)] dark:text-#777 dark:hover:bg-[#FFFFFF0A]'"
              :title="session.title"
              @click="handleSelect(session.conversationId)"
            >
              <icon-material-symbols:chat-outline-rounded class="text-22px" />
            </div>
          </div>
        </NScrollbar>
      </div>
    </div>

    <div v-else class="flex items-center justify-between px-4 pt-4 pb-2">
      <span class="text-15px font-600">对话列表</span>
      <div class="flex items-center gap-1">
        <NButton
          type="primary"
          size="small"
          secondary
          @click="handleNewChat"
        >
          <template #icon>
            <icon-material-symbols:add-rounded />
          </template>
          新对话
        </NButton>
        <NButton text size="tiny" @click="collapsed = true">
          <template #icon>
            <icon-material-symbols:chevron-left-rounded />
          </template>
        </NButton>
      </div>
    </div>

    <!-- Tabs -->
    <div v-if="!collapsed" class="mx-4 mb-2 flex rounded-lg bg-[rgb(var(--border-color)/0.15)] p-0.5 dark:bg-[#FFFFFF0A]">
      <div
        class="flex-1 cursor-pointer rounded-md px-3 py-1.5 text-center text-12px font-500 transition-all"
        :class="activeTab === 'active'
          ? 'bg-white text-[rgb(var(--primary-color))] shadow-sm dark:bg-[#FFFFFF14] dark:text-(--primary-color)'
          : 'text-#666 dark:text-#999'"
        @click="setActiveTab('active')"
      >
        活跃
      </div>
      <div
        class="flex-1 cursor-pointer rounded-md px-3 py-1.5 text-center text-12px font-500 transition-all"
        :class="activeTab === 'archived'
          ? 'bg-white text-[rgb(var(--primary-color))] shadow-sm dark:bg-[#FFFFFF14] dark:text-(--primary-color)'
          : 'text-#666 dark:text-#999'"
        @click="setActiveTab('archived')"
      >
        已归档
      </div>
    </div>

    <!-- List -->
    <div v-if="!collapsed" class="flex min-h-0 flex-1 overflow-hidden">
      <NScrollbar class="flex-1 px-2">
        <NSpin :show="sessionsLoading" class="h-full">
          <TransitionGroup name="session-list" tag="div">
            <div
              v-if="filteredSessions.length === 0 && !sessionsLoading"
              class="flex flex-col items-center justify-center gap-3 py-16"
            >
              <icon-material-symbols:chat-outline-rounded class="text-36px color-#ccc dark:color-#444" />
              <span class="text-13px color-#aaa">{{ activeTab === 'active' ? '暂无对话记录' : '暂无归档对话' }}</span>
            </div>

            <div
              v-for="session in filteredSessions"
              :key="session.conversationId"
              class="group mx-1 mb-0.5 flex cursor-pointer items-center gap-2.5 rounded-lg px-3 py-2.5 transition-all"
              :class="session.conversationId === conversationId
                ? 'bg-[rgb(var(--primary-color)/0.08)]'
                : 'hover:bg-[rgb(var(--border-color)/0.3)] dark:hover:bg-[#FFFFFF08]'"
              @click="handleSelect(session.conversationId)"
            >
              <div class="shrink-0 flex h-7 w-7 items-center justify-center rounded-lg text-15px"
                :class="session.conversationId === conversationId
                  ? 'bg-[rgb(var(--primary-color)/0.12)] text-[rgb(var(--primary-color))]'
                  : 'bg-[rgb(var(--border-color)/0.2)] text-#999 dark:bg-[#FFFFFF0A]'"
              >
                <icon-material-symbols:chat-outline-rounded />
              </div>
              <div class="min-w-0 flex-1">
                <div
                  class="truncate text-13px font-500"
                  :class="session.conversationId === conversationId ? 'text-[rgb(var(--primary-color))]' : ''"
                >
                  {{ session.title }}
                </div>
                <div class="text-11px color-#aaa">{{ formatDate(session.updatedAt) }}</div>
              </div>

              <!-- Action button -->
              <NPopconfirm v-if="activeTab === 'active'" @positive-click="handleArchive(session.conversationId)">
                <template #trigger>
                  <NButton
                    class="shrink-0 transition-opacity"
                    :class="session.conversationId === conversationId ? '' : 'opacity-0 group-hover:opacity-100'"
                    text
                    size="tiny"
                    @click.stop
                  >
                    <template #icon>
                      <icon-material-symbols:archive-outline-rounded class="text-15px color-#999 hover:color-#666" />
                    </template>
                  </NButton>
                </template>
                归档后可在「已归档」中找回
              </NPopconfirm>
              <NButton
                v-else
                class="shrink-0 opacity-0 transition-opacity group-hover:opacity-100"
                text
                size="tiny"
                @click.stop="handleUnarchive(session.conversationId)"
              >
                <template #icon>
                  <icon-material-symbols:unarchive-outline-rounded class="text-15px color-#999 hover:color-#666" />
                </template>
              </NButton>
            </div>
          </TransitionGroup>
        </NSpin>
      </NScrollbar>
    </div>

  </div>
</template>

<style scoped>
.session-list-enter-active,
.session-list-leave-active {
  transition: all 0.2s ease;
}
.session-list-enter-from,
.session-list-leave-to {
  opacity: 0;
  transform: translateX(-8px);
}
</style>
