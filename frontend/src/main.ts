import { createApp } from 'vue'
import { createPinia } from 'pinia'
import {
  ArrowDown,
  Bell,
  Box,
  Calendar,
  DataLine,
  Delete,
  Document,
  Edit,
  Expand,
  Fold,
  HomeFilled,
  Key,
  Lock,
  Notebook,
  Plus,
  Postcard,
  Setting,
  SetUp,
  ShoppingCart,
  Tickets,
  Timer,
  User,
  UserFilled,
  // Search：组织与人员页「部门搜索框」的前缀图标。
  // 模板里写的是 <Search />，图标不在 unplugin-vue-components 的自动导入范围，
  // 不注册就是一个空标签（不报错）。
  Search,
  // FolderOpened：备份记录菜单的图标（P0）。
  // ⚠️ 菜单 icon 是按名称动态渲染的，而 unplugin-vue-components 只管 Element Plus 组件、
  // 不覆盖图标，因此新图标必须在此显式导入并注册进 ICONS，否则菜单渲出一个空标签。
  FolderOpened
} from '@element-plus/icons-vue'
import App from '@/App.vue'
import router from '@/router'
import { setupRouterGuard } from '@/router/guard'
import { useSiteStore } from '@/store/site'
import '@/styles/index.css'

/**
 * 应用入口
 *
 * 说明：Element Plus 组件与样式采用按需引入（unplugin-auto-import / unplugin-vue-components），
 * 无需在此全量注册。
 * 图标需要按名称动态渲染（菜单的 icon 字段），因此按需注册实际用到的一批，
 * 而非全量导入整个图标库，避免打包体积膨胀。
 */
const app = createApp(App)

app.use(createPinia())
app.use(router)

const ICONS = {
  ArrowDown,
  Bell,
  Box,
  // Calendar / Document / Key / Notebook / Postcard / ShoppingCart / Timer：
  // 「申请类型与审批流程」页的图标。申请类型的 icon 字段由管理员在界面上选，
  // 选出来的名字**按名渲染**，因此候选图标必须全部注册 —— 少注册一个，
  // 卡片上的图标就是一个空标签（页面不报错，只是图案不见，最难发现）。
  // Timer / Calendar / Postcard / ShoppingCart / Key 也正好是 5 个预置申请类型正在用的图标。
  Calendar,
  DataLine,
  // Delete / Edit / Plus：组织与人员页的部门树操作按钮。
  // 模板里直接写了 <Edit /> <Plus /> <Delete />，而图标不在
  // unplugin-vue-components 的自动导入范围内（它只处理 Element Plus 组件），
  // 因此必须在此按名注册，否则模板渲染出一个空标签且只在控制台留警告。
  Delete,
  Document,
  Edit,
  Expand,
  Fold,
  HomeFilled,
  Key,
  Lock,
  Notebook,
  Plus,
  Postcard,
  Setting,
  SetUp,
  ShoppingCart,
  Tickets,
  Timer,
  User,
  UserFilled,
  Search,
  FolderOpened
}

for (const [name, component] of Object.entries(ICONS)) {
  app.component(name, component)
}

setupRouterGuard(router)

// 站点品牌（）：登录页标题与浏览器标签页都要用，因此启动即拉一次。
//
// 刻意**不 await**：这是外观信息，等它返回再挂载会让首屏出现可感知的白屏；
// 而 store 内置的兜底值与后端默认值一致，先渲染兜底名再被真实值替换，用户无感。
// 拉取成功后由 store 的 siteName 触发 router/guard 重设一次标签页标题
// （否则自定义名称要等下一次导航才生效）。
const siteStore = useSiteStore()
void siteStore.load()

// 全局兜底：组件内未捕获的错误统一打印，避免白屏且便于定位
app.config.errorHandler = (err, _instance, info) => {
  console.error('[全局异常]', info, err)
}

app.mount('#app')
