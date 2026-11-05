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
import type { MenuItem } from '@/config/menus'

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

/**
 * 面包屑（2026-11 视觉重构新增）。
 *
 * <h2>为什么不是只显示当前页名</h2>
 * 这个系统是三层的：业务分组 → 页面 → 详情弹窗。只显示当前页名时，
 * 用户从站内消息 / 待办通知直接跳进来，会失去「我在哪一层」的位置感；
 * 而面包屑要回答的正是这个问题。
 *
 * <h2>为什么复用菜单树而不是另开一张路由标题表</h2>
 * 侧栏与面包屑必须同源：两处各维护一份，改菜单忘了改表格，就会出现
 * 「侧栏叫资产管理、面包屑叫设备台账」这种自相矛盾的界面。
 * 菜单树本来就有 path → title，直接沿树找路径即可。
 */
function findTrail(items: MenuItem[], path: string, trail: string[] = []): string[] {
  for (const item of items) {
    const next = [...trail, item.title]
    if (item.path === path) {
      return next
    }
    if (item.children?.length) {
      const found = findTrail(item.children, path, next)
      if (found.length > 0) {
        return found
      }
    }
  }
  return []
}

const breadcrumb = computed<string[]>(() => {
  const trail = findTrail(userStore.menus, route.path)
  // 菜单里没有的路径（工作台、详情页、错误页）退回标题本身 —— 绝不渲染空面包屑
  return trail.length > 0 ? trail : [pageTitle.value]
})
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

        <!-- 面包屑 + 当前页标题：面包屑答「我在哪一层」，标题答「这是哪一页」。
             移动端只显示当前项（见下方样式），避免把顶栏挤爆。 -->
        <nav class="ts-crumb" aria-label="当前位置">
          <template v-for="(item, index) in breadcrumb" :key="`${item}-${index}`">
            <span
              class="ts-crumb__item"
              :class="{ 'is-current': index === breadcrumb.length - 1 }"
            >{{ item }}</span>
            <span v-if="index < breadcrumb.length - 1" class="ts-crumb__sep" aria-hidden="true">/</span>
          </template>
        </nav>

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
  background: var(--ts-nav-bg);
  transition: width var(--ts-dur) var(--ts-ease), flex-basis var(--ts-dur) var(--ts-ease);
}

.ts-layout__aside.is-collapsed {
  width: var(--ts-sidebar-collapsed);
  flex-basis: var(--ts-sidebar-collapsed);
}

.ts-brand {
  display: flex;
  align-items: center;
  gap: var(--ts-sp-2);
  height: var(--ts-header-height);
  padding: 0 var(--ts-sp-4);
  color: var(--ts-text-inverse);
  white-space: nowrap;
  overflow: hidden;
  flex: 0 0 auto;
  border-bottom: 1px solid var(--ts-nav-divider);
}

.ts-brand__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: var(--ts-r-sm);
  background: var(--ts-accent);
  font-size: var(--ts-fs-caption);
  font-weight: 600;
  flex: 0 0 auto;
}

.ts-brand__text {
  font-size: var(--ts-fs-card);
  font-weight: 600;
  letter-spacing: 0.01em;
}

.ts-aside__scroll {
  flex: 1 1 auto;
  min-height: 0;
  padding: var(--ts-sp-2) 0;
}

/*
 * 侧边栏底部版权：只有管理员在系统参数里填了版权文字才渲染。
 * 深底上用弱化白 + 微字号：它能读清，但不会把视线从菜单上抢走 ——
 * 这是一行声明，不是导航。
 */
.ts-aside__foot {
  flex: 0 0 auto;
  padding: var(--ts-sp-3) var(--ts-sp-4);
  font-size: var(--ts-fs-micro);
  line-height: var(--ts-lh-tight);
  color: var(--ts-nav-text-muted);
  border-top: 1px solid var(--ts-nav-divider);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/*
 * 菜单配色与尺寸统一走 EP 的菜单变量，而不是给 <el-menu> 传
 * background-color/text-color 行内属性 ——
 * 行内属性会被写死成具体色值，换主题时要改模板；变量只改一处。
 *
 * 选中态用**整块强调色底 + 白字**，不加左侧色条：
 * 那条 3px 竖线是最典型的「AI 生成后台」标志之一，且在本项目里
 * 与「卡片左边框」写法重复（impeccable 的侧边条规则也会报）。
 */
.ts-layout__aside :deep(.el-menu) {
  --el-menu-bg-color: transparent;
  --el-menu-text-color: var(--ts-nav-text);
  --el-menu-active-color: var(--ts-text-inverse);
  --el-menu-hover-bg-color: var(--ts-nav-bg-hover);
  --el-menu-item-height: 42px;
  --el-menu-sub-item-height: 38px;
  border-right: none;
}

.ts-layout__aside :deep(.el-menu-item),
.ts-layout__aside :deep(.el-sub-menu__title) {
  margin: 2px var(--ts-sp-2);
  border-radius: var(--ts-r-sm);
  font-size: var(--ts-fs-body);
}

.ts-layout__aside :deep(.el-menu-item.is-active) {
  background: var(--ts-nav-bg-active);
  color: var(--ts-text-inverse);
  font-weight: 500;
}

.ts-layout__aside :deep(.el-menu-item:hover),
.ts-layout__aside :deep(.el-sub-menu__title:hover) {
  background: var(--ts-nav-bg-hover);
  color: var(--ts-text-inverse);
}

/* 折叠态（64px）下菜单项要收成方块，圆角与外边距会把它挤歪 */
.ts-layout__aside.is-collapsed :deep(.el-menu-item),
.ts-layout__aside.is-collapsed :deep(.el-sub-menu__title) {
  margin: 2px 6px;
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
  gap: var(--ts-sp-3);
  height: var(--ts-header-height);
  flex: 0 0 auto;
  padding: 0 var(--ts-sp-5);
  background: var(--ts-surface);
  border-bottom: 1px solid var(--ts-border);
}

/*
 * 面包屑（2026-11）。层级用「分隔符 + 字重」表达，不用颜色堆叠：
 * 非当前项是次要色，当前项是主色 + 500 字重，视觉落点只有一个。
 */
.ts-crumb {
  display: flex;
  align-items: center;
  gap: var(--ts-sp-2);
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
}

.ts-crumb__item {
  font-size: var(--ts-fs-body);
  color: var(--ts-text-hint);
  overflow: hidden;
  text-overflow: ellipsis;
}

.ts-crumb__item.is-current {
  color: var(--ts-text);
  font-weight: 600;
}

.ts-crumb__sep {
  color: var(--ts-border-strong);
  font-size: var(--ts-fs-caption);
}

.ts-layout__spacer {
  flex: 1 1 auto;
}

/*
 * 内容区（2026-11 改造）：只负责滚动与横向居中，**不再叠加内边距**。
 * 改造前 content 有 24px、页面里的 .ts-page 又有 16px，
 * 左右各 40px 的留白把内容挤窄，而卡片宽度仍然七种并存 ——
 * 真正决定留白的应该是页面容器（.ts-page），不是滚动容器。
 */
.ts-layout__content {
  flex: 1 1 auto;
  width: 100%;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  -webkit-overflow-scrolling: touch;
}

.ts-layout__footer {
  flex: 0 0 auto;
  padding: var(--ts-sp-2) var(--ts-sp-4) calc(var(--ts-sp-2) + var(--ts-safe-bottom));
  text-align: center;
  font-size: var(--ts-fs-micro);
  color: var(--ts-text-hint);
  background: var(--ts-surface);
  border-top: 1px solid var(--ts-border);
}

/* 移动端页脚的第二行：版权文字单独占一行，不与系统名称挤在一起 */
.ts-layout__copyright {
  display: block;
  margin-top: 2px;
  font-size: var(--ts-fs-micro);
  color: var(--ts-text-secondary);
}

.ts-icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  padding: 0;
  border: none;
  border-radius: var(--ts-r-sm);
  background: transparent;
  color: var(--ts-text-secondary);
  cursor: pointer;
  transition: background-color var(--ts-dur-fast) var(--ts-ease),
    color var(--ts-dur-fast) var(--ts-ease);
}

.ts-icon-btn:hover {
  background: var(--ts-surface-hover);
  color: var(--ts-text);
}

.ts-user {
  display: inline-flex;
  align-items: center;
  gap: var(--ts-sp-2);
  height: 36px;
  padding: 0 var(--ts-sp-2);
  border-radius: var(--ts-r-sm);
  cursor: pointer;
  color: var(--ts-text);
  outline: none;
  transition: background-color var(--ts-dur-fast) var(--ts-ease);
}

.ts-user:hover {
  background: var(--ts-surface-hover);
}

.ts-user__avatar {
  background: var(--ts-accent);
  font-size: var(--ts-fs-caption);
  font-weight: 500;
}

.ts-user__name {
  max-width: 96px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--ts-fs-body);
}

/* 手机档：面包屑只保留当前项 —— 三层路径在 360px 宽里会挤成省略号，
   而用户此刻最需要的信息是「我在哪一页」，「我从哪个分组进来」可以省。 */
@media (max-width: 767px) {
  .ts-crumb__item:not(.is-current),
  .ts-crumb__sep {
    display: none;
  }

  .ts-layout__header {
    padding: 0 var(--ts-sp-3);
    gap: var(--ts-sp-2);
  }

  .ts-crumb__item {
    font-size: var(--ts-fs-card);
    font-weight: 600;
    color: var(--ts-text);
  }
}
</style>

<style>
/* 抽屉菜单为 teleport 渲染，需使用非 scoped 样式覆盖 */
.ts-drawer .el-drawer__body {
  padding: 0;
  background: var(--ts-nav-bg);
}

/* 抽屉里的菜单同样走统一变量（scoped 样式作用不到 teleport 出去的内容） */
.ts-drawer .el-menu {
  --el-menu-bg-color: transparent;
  --el-menu-text-color: var(--ts-nav-text);
  --el-menu-active-color: var(--ts-text-inverse);
  --el-menu-hover-bg-color: var(--ts-nav-bg-hover);
  --el-menu-item-height: 46px;
  --el-menu-sub-item-height: 42px;
  border-right: none;
  padding: var(--ts-sp-2) 0;
}

.ts-drawer .el-menu-item,
.ts-drawer .el-sub-menu__title {
  margin: 2px var(--ts-sp-2);
  border-radius: var(--ts-r-sm);
}

.ts-drawer .el-menu-item.is-active {
  background: var(--ts-nav-bg-active);
  color: var(--ts-text-inverse);
  font-weight: 500;
}

.ts-drawer .el-menu-item:hover,
.ts-drawer .el-sub-menu__title:hover {
  background: var(--ts-nav-bg-hover);
  color: var(--ts-text-inverse);
}

.ts-drawer .ts-brand {
  color: var(--ts-text-inverse);
  border-bottom: 1px solid var(--ts-nav-divider);
}
</style>
