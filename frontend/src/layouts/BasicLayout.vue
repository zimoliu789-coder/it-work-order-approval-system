<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'
import { useSiteStore } from '@/store/site'
import { useResponsive } from '@/composables/useResponsive'
import metaApi from '@/api/meta'
import { applyMessageTypeMeta } from '@/types/message'
import { ROLE_LABELS } from '@/types/user'
import MenuTree from '@/components/MenuTree.vue'
import MessageBell from '@/components/MessageBell.vue'
import SiteLogo from '@/components/SiteLogo.vue'

/**
 * 主布局（ 前端 UI 规范）
 *
 * PC（≥1200px）：顶部用户区 + 左侧固定菜单 + 内容区
 * 平板（768-1199px）：左侧菜单默认折叠为图标
 * 手机（<768px）：不保留左侧栏，改用抽屉菜单
 */
const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
// 站点名称与 logo（）：由全局 store 提供 —— 管理员在系统参数页改完即刷新所有渲染点。
// 刻意不使用任何构建期常量（如环境变量）：那种常量在改名后不会变，改完还得重新构建才生效。
const siteStore = useSiteStore()
const { isMobile, isPc } = useResponsive()

const drawerVisible = ref(false)
const collapsed = ref(false)

// 平板默认折叠，PC 默认展开
watch(
  isPc,
  (value) => {
    collapsed.value = !value
  },
  { immediate: true }
)

// 手机端改用抽屉菜单，关闭折叠态
watch(isMobile, (value) => {
  if (value) {
    drawerVisible.value = false
  }
})

const activeMenu = computed(() => route.path)
const pageTitle = computed(() => (route.meta?.title as string | undefined) ?? '工作台')
const avatarText = computed(() => userStore.displayName.slice(0, 1) || '员')
const roleLabel = computed(() => ROLE_LABELS[userStore.role] ?? userStore.role)
const showAside = computed(() => !isMobile.value)
const asideCollapsed = computed(() => collapsed.value && !isMobile.value)

function toggleSidebar(): void {
  if (isMobile.value) {
    drawerVisible.value = !drawerVisible.value
  } else {
    collapsed.value = !collapsed.value
  }
}

function onMenuSelect(): void {
  if (isMobile.value) {
    drawerVisible.value = false
  }
}

async function handleUserCommand(command: string): Promise<void> {
  if (command === 'profile') {
    await router.push('/profile')
    return
  }
  if (command === 'password') {
    // 修改密码（ ；2026-09-20 改名）：进入布局内的自助改密页，
    // 而非独立无壳的强制改密页（后者 /change-password 只服务「首登强制改密」），
    // 两处共用同一套表单组件。
    // 菜单名由「密码管理」改为「修改密码」：管理员侧另有「重置密码」（替他人改），
    // 原名与它只差「管理/重置」两字，用户分不清是「改自己的」还是「改别人的」。
    await router.push('/profile/password')
    return
  }
  if (command === 'logout') {
    try {
      await ElMessageBox.confirm('确认退出登录吗？', '退出确认', {
        confirmButtonText: '退出登录',
        cancelButtonText: '取消',
        type: 'warning'
      })
    } catch {
      return
    }
    await userStore.logout()
    await router.replace('/login')
  }
}

/**
 * 拉取消息类型元数据（）。
 *
 * 布局挂载即代表已登录，此处拉一次即可覆盖当前会话的铃铛与消息中心。
 * 失败不提示、不阻断 —— 前端保留内置兜底表，元数据接口只是「让映射不再漂移」的优化。
 */
async function loadMeta(): Promise<void> {
  try {
    applyMessageTypeMeta(await metaApi.messageTypes())
  } catch {
    // 忽略：使用内置兜底
  }
}

onMounted(() => {
  void loadMeta()
})
</script>

<template>
  <div class="ts-layout">
    <!-- PC / 平板：常驻侧边栏 -->
    <aside v-if="showAside" class="ts-layout__aside" :class="{ 'is-collapsed': asideCollapsed }">
      <div class="ts-brand">
        <span class="ts-brand__mark"><SiteLogo /></span>
        <span v-show="!asideCollapsed" class="ts-brand__text">{{ siteStore.siteName }}</span>
      </div>
      <el-scrollbar class="ts-aside__scroll">
        <el-menu
          :default-active="activeMenu"
          :collapse="asideCollapsed"
          :collapse-transition="false"
          unique-opened
          router
          background-color="#1f3a5f"
          text-color="#cbd5e1"
          active-text-color="#ffffff"
        >
          <el-menu-item index="/dashboard">
            <el-icon><HomeFilled /></el-icon>
            <template #title><span>工作台</span></template>
          </el-menu-item>
          <MenuTree :items="userStore.menus" />
        </el-menu>
      </el-scrollbar>
      <!--
        侧边栏底部版权（）：只有管理员在系统参数里填了版权文字才渲染。
        折叠态（64px）不显示 —— 那一行小字在 64px 里会被压成一条竖排乱码。
      -->
      <footer v-if="siteStore.siteCopyright && !asideCollapsed" class="ts-aside__foot">
        {{ siteStore.siteCopyright }}
      </footer>
    </aside>

    <!-- 手机：抽屉菜单 -->
    <el-drawer
      v-if="isMobile"
      v-model="drawerVisible"
      direction="ltr"
      :with-header="false"
      size="248px"
      class="ts-drawer"
    >
      <div class="ts-brand">
        <span class="ts-brand__mark"><SiteLogo /></span>
        <span class="ts-brand__text">{{ siteStore.siteName }}</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        unique-opened
        router
        background-color="#1f3a5f"
        text-color="#cbd5e1"
        active-text-color="#ffffff"
        @select="onMenuSelect"
      >
        <el-menu-item index="/dashboard">
          <el-icon><HomeFilled /></el-icon>
          <template #title><span>工作台</span></template>
        </el-menu-item>
        <MenuTree :items="userStore.menus" />
      </el-menu>
    </el-drawer>

    <div class="ts-layout__body">
      <header class="ts-layout__header">
        <button class="ts-icon-btn" type="button" aria-label="切换菜单" @click="toggleSidebar">
          <el-icon :size="20">
            <Expand v-if="asideCollapsed || isMobile" />
            <Fold v-else />
          </el-icon>
        </button>

        <span class="ts-layout__title">{{ pageTitle }}</span>

        <div class="ts-layout__spacer" />

        <MessageBell />

        <el-dropdown trigger="click" @command="handleUserCommand">
          <span class="ts-user">
            <el-avatar :size="28" class="ts-user__avatar">{{ avatarText }}</el-avatar>
            <span class="ts-user__name ts-pc-only">{{ userStore.displayName }}</span>
            <el-icon :size="12"><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item disabled>{{ userStore.user?.username }}（{{ roleLabel }}）</el-dropdown-item>
              <el-dropdown-item command="profile" divided>个人中心</el-dropdown-item>
              <el-dropdown-item command="password">修改密码</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </header>

      <main class="ts-layout__content">
        <router-view />
      </main>

      <footer class="ts-layout__footer ts-mobile-only">
        <span>{{ siteStore.siteName }}</span>
        <!-- 版权文字（）：没填就不渲染这一行 -->
        <span v-if="siteStore.siteCopyright" class="ts-layout__copyright">
          {{ siteStore.siteCopyright }}
        </span>
      </footer>
    </div>
  </div>
</template>

<style scoped>
.ts-layout {
  display: flex;
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
  background: var(--ts-bg);
}

.ts-layout__aside {
  display: flex;
  flex-direction: column;
  width: var(--ts-sidebar-width);
  flex: 0 0 var(--ts-sidebar-width);
  background: #1f3a5f;
  transition: width 0.2s ease, flex-basis 0.2s ease;
}

.ts-layout__aside.is-collapsed {
  width: 64px;
  flex-basis: 64px;
}

.ts-brand {
  display: flex;
  align-items: center;
  gap: 8px;
  height: var(--ts-header-height);
  padding: 0 16px;
  color: #fff;
  white-space: nowrap;
  overflow: hidden;
  flex: 0 0 auto;
}

.ts-brand__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 7px;
  background: #2f5b8f;
  font-size: 12px;
  font-weight: 500;
  flex: 0 0 auto;
}

.ts-brand__text {
  font-size: 14px;
  font-weight: 500;
}

.ts-aside__scroll {
  flex: 1 1 auto;
  min-height: 0;
}

/*
 * 侧边栏底部版权（）。
 * 深色底（#1f3a5f）上用小字 + 半透明白：既能在深底上读清，
 * 又不会把视线从菜单上抢走 —— 它是一行声明，不是导航。
 */
.ts-aside__foot {
  flex: 0 0 auto;
  padding: 10px 16px 12px;
  font-size: 11px;
  line-height: 1.5;
  color: rgb(255 255 255 / 55%);
  border-top: 1px solid rgb(255 255 255 / 10%);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ts-layout__aside :deep(.el-menu) {
  border-right: none;
}

.ts-layout__body {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-width: 0;
}

.ts-layout__header {
  display: flex;
  align-items: center;
  gap: 8px;
  height: var(--ts-header-height);
  flex: 0 0 auto;
  padding: 0 16px;
  background: var(--ts-surface);
  border-bottom: 1px solid var(--ts-border);
}

.ts-layout__title {
  font-size: 15px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ts-layout__spacer {
  flex: 1 1 auto;
}

.ts-layout__content {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  -webkit-overflow-scrolling: touch;
}

.ts-layout__footer {
  flex: 0 0 auto;
  padding: 8px 16px calc(8px + var(--ts-safe-bottom));
  text-align: center;
  font-size: 12px;
  color: var(--ts-text-hint);
  background: var(--ts-surface);
  border-top: 1px solid var(--ts-border);
}

/* 移动端页脚的第二行：版权文字（）单独占一行，不与系统名称挤在一起 */
.ts-layout__copyright {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  color: var(--ts-text-secondary);
}

.ts-icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  padding: 0;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--ts-text-secondary);
  cursor: pointer;
}

.ts-icon-btn:hover {
  background: rgba(31, 58, 95, 0.08);
}

.ts-user {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 40px;
  padding: 0 6px;
  border-radius: 8px;
  cursor: pointer;
  color: var(--ts-text);
  outline: none;
}

.ts-user:hover {
  background: rgba(31, 58, 95, 0.08);
}

.ts-user__avatar {
  background: var(--ts-primary);
  font-size: 13px;
}

.ts-user__name {
  max-width: 96px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 767px) {
  .ts-layout__title {
    font-size: 14px;
  }
}
</style>

<style>
/* 抽屉菜单为 teleport 渲染，需使用非 scoped 样式覆盖 */
.ts-drawer .el-drawer__body {
  padding: 0;
  background: #1f3a5f;
}
</style>
