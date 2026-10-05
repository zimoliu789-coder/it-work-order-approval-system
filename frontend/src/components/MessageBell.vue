<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { messageApi } from '@/api/message'
import { messageTargetRoute, messageTagType } from '@/types/message'
import type { MessageItem } from '@/types/message'

/**
 * 消息铃铛（ 右上角消息铃铛、 站内消息通知系统）
 *
 * 能力：
 * - 未读红点：进入布局时拉一次，之后按 {@link POLL_INTERVAL_MS} 轮询（失败静默，不打扰用户）；
 * - 点击铃铛：拉最近若干条消息展示，点某条 → 标记已读 + 跳转到对应处理页；
 * - 「全部已读」：一次标记全部未读。
 *
 * 说明：后端消息端点只操作「当前登录者自己的消息」，前端无需也无法指定 userId。
 * 轮询采用「静默失败」：网络抖动或会话过期时不弹错误提示（会话过期由请求层统一处理跳登录）。
 */

/** 轮询间隔：60 秒。消息非强实时场景，此频率足以兼顾及时性与服务端压力 */
const POLL_INTERVAL_MS = 60_000

/** 面板里最多展示的条数 */
const PANEL_SIZE = 8

const router = useRouter()

const unreadCount = ref(0)
const panelVisible = ref(false)
const loading = ref(false)
const messages = ref<MessageItem[]>([])
const markingAll = ref(false)

let timer: number | null = null

const tipText = computed(() =>
  unreadCount.value > 0 ? `您有 ${unreadCount.value} 条未读消息` : '暂无未读消息'
)

onMounted(() => {
  void refreshUnread()
  timer = window.setInterval(() => void refreshUnread(), POLL_INTERVAL_MS)
})

onUnmounted(() => {
  if (timer !== null) {
    window.clearInterval(timer)
    timer = null
  }
})

watch(panelVisible, (open) => {
  if (open) {
    void loadMessages()
  }
})

/** 拉取未读数（静默失败） */
async function refreshUnread(): Promise<void> {
  try {
    unreadCount.value = await messageApi.unreadCount()
  } catch {
    // 静默：铃铛是辅助信息，失败不打扰用户
  }
}

/** 拉取最近消息 */
async function loadMessages(): Promise<void> {
  loading.value = true
  try {
    const result = await messageApi.mine({ page: 1, size: PANEL_SIZE })
    messages.value = result.records
  } catch {
    messages.value = []
  } finally {
    loading.value = false
  }
}

/**
 * 消息类型 → 点击后跳转的处理页。
 *
 * 映射表已抽到 `@/types/message`（纯函数、有单测），此处只做调用 ——
 * 避免「新增消息类型忘记补映射」静默把用户带到无关页面。
 */
const targetRoute = messageTargetRoute

async function handleClick(item: MessageItem): Promise<void> {
  if (item.isRead !== true) {
    try {
      await messageApi.markRead(item.id)
      item.isRead = true
      unreadCount.value = Math.max(0, unreadCount.value - 1)
    } catch {
      // 已读失败不阻断跳转
    }
  }
  panelVisible.value = false
  void router.push(targetRoute(item.messageType))
}

async function handleMarkAllRead(): Promise<void> {
  if (unreadCount.value === 0) {
    return
  }
  markingAll.value = true
  try {
    await messageApi.markAllRead()
    ElMessage.success('已全部标记为已读')
    messages.value = messages.value.map((item) => ({ ...item, isRead: true }))
    unreadCount.value = 0
  } catch {
    // 错误由请求层统一提示
  } finally {
    markingAll.value = false
  }
}
</script>

<template>
  <el-popover
    v-model:visible="panelVisible"
    placement="bottom-end"
    :width="340"
    trigger="click"
    popper-class="ts-message-popover"
  >
    <template #reference>
      <button class="ts-bell" type="button" aria-label="站内消息">
        <el-badge :value="unreadCount" :hidden="unreadCount === 0" :max="99">
          <el-icon :size="20"><Bell /></el-icon>
        </el-badge>
      </button>
    </template>

    <div class="ts-message-panel">
      <div class="ts-flex-between ts-message-panel__head">
        <span class="ts-message-panel__title">站内消息</span>
        <el-button
          link
          type="primary"
          size="small"
          :disabled="unreadCount === 0"
          :loading="markingAll"
          @click="handleMarkAllRead"
        >
          全部已读
        </el-button>
      </div>

      <div v-loading="loading" class="ts-message-panel__body">
        <ul v-if="messages.length > 0" class="ts-message-panel__list">
          <li
            v-for="item in messages"
            :key="item.id"
            class="ts-message-panel__item"
            :class="{ 'is-unread': item.isRead !== true }"
            @click="handleClick(item)"
          >
            <div class="ts-message-panel__item-head">
              <span class="ts-message-panel__item-title">{{ item.title }}</span>
              <el-badge v-if="item.isRead !== true" is-dot class="ts-message-panel__dot" />
            </div>
            <p class="ts-message-panel__item-content">{{ item.content }}</p>
            <div class="ts-message-panel__item-meta">
              <el-tag size="small" effect="plain" :type="messageTagType(item.messageType)">
                {{ item.messageTypeLabel ?? '消息' }}
              </el-tag>
              <span class="ts-text-hint">{{ item.createdAt }}</span>
            </div>
          </li>
        </ul>
        <el-empty v-else-if="!loading" :image-size="60">
          <template #description>
            <div class="ts-message-panel__empty">
              <p>{{ tipText }}</p>
              <p class="ts-text-hint">待办、审批结果、到期预警等消息会汇总到这里</p>
            </div>
          </template>
        </el-empty>
      </div>
    </div>
  </el-popover>
</template>

<style scoped>
.ts-bell {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: #5b6472;
  cursor: pointer;
}

.ts-bell:hover {
  background: rgba(31, 58, 95, 0.08);
}

.ts-message-panel__head {
  padding-bottom: 8px;
  border-bottom: 1px solid var(--ts-border);
}

.ts-message-panel__title {
  font-weight: 500;
}

.ts-message-panel__body {
  max-height: 380px;
  overflow-y: auto;
}

.ts-message-panel__list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.ts-message-panel__item {
  padding: 10px 6px;
  border-bottom: 1px solid var(--ts-border);
  cursor: pointer;
}

.ts-message-panel__item:last-child {
  border-bottom: none;
}

.ts-message-panel__item:hover {
  background: rgba(31, 58, 95, 0.04);
}

.ts-message-panel__item.is-unread .ts-message-panel__item-title {
  font-weight: 600;
}

.ts-message-panel__item-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.ts-message-panel__item-title {
  font-size: 13px;
  word-break: break-all;
}

.ts-message-panel__item-content {
  margin: 4px 0 6px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ts-text-secondary);
  word-break: break-all;
}

.ts-message-panel__item-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
}

.ts-message-panel__empty {
  text-align: center;
  line-height: 1.8;
  font-size: 12px;
  color: var(--ts-text-secondary);
}
</style>
