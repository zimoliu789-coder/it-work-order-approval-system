<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import { useSiteStore } from '@/store/site'
import systemApi from '@/api/system'
import SiteLogo from '@/components/SiteLogo.vue'
import type {
  ConfigCatalog,
  ConfigCatalogGroup,
  ConfigCatalogItem,
  ConfigCatalogSection,
  ConfigTestMailPayload,
  ConfigTestSmsPayload
} from '@/types/system'

/**
 * 系统参数设置（ ·  · ：卡片式配置页）
 *
 * ## 为什么整页重做
 * 改造前参数页是一张表：左边一列英文 key（`lock_timeout_minutes`…），右边一列输入框。
 * 能读懂它的人只有写代码的人，而需求是「非技术人员也能维护」。
 * 于是这一版把「怎么显示」交给服务端目录（`SystemConfigCatalog`）：
 * 分组、中文标签、控件类型、单位、区间、说明**全部由接口下发**，前端只负责渲染。
 *
 * 前端刻意**不内置**任何默认值、分组顺序或区间常量：
 * 内置一份就会与迁移脚本初值、后端校验区间漂移，而漂移的表现是
 * 「页面提示可以填 100、保存却被拒」这类最难解释的失败。
 *
 * ## 三条交互约定
 * 1. **保存是整体提交**：底部固定栏一次提交所有改动，后端「全成或全败」。
 *    因此「恢复默认」只回填、不提交 —— 否则一次误点就会把一张卡片立刻写进库。
 * 2. **密文项留空 = 不修改**：页面回显的是掩码 `****`，提交掩码时后端按「未改动」跳过。
 *    文案上必须说清楚，否则用户会以为「保存成功但授权码被清空了」。
 * 3. **有校验错误就不让保存**：把错误标在字段下方并禁用保存按钮，
 *    比「提交后被后端整批拒绝」更好定位 —— 后者报的是「参数 X 非法」，用户得自己在几十项里找。
 * 4. **灰掉的项不进本次保存**（ · ）：通道开关关闭时，
 *    该分区的参数在界面上是灰的，因此它们也不参与校验、改动计数与提交 ——
 *    否则会出现「界面说不可改、请求里却带着新值」的言行不一，
 *    以及「草稿里的非法值把整次保存卡死、而输入框点都点不进去」这种死结。
 *    判据与服务端一致：开关自身永远可写（否则关掉后就再也打不开）。
 *
 * ## 权限
 * 查看 `config:view`（admin 也有）；修改 `config:manage`（仅 super_admin）。
 * 另有「仅内置超管可改」的项（站点品牌 / 验证渠道开关 / 各类凭据），
 * 由服务端在目录里下发 `editable=false` + `adminOnly=true`，前端只负责置灰。
 */
const userStore = useUserStore()
const siteStore = useSiteStore()

/** 修改权限：没有它整页只读（查看权限另算，登录页已由路由守卫控制） */
const canManage = computed(() => userStore.hasPerm('config:manage'))

/** 密文项未修改时页面回显的掩码，与服务端 `SecretCipher.MASK` 同字面量 */
const SECRET_MASK = '****'

/** 需要额外做邮箱格式校验的键 */
const SMTP_USERNAME_KEY = 'smtp_username'

/** 短信通道的参数键（发送测试短信时作为覆盖项带上；与后端 SmsSettings 逐字对应） */
const SMS_PROVIDER_KEY = 'sms_provider'
const SMS_ACCESS_KEY_ID_KEY = 'sms_access_key_id'
const SMS_ACCESS_KEY_SECRET_KEY = 'sms_access_key_secret'
const SMS_SIGN_NAME_KEY = 'sms_sign_name'
const SMS_TEMPLATE_CODE_KEY = 'sms_template_code'

/** 用于「邮箱验证与手机验证都关掉」这条依赖警告 */
const KEY_SMS_VERIFY = 'sms_verify_enabled'
const KEY_EMAIL_VERIFY = 'email_verify_enabled'

/** 站点图标（走独立上传接口，不进草稿） */
const KEY_SITE_LOGO = 'system.site-logo'
const KEY_SITE_NAME = 'system.site-name'
const KEY_COPYRIGHT = 'system.copyright'

/**
 * 站点品牌三键（名称 / 图标 / 版权）。
 *
 * <p>保存后要统一重拉一次 siteStore：否则「改完版权，侧边栏底部与登录页仍是旧的」——
 * 而这两处正是版权文字<b>唯一</b>的可见位置，用户只会判定成「没生效」。
 * 原条件只判 `system.site-name`，是/E 留下的口子：图标与版权走的是同一条表单写入路径，
 * 却没有同样的刷新待遇。
 */
const BRANDING_KEYS: readonly string[] = [KEY_SITE_NAME, KEY_SITE_LOGO, KEY_COPYRIGHT]

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

/** 手机号格式：与后端 AccountFormats.isPhone 同口径，也与绑定页 / 员工管理页一致 */
const PHONE_RE = /^1[3-9]\d{9}$/

const loading = ref(false)
const saving = ref(false)
const catalog = ref<ConfigCatalog | null>(null)

/** 编辑草稿：只有被改动过的项才在这里（键 → 新值） */
const draft = reactive<Record<string, string>>({})

/** 搜索关键字：实时过滤参数项与分组 */
const search = ref('')

/** 左侧目录当前高亮的分组 */
const activeGroup = ref('')

/** 高级参数这类默认折叠的分组：记录展开状态 */
const expanded = reactive<Record<string, boolean>>({})

/** 保存结果反馈：idle 未保存 / success 成功 / error 失败 */
const saveState = ref<'idle' | 'success' | 'error'>('idle')
const savedCount = ref(0)
const saveError = ref('')

/** 卡片 DOM 引用：用于左侧目录点击滚动与当前位置高亮 */
const cardRefs = new Map<string, HTMLElement>()

function setCardRef(code: string, el: unknown): void {
  if (el instanceof HTMLElement) {
    cardRefs.set(code, el)
  } else {
    cardRefs.delete(code)
  }
}

// ------------------------------------------------------------------
// 取值与变更
// ------------------------------------------------------------------

/**
 * 按配置键取项。
 *
 * 目录由服务端下发，理论上不会缺项；这里返回一个空壳而不是 `undefined`，
 * 是为了让模板与校验函数不必到处判空 —— 一个缺失的项最多显示成空白，不会让整页渲染失败。
 */
function findItem(key: string): ConfigCatalogItem {
  for (const group of catalog.value?.groups ?? []) {
    for (const item of group.items) {
      if (item.key === key) {
        return item
      }
    }
  }
  return {
    key,
    label: key,
    type: 'TEXT',
    unit: '',
    value: '',
    defaultValue: '',
    description: '',
    editable: false,
    adminOnly: false,
    secret: false,
    required: false,
    widget: null,
    layout: 'MEDIUM',
    options: [],
    min: null,
    max: null,
    maxLength: null
  }
}

/** 当前生效值：草稿优先，否则用服务端下发的当前值 */
function valueOf(item: ConfigCatalogItem): string {
  const edited = draft[item.key]
  return edited === undefined ? item.value : edited
}

/** 该项是否被改动过 */
function isChanged(item: ConfigCatalogItem): boolean {
  const edited = draft[item.key]
  return edited !== undefined && edited !== item.value
}

/**
 * 写入草稿。
 *
 * 改回原值时把草稿删掉，避免「值没变却出现在提交里」——
 * 那会让底部的「已改动 N 项」虚高，用户以为改了很多其实什么都没改。
 */
function onEdit(item: ConfigCatalogItem, next: string): void {
  if (next === item.value) {
    delete draft[item.key]
  } else {
    draft[item.key] = next
  }
  saveState.value = 'idle'
  saveError.value = ''
}

/**
 * 密文框获得焦点时清掉掩码。
 *
 * 不清掉的话，用户必须先把 `****` 手工删干净才能输入新授权码 ——
 * 而 `****` 看起来像个占位提示，很多人会以为直接输入就会替换。
 */
function onSecretFocus(item: ConfigCatalogItem): void {
  if (item.secret && draft[item.key] === undefined && valueOf(item) === SECRET_MASK) {
    draft[item.key] = ''
  }
}

/** 密文框失焦且仍为空 → 还原成掩码显示（空 = 不修改，不该显示成「改动过」） */
function onSecretBlur(item: ConfigCatalogItem): void {
  if (item.secret && draft[item.key] === '') {
    delete draft[item.key]
  }
}

/** 特殊控件（logo 上传）不走草稿流程：它由独立接口即时持久化 */
function isSpecialWidget(item: ConfigCatalogItem): boolean {
  return item.widget === 'logo'
}

// ------------------------------------------------------------------
// 通道开关联动（ / ）
// ------------------------------------------------------------------

/**
 * 本分区是否因通道开关关闭而整体锁住。
 *
 * <p>取的是**草稿优先**的值（`valueOf` 已处理），因此把开关拨到「停用」的瞬间，
 * 下面的参数立刻变灰，不需要先保存 —— 否则用户会以为「关了但没生效」。
 *
 * <p>直接用 `dependsOnKey` 取值而不必先反查「这个键在哪个分区」：开关一定与它控制的参数
 * 在同一个分区里（由后端 `SystemConfigCatalogTest#controlledSections` 钉死）。
 */
function sectionLocked(section: ConfigCatalogSection): boolean {
  if (!section.dependsOnKey) {
    return false
  }
  return valueOf(findItem(section.dependsOnKey)) !== '1'
}

/**
 * 单项是否可编辑。
 *
 * <p>**开关自身不受自己控制**：否则关掉之后再无入口把它打开，参数页会把自己锁死。
 * 这条与服务端 `SystemConfigCatalog.Section#controlledItems()` 是同一份判据的两侧实现，
 * 改一边必须改另一边。
 */
function isEditableItem(item: ConfigCatalogItem, section: ConfigCatalogSection): boolean {
  if (!item.editable) {
    return false
  }
  if (section.dependsOnKey && item.key === section.dependsOnKey) {
    return true
  }
  return !sectionLocked(section)
}

/**
 * 该项是否参与「校验 / 改动计数 / 提交 / 恢复默认」。
 *
 * <p>与「能不能改」是两件事，但故意共用同一份联动判据：通道关闭时它的参数在界面上是灰的，
 * 若仍然提交，就会出现两个后果 ——
 * ①「界面说不可改、请求里却带着新值」的言行不一；
 * ② 草稿里残留的非法值把整次保存卡死，而用户面对一个改不动的输入框无从下手。
 * 因此规则统一为：**灰掉的项不进本次保存**（草稿本身保留，把开关拨回来就能继续编辑）。
 */
function isParticipating(item: ConfigCatalogItem, section: ConfigCatalogSection): boolean {
  return !isSpecialWidget(item) && isEditableItem(item, section)
}

/** 把某张卡片的所有项回填成默认值（**不提交**，仍需点底部保存） */
function resetGroup(group: ConfigCatalogGroup): void {
  let touched = 0
  for (const section of group.sections) {
    for (const item of section.items) {
      if (!isParticipating(item, section)) {
        continue
      }
      if (item.secret && !item.defaultValue) {
        // 密文项的默认值是空串，而空串的语义是「不修改」——
        // 写进草稿再提交会被后端跳过，但界面上会出现一个「已改动」的假信号
        delete draft[item.key]
        continue
      }
      if (item.defaultValue === item.value) {
        delete draft[item.key]
        continue
      }
      draft[item.key] = item.defaultValue
      touched += 1
    }
  }
  saveState.value = 'idle'
  saveError.value = ''
  ElMessage.info(
    touched > 0
      ? `「${group.label}」已回填默认值，请点底部「保存所有修改」生效`
      : `「${group.label}」无需恢复默认`
  )
}

/** 放弃全部改动 */
function discard(): void {
  Object.keys(draft).forEach((key) => delete draft[key])
  saveState.value = 'idle'
  saveError.value = ''
  ElMessage.info('已放弃未保存的改动')
}

// ------------------------------------------------------------------
// 校验
// ------------------------------------------------------------------

/**
 * 单项校验：返回错误文案，合法返回空串。
 *
 * 规则全部来自服务端下发的 `min / max / maxLength / required`，
 * 前端不写死任何数值 —— 见文件头注释「前端刻意不内置任何区间常量」。
 */
function validate(item: ConfigCatalogItem): string {
  if (isSpecialWidget(item)) {
    return ''
  }
  const raw = valueOf(item).trim()

  if (!raw) {
    if (item.type === 'PASSWORD' || item.secret) {
      // 密文项留空 = 保持原值，不是错误
      return ''
    }
    return item.required ? '不能为空' : ''
  }
  if (item.type === 'NUMBER') {
    if (!/^\d+$/.test(raw)) {
      return '请输入整数'
    }
    const num = Number(raw)
    if (item.min !== null && num < item.min) {
      return `不能小于 ${item.min}`
    }
    if (item.max !== null && num > item.max) {
      return `不能大于 ${item.max}`
    }
  }
  if (item.maxLength !== null && raw.length > item.maxLength) {
    return `不能超过 ${item.maxLength} 个字符`
  }
  if (item.key === SMTP_USERNAME_KEY && raw !== SECRET_MASK && !EMAIL_RE.test(raw)) {
    return '请填写完整的邮箱地址，例如 noreply@example.com'
  }
  return ''
}

/**
 * 全部错误（键 → 文案）：只保留有错的项。
 *
 * <p>跳过「不参与本次保存的项」（灰掉的通道参数、logo 特殊控件）——
 * 否则一个已经灰掉、用户根本改不动的输入框会把保存按钮一直禁着，
 * 而报错提示指向的位置连点都点不进去。
 */
const errors = computed<Record<string, string>>(() => {
  const out: Record<string, string> = {}
  for (const group of catalog.value?.groups ?? []) {
    for (const section of group.sections) {
      for (const item of section.items) {
        if (!isParticipating(item, section)) {
          continue
        }
        const message = validate(item)
        if (message) {
          out[item.key] = message
        }
      }
    }
  }
  return out
})

/** 已改动的项（灰掉的项不计入 —— 见 isParticipating 的注释） */
const changedItems = computed<ConfigCatalogItem[]>(() => {
  const list: ConfigCatalogItem[] = []
  for (const group of catalog.value?.groups ?? []) {
    for (const section of group.sections) {
      for (const item of section.items) {
        if (isParticipating(item, section) && isChanged(item)) {
          list.push(item)
        }
      }
    }
  }
  return list
})

const changedCount = computed(() => changedItems.value.length)

const errorCount = computed(() => Object.keys(errors.value).length)

const canSave = computed(() => canManage.value && changedCount.value > 0 && errorCount.value === 0)

// ------------------------------------------------------------------
// 依赖警告（：配置依赖警告）
// ------------------------------------------------------------------

/** 手机验证与邮箱验证是否都已关闭 —— 此时全站无法自助找回密码 */
const allChannelsOff = computed(
  () => valueOf(findItem(KEY_SMS_VERIFY)) === '0' && valueOf(findItem(KEY_EMAIL_VERIFY)) === '0'
)

/**
 * SMTP 是否尚未配置完整。
 *
 * 与后端 `MailSettings.complete()` 同口径（host / port / username / password 四项），
 * 且**按草稿值**判定 —— 用户边填边能看到警告消失，而不是保存后才发现。
 */
const smtpIncomplete = computed(() => {
  const host = valueOf(findItem('smtp_host')).trim()
  const port = Number(valueOf(findItem('smtp_port')).trim())
  const username = valueOf(findItem('smtp_username')).trim()
  const password = valueOf(findItem('smtp_password')).trim()
  return !host || !username || !password || !(port > 0 && port <= 65535)
})

// ------------------------------------------------------------------
// 搜索过滤
// ------------------------------------------------------------------

function matches(item: ConfigCatalogItem, keyword: string): boolean {
  return (
    item.label.toLowerCase().includes(keyword) ||
    item.description.toLowerCase().includes(keyword) ||
    item.key.toLowerCase().includes(keyword)
  )
}

/**
 * 搜索命中的分组（分组标题/说明命中则整组显示，否则按项过滤）。
 *
 * <p>过滤必须**在分区层面**完成并同步重算扁平的 {@code items}：
 * 两者是同一份数据的两种视图（见类型定义），只过滤其中一份会让
 * 「渲染用的分区」与「逻辑用的扁平项」互相矛盾 ——
 * 表现为「搜索后看不见某一项，改动计数里却仍然有它」。
 */
const visibleGroups = computed<ConfigCatalogGroup[]>(() => {
  const groups = catalog.value?.groups ?? []
  const keyword = search.value.trim().toLowerCase()
  if (!keyword) {
    return groups
  }
  const result: ConfigCatalogGroup[] = []
  for (const group of groups) {
    if (
      group.label.toLowerCase().includes(keyword) ||
      group.description.toLowerCase().includes(keyword)
    ) {
      result.push(group)
      continue
    }
    const sections = group.sections
      .map((section) => ({
        ...section,
        items: section.items.filter((item) => matches(item, keyword))
      }))
      .filter((section) => section.items.length > 0)
    if (sections.length > 0) {
      result.push({ ...group, sections, items: sections.flatMap((section) => section.items) })
    }
  }
  return result
})

/** 折叠状态：有搜索关键字时强制展开，否则用户看不到命中的内容 */
function isExpanded(group: ConfigCatalogGroup): boolean {
  if (search.value.trim()) {
    return true
  }
  return expanded[group.code] ?? !group.collapsed
}

function toggleGroup(group: ConfigCatalogGroup): void {
  expanded[group.code] = !isExpanded(group)
}

// ------------------------------------------------------------------
// 左侧目录：点击滚动 + 当前位置高亮
// ------------------------------------------------------------------

function scrollToGroup(code: string): void {
  const el = cardRefs.get(code)
  if (!el) {
    return
  }
  activeGroup.value = code
  // 用 scrollIntoView 而不是算 scrollTop：滚动容器可能是 window，也可能是布局里的
  // 内层元素（不同布局下不同），算绝对位移会在这两种情形里各错一次。
  el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

/**
 * 滚动时高亮当前卡片。
 *
 * 判定用 `getBoundingClientRect().top` 与一个固定阈值比较，
 * 而不是 IntersectionObserver：后者在「卡片高度小于视口」时会出现
 * 多张卡片同时进入视野、回调顺序不稳定的问题，高亮会来回跳。
 *
 * 监听时带 `capture: true`：滚动事件不冒泡，但会在捕获阶段经过 window，
 * 因此无论实际滚动的是 window 还是内层容器，这里都能收到。
 */
function onScroll(): void {
  const offset = 150
  let current = visibleGroups.value[0]?.code ?? ''
  for (const group of visibleGroups.value) {
    const el = cardRefs.get(group.code)
    if (el && el.getBoundingClientRect().top <= offset) {
      current = group.code
    }
  }
  if (current && current !== activeGroup.value) {
    activeGroup.value = current
  }
}

// ------------------------------------------------------------------
// 加载与保存
// ------------------------------------------------------------------

async function load(): Promise<void> {
  loading.value = true
  try {
    catalog.value = await systemApi.getConfigCatalog()
    Object.keys(draft).forEach((key) => delete draft[key])
    if (!activeGroup.value) {
      activeGroup.value = catalog.value.groups[0]?.code ?? ''
    }
  } catch {
    catalog.value = null
  } finally {
    loading.value = false
  }
}

async function save(): Promise<void> {
  if (!canManage.value) {
    ElMessage.warning('没有修改系统参数的权限')
    return
  }
  if (errorCount.value > 0) {
    ElMessage.warning(`有 ${errorCount.value} 项填写不正确，请先修正（已标红）`)
    return
  }
  const values: Record<string, string> = {}
  for (const item of changedItems.value) {
    values[item.key] = valueOf(item)
  }
  if (Object.keys(values).length === 0) {
    ElMessage.info('没有需要保存的改动')
    return
  }

  saving.value = true
  saveError.value = ''
  try {
    const result = await systemApi.updateSystemConfigs(values)
    savedCount.value = result.affected
    saveState.value = 'success'
    // 站点名称 / 图标 / 版权任一被改：重拉站点品牌，让侧边栏、标签页与登录页同时刷新
    if (BRANDING_KEYS.some((key) => key in values)) {
      await siteStore.load()
    }
    await load()
    ElMessage.success(
      result.affected > 0
        ? `已保存 ${result.affected} 项参数（已生效）`
        : '提交成功（与现值一致，未写入）'
    )
  } catch (e) {
    saveState.value = 'error'
    saveError.value = (e as { message?: string }).message ?? '保存失败'
  } finally {
    saving.value = false
  }
}

// ------------------------------------------------------------------
// 站点图标（独立接口即时持久化，不进草稿）
// ------------------------------------------------------------------

const logoInputRef = ref<HTMLInputElement>()
const logoBusy = ref(false)
const canEditLogo = computed(() => canManage.value && findItem(KEY_SITE_LOGO).editable)

/**
 * 上传 logo：前端这层校验与服务端同口径（png/jpg、10MB），
 * 目的是**在传输前**拦掉明显不合格的文件，否则用户要等一张 20MB 的图传完才被拒。
 * 服务端仍独立校验 —— 前端校验只是体验优化，不是安全边界。
 */
async function onLogoSelected(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  // 先清空 input 的值：否则连续两次选同一个文件不会触发 change，表现为「第二次上传没反应」
  input.value = ''
  if (!file || !canEditLogo.value) {
    return
  }
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.warning('logo 图片不能超过 10MB，请压缩后重试')
    return
  }
  if (!['image/png', 'image/jpeg'].includes(file.type)) {
    ElMessage.warning('logo 仅支持 png / jpg 格式的图片')
    return
  }
  logoBusy.value = true
  try {
    siteStore.setFromApi(await systemApi.uploadSiteLogo(file))
    await load()
    ElMessage.success('logo 已更新，全站立即生效')
  } catch {
    // 错误由请求层统一提示
  } finally {
    logoBusy.value = false
  }
}

async function resetLogo(): Promise<void> {
  if (!canEditLogo.value) {
    ElMessage.warning('仅内置超级管理员可修改 logo')
    return
  }
  try {
    await ElMessageBox.confirm('确认恢复默认的「IT」文字图标？当前上传的图片将被删除。', '恢复默认 logo', {
      type: 'warning',
      confirmButtonText: '确认恢复',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  logoBusy.value = true
  try {
    siteStore.setFromApi(await systemApi.resetSiteLogo())
    await load()
    ElMessage.success('已恢复默认 logo')
  } catch {
    // 错误由请求层统一提示
  } finally {
    logoBusy.value = false
  }
}

// ------------------------------------------------------------------
// 发送测试邮件（）
// ------------------------------------------------------------------

/**
 * 用**当前表单值**（含未保存的改动）去连一次 SMTP。
 *
 * 必须带草稿值：管理员的真实动作是「填完 → 先试一下 → 成了再保存」。
 * 若只能用已保存的配置，他就被迫「先存错的、再测、再改回来」，
 * 而保存完成后系统会立刻按这份配置发验证码 —— 等于拿真实用户做试验。
 */
async function sendTestMail(): Promise<void> {
  if (!canManage.value) {
    ElMessage.warning('没有发送测试邮件的权限')
    return
  }

  let target = valueOf(findItem(SMTP_USERNAME_KEY)).trim()
  try {
    const input = await ElMessageBox.prompt('测试邮件将发往哪个邮箱？', '发送测试邮件', {
      confirmButtonText: '发送',
      cancelButtonText: '取消',
      inputValue: target,
      inputPlaceholder: '例如 admin@example.com',
      inputValidator: (value: string) =>
        EMAIL_RE.test((value ?? '').trim()) ? true : '请输入正确的邮箱地址'
    })
    target = input.value.trim()
  } catch {
    // 用户取消：不是错误，不提示
    return
  }

  // 覆盖项只在用户真的填了的时候带上：空字段 / 掩码 = 沿用已保存配置
  const payload: ConfigTestMailPayload = { to: target }
  const host = valueOf(findItem('smtp_host')).trim()
  if (host) {
    payload.host = host
  }
  const port = Number(valueOf(findItem('smtp_port')).trim())
  if (port > 0) {
    payload.port = port
  }
  const username = valueOf(findItem(SMTP_USERNAME_KEY)).trim()
  if (username) {
    payload.username = username
  }
  const password = valueOf(findItem('smtp_password')).trim()
  if (password && password !== SECRET_MASK) {
    payload.password = password
  }
  const fromName = valueOf(findItem('smtp_from_name')).trim()
  if (fromName) {
    payload.fromName = fromName
  }
  payload.ssl = valueOf(findItem('smtp_ssl')) === '1'

  saving.value = true
  try {
    const result = await systemApi.testMail(payload)
    ElMessage.success(
      `测试邮件已发送至 ${target}（SMTP ${result.host}:${result.port}，SSL ${result.ssl ? '开' : '关'}）`
    )
  } catch (e) {
    // 后端已把原因归类为可读文案（认证失败 / 连不上 / SSL 不匹配）。
    // 用弹窗而不是一闪而过的消息：长文案需要用户看清并据此改配置。
    ElMessageBox.alert((e as { message?: string }).message ?? '测试邮件发送失败', '发送失败', {
      type: 'error',
      confirmButtonText: '知道了'
    }).catch(() => undefined)
  } finally {
    saving.value = false
  }
}

// ------------------------------------------------------------------
// 发送测试短信（）
// ------------------------------------------------------------------

/**
 * 用**当前表单值**（含未保存的改动）发一条测试短信。
 *
 * <p>与 {@link sendTestMail} 的关键差别是<b>不能承诺「已发送」</b>：
 * 短信网关本期尚未接入（为预留），后端返回的 {@code delivered} 恒为 {@code false}。
 * 因此这里按 `delivered` 分档展示 —— 参数校验通过但没真发出去时，
 * 用「参数校验通过（未真实发送）」+ 说明，而不是一个会让人误判配置已可用的绿色成功框。
 */
async function sendTestSms(): Promise<void> {
  if (!canManage.value) {
    ElMessage.warning('没有发送测试短信的权限')
    return
  }

  let target = ''
  try {
    const input = await ElMessageBox.prompt('测试短信将发往哪个手机号？', '发送测试短信', {
      confirmButtonText: '发送',
      cancelButtonText: '取消',
      inputPlaceholder: '例如 13800000000',
      inputValidator: (value: string) =>
        PHONE_RE.test((value ?? '').trim()) ? true : '请输入 11 位手机号'
    })
    target = input.value.trim()
  } catch {
    // 用户取消：不是错误，不提示
    return
  }

  // 覆盖项只在用户真的填了的时候带上：空字段 / 掩码 = 沿用已保存配置
  const payload: ConfigTestSmsPayload = { to: target }
  const provider = valueOf(findItem(SMS_PROVIDER_KEY)).trim()
  if (provider) {
    payload.provider = provider
  }
  const accessKeyId = valueOf(findItem(SMS_ACCESS_KEY_ID_KEY)).trim()
  if (accessKeyId) {
    payload.accessKeyId = accessKeyId
  }
  const secret = valueOf(findItem(SMS_ACCESS_KEY_SECRET_KEY)).trim()
  if (secret && secret !== SECRET_MASK) {
    payload.accessKeySecret = secret
  }
  const signName = valueOf(findItem(SMS_SIGN_NAME_KEY)).trim()
  if (signName) {
    payload.signName = signName
  }
  const templateCode = valueOf(findItem(SMS_TEMPLATE_CODE_KEY)).trim()
  if (templateCode) {
    payload.templateCode = templateCode
  }

  saving.value = true
  try {
    const result = await systemApi.testSms(payload)
    // 如实展示：delivered=false 时**不能**说「已发送」
    ElMessageBox.alert(
      result.message,
      result.delivered ? `测试短信已发送至 ${target}` : '参数校验通过（短信未真实发出）',
      {
        type: result.delivered ? 'success' : 'warning',
        confirmButtonText: '知道了'
      }
    ).catch(() => undefined)
  } catch (e) {
    // 后端已把原因归类为可读文案（缺哪几项 / 号码格式 / 通道未启用）。
    // 用弹窗而不是一闪而过的消息：长文案需要用户看清并据此改配置。
    ElMessageBox.alert((e as { message?: string }).message ?? '测试短信发送失败', '发送失败', {
      type: 'error',
      confirmButtonText: '知道了'
    }).catch(() => undefined)
  } finally {
    saving.value = false
  }
}

// ------------------------------------------------------------------
// 离开页面拦截（：离开页面弹确认）
// ------------------------------------------------------------------

onBeforeRouteLeave(async () => {
  if (changedCount.value === 0) {
    return true
  }
  try {
    await ElMessageBox.confirm(
      `有 ${changedCount.value} 项参数改动尚未保存，离开将丢失这些改动。`,
      '尚未保存',
      { type: 'warning', confirmButtonText: '放弃并离开', cancelButtonText: '继续编辑' }
    )
    return true
  } catch {
    return false
  }
})

onMounted(async () => {
  await load()
  window.addEventListener('scroll', onScroll, true)
  onScroll()
})

onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll, true)
})
</script>

<template>
  <div class="ts-page cfg">
    <!-- 头部：标题 + 搜索 -->
    <section class="ts-card cfg__head">
      <div class="cfg__head-main">
        <h3 class="cfg__title">系统参数设置</h3>
        <p class="ts-text-secondary cfg__subtitle">
          按功能分成若干张卡片，改完点页面底部「保存所有修改」一次提交（后端全成或全败，保存后立即生效）。
        </p>
      </div>
      <div class="cfg__search">
        <el-input v-model="search" clearable placeholder="搜索参数，例如「端口」「验证码」" />
      </div>
    </section>

    <!-- 全站级依赖警告 -->
    <el-alert
      v-if="catalog && allChannelsOff"
      class="cfg__alert"
      type="warning"
      show-icon
      :closable="false"
      title="短信通知与邮件通知都已关闭"
      description="此时全站所有人都无法自助找回密码，只能由管理员重置；首次登录也不会再弹出绑定引导，审批通知只走站内消息中心。若非有意为之，请至少开启一种验证方式。"
    />

    <div v-loading="loading" class="cfg__body">
      <!-- 左侧目录导航 -->
      <aside class="ts-card cfg__nav">
        <p class="cfg__nav-title">参数目录</p>
        <ul class="cfg__nav-list">
          <li
            v-for="group in visibleGroups"
            :key="group.code"
            class="cfg__nav-item"
            :class="{ 'is-active': activeGroup === group.code }"
            @click="scrollToGroup(group.code)"
          >
            <span>{{ group.label }}</span>
            <el-tag v-if="group.badge" size="small" type="info" effect="plain">{{ group.badge }}</el-tag>
          </li>
        </ul>
        <p v-if="visibleGroups.length === 0" class="ts-text-secondary cfg__nav-empty">没有匹配的参数</p>
      </aside>

      <!-- 右侧卡片 -->
      <div class="cfg__cards">
        <section
          v-for="group in visibleGroups"
          :key="group.code"
          :ref="(el) => setCardRef(group.code, el)"
          class="ts-card cfg__card"
        >
          <header class="cfg__card-head">
            <div>
              <h4 class="cfg__card-title">
                {{ group.label }}
                <el-tag v-if="group.badge" size="small" type="info" effect="plain">{{ group.badge }}</el-tag>
              </h4>
              <p class="ts-text-secondary cfg__card-desc">{{ group.description }}</p>
            </div>
            <div class="cfg__card-actions">
              <el-button link type="primary" @click="toggleGroup(group)">
                {{ isExpanded(group) ? '收起' : '展开' }}
              </el-button>
              <el-button link type="primary" :disabled="!canManage" @click="resetGroup(group)">
                恢复默认
              </el-button>
            </div>
          </header>

          <div v-if="isExpanded(group)" class="cfg__fields">
            <div
              v-for="section in group.sections"
              :key="section.code"
              class="cfg__section"
              :class="{ 'is-locked': sectionLocked(section) }"
            >
              <!--
                分区小标题。单分区分组（登录与安全 / 借用与审批 / 高级参数）用「匿名分区」
                （label=null）—— 硬加一个几乎同名的小标题只会让卡片显得啰嗦。
              -->
              <div v-if="section.label" class="cfg__section-head">
                <h5 class="cfg__section-title">
                  {{ section.label }}
                  <el-tag v-if="section.badge" size="small" type="info" effect="plain">
                    {{ section.badge }}
                  </el-tag>
                </h5>
                <p v-if="section.description" class="ts-text-secondary cfg__section-desc">
                  {{ section.description }}
                </p>
              </div>

              <!--
                通道关闭提示（）：本分区除开关自身外的参数已整体置灰。
                文案与后端 CONFIG_CHANNEL_DISABLED 的口径一致 —— 都要求「先打开开关」。
              -->
              <el-alert
                v-if="sectionLocked(section)"
                class="cfg__section-alert"
                type="info"
                show-icon
                :closable="false"
                :title="`已关闭${section.label}，如需配置请先开启`"
                description="上方的启用开关处于停用状态，本通道的参数暂不可修改；重新开启后即可继续编辑（未保存的改动会保留）。"
              />

              <!--
                邮件分区：SMTP 不完整提示。
                 重组后这张卡同时装着短信、邮箱、验证码，卡片级提示会误伤另外两节，
                因此随分区下沉到 mail 一节。
              -->
              <el-alert
                v-if="section.code === 'mail' && smtpIncomplete && !sectionLocked(section)"
                class="cfg__section-alert"
                type="warning"
                show-icon
                :closable="false"
                title="SMTP 尚未配置完整"
                description="在填齐「服务器地址 / 端口 / 发件邮箱账号 / 授权码」之前，邮箱验证码只会写入系统日志，不会真的发出邮件。"
              />

              <div
                v-for="item in section.items"
                :key="item.key"
                class="cfg__field"
                :class="[
                  `is-span-${(item.layout ?? 'MEDIUM').toLowerCase()}`,
                  {
                    'is-disabled': !isEditableItem(item, section),
                    'is-error': !!errors[item.key]
                  }
                ]"
              >
                <div class="cfg__field-label">
                  <span class="cfg__field-name">
                    {{ item.label }}
                    <el-tag v-if="item.adminOnly" size="small" type="warning" effect="plain">
                      仅内置超管
                    </el-tag>
                  </span>
                  <p class="cfg__field-desc">{{ item.description }}</p>
                </div>

                <div class="cfg__field-control">
                  <!-- 站点图标：走独立上传接口，即时生效，不进草稿 -->
                  <template v-if="item.widget === 'logo'">
                    <span class="cfg__logo-mark"><SiteLogo /></span>
                    <el-button :disabled="!canEditLogo" :loading="logoBusy" @click="logoInputRef?.click()">
                      上传图片
                    </el-button>
                    <el-button
                      :disabled="!canEditLogo || siteStore.logoType !== 'IMAGE'"
                      :loading="logoBusy"
                      @click="resetLogo"
                    >
                      恢复默认
                    </el-button>
                  </template>

                  <!-- 开关 -->
                  <template v-else-if="item.type === 'TOGGLE'">
                    <el-switch
                      :model-value="valueOf(item)"
                      active-value="1"
                      inactive-value="0"
                      :disabled="!isEditableItem(item, section)"
                      active-text="启用"
                      inactive-text="停用"
                      @update:model-value="(v: string | number | boolean) => onEdit(item, String(v))"
                    />
                  </template>

                  <!-- 下拉 -->
                  <template v-else-if="item.type === 'SELECT'">
                    <el-select
                      :model-value="valueOf(item)"
                      class="cfg__control"
                      :disabled="!isEditableItem(item, section)"
                      @update:model-value="(v: string) => onEdit(item, v)"
                    >
                      <el-option
                        v-for="option in item.options"
                        :key="option.value"
                        :label="option.label"
                        :value="option.value"
                      />
                    </el-select>
                  </template>

                  <!-- 密码（带显隐） -->
                  <template v-else-if="item.type === 'PASSWORD'">
                    <el-input
                      :model-value="valueOf(item)"
                      class="cfg__control"
                      type="password"
                      show-password
                      :disabled="!isEditableItem(item, section)"
                      :placeholder="item.value === SECRET_MASK ? '已配置，留空表示不修改' : '未配置'"
                      @focus="onSecretFocus(item)"
                      @blur="onSecretBlur(item)"
                      @update:model-value="(v: string) => onEdit(item, v)"
                    />
                  </template>

                  <!-- 数字 / 文本 -->
                  <template v-else>
                    <el-input
                      :model-value="valueOf(item)"
                      class="cfg__control"
                      :disabled="!isEditableItem(item, section)"
                      @update:model-value="(v: string) => onEdit(item, v)"
                    />
                  </template>

                  <span v-if="item.unit && item.type === 'NUMBER'" class="cfg__field-unit">{{ item.unit }}</span>
                  <span v-else-if="item.type === 'PASSWORD'" class="ts-text-secondary cfg__field-tip">
                    加密存储，页面只显示掩码
                  </span>
                </div>

                <p v-if="errors[item.key]" class="cfg__field-error">{{ errors[item.key] }}</p>
              </div>

              <footer v-if="section.notes.length > 0" class="cfg__section-notes">
                <p v-for="(note, index) in section.notes" :key="index">{{ note }}</p>
              </footer>

              <!-- 通道测试按钮：随所属分区（短信用 test-sms、邮件用 test-mail） -->
              <div
                v-if="section.code === 'sms' || section.code === 'mail'"
                class="cfg__card-footer"
              >
                <el-button
                  v-if="section.code === 'sms'"
                  :disabled="!canManage || sectionLocked(section)"
                  :loading="saving"
                  @click="sendTestSms"
                >
                  发送测试短信
                </el-button>
                <el-button
                  v-else
                  :disabled="!canManage || sectionLocked(section)"
                  :loading="saving"
                  @click="sendTestMail"
                >
                  发送测试邮件
                </el-button>
                <span class="ts-text-secondary cfg__footer-tip">
                  {{
                    section.code === 'sms'
                      ? '用当前填写的参数试一次（未保存也可以测）。短信网关尚未接入，测试只会校验参数并把验证码写进日志。'
                      : '用当前填写的参数试发一封（未保存也可以测），失败会给出具体原因。'
                  }}
                </span>
              </div>
            </div>
          </div>

          <footer v-if="group.notes.length > 0 && isExpanded(group)" class="cfg__card-notes">
            <p v-for="(note, index) in group.notes" :key="index">{{ note }}</p>
          </footer>
        </section>

        <el-empty v-if="!loading && visibleGroups.length === 0" description="没有匹配的系统参数" />
      </div>
    </div>

    <!-- 底部固定保存栏 -->
    <div class="cfg__bar">
      <div class="cfg__bar-status">
        <template v-if="saveState === 'success'">
          <span class="cfg__bar-ok">已保存 {{ savedCount }} 项参数（已生效）</span>
        </template>
        <template v-else-if="saveState === 'error'">
          <span class="cfg__bar-error">保存失败：{{ saveError }}</span>
        </template>
        <template v-else-if="errorCount > 0">
          <span class="cfg__bar-error">{{ errorCount }} 项填写不正确，请修正后再保存</span>
        </template>
        <template v-else-if="changedCount > 0">
          <span>有 {{ changedCount }} 项改动尚未保存</span>
        </template>
        <template v-else>
          <span class="ts-text-secondary">暂无改动</span>
        </template>
      </div>
      <div class="cfg__bar-actions">
        <el-button :disabled="saving || changedCount === 0" @click="discard">放弃改动</el-button>
        <el-button v-if="canManage" type="primary" :loading="saving" :disabled="!canSave" @click="save">
          保存所有修改{{ changedCount > 0 ? `（${changedCount}）` : '' }}
        </el-button>
        <el-tag v-else type="info" effect="plain">只读</el-tag>
      </div>
    </div>

    <!--
      隐藏的原生 file input 必须放在 v-for 之外：
      模板 ref 在 v-for 内部会被收集成数组，`logoInputRef?.click()` 会静默失效。
    -->
    <input
      ref="logoInputRef"
      class="cfg__file"
      type="file"
      accept="image/png,image/jpeg"
      @change="onLogoSelected"
    />
  </div>
</template>

<style scoped>
.cfg {
  padding-bottom: 72px;
}

.cfg__head {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.cfg__title {
  margin: 0 0 4px;
  font-size: 16px;
  font-weight: 500;
}

.cfg__subtitle {
  margin: 0;
  font-size: 13px;
}

.cfg__search {
  width: 280px;
  max-width: 100%;
}

.cfg__alert {
  margin-bottom: 12px;
}

.cfg__body {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

/* ---------------- 左侧目录 ---------------- */

.cfg__nav {
  position: sticky;
  top: 12px;
  flex: 0 0 168px;
  width: 168px;
  padding: 12px;
}

.cfg__nav-title {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--ts-text-secondary);
}

.cfg__nav-list {
  padding: 0;
  margin: 0;
  list-style: none;
}

.cfg__nav-item {
  display: flex;
  gap: 6px;
  align-items: center;
  justify-content: space-between;
  padding: 7px 8px;
  margin-bottom: 2px;
  font-size: 13px;
  color: var(--ts-text-regular, #4b5563);
  cursor: pointer;
  border-radius: 6px;
  transition: background-color 0.15s;
}

.cfg__nav-item:hover {
  background: var(--ts-fill-light, #f5f7fa);
}

.cfg__nav-item.is-active {
  font-weight: 600;
  color: var(--ts-primary);
  background: var(--ts-primary-light, #ecf3ff);
}

.cfg__nav-empty {
  margin: 0;
  font-size: 12px;
}

/* ---------------- 右侧卡片 ---------------- */

.cfg__cards {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.cfg__card {
  scroll-margin-top: 12px;
}

.cfg__card-head {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: flex-start;
  justify-content: space-between;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--ts-border);
}

.cfg__card-title {
  display: flex;
  gap: 8px;
  align-items: center;
  margin: 0 0 4px;
  font-size: 15px;
  font-weight: 600;
}

.cfg__card-desc {
  margin: 0;
  font-size: 13px;
}

.cfg__card-actions {
  display: flex;
  gap: 4px;
  align-items: center;
}

/* ---------------- 卡片内的分区 ---------------- */

/*
 * 分区之间的分隔只靠一条浅色虚线 + 间距。
 * 刻意不加边框 / 底色：分区是「同一张卡片里的几节」，做成嵌套卡片会让页面
 * 出现三层方框（页 → 卡 → 子卡），层级反而更难看清。
 */
.cfg__section + .cfg__section {
  padding-top: 14px;
  margin-top: 14px;
  border-top: 1px dashed var(--ts-border);
}

.cfg__section-head {
  margin-bottom: 4px;
}

.cfg__section-title {
  display: flex;
  gap: 6px;
  align-items: center;
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--ts-text-regular, #4b5563);
}

.cfg__section-desc {
  margin: 2px 0 0;
  font-size: 12px;
  line-height: 1.5;
}

.cfg__section-alert {
  margin: 8px 0;
}

/* 通道关闭时整节降透明度：让「这一节现在不可配」在视觉上先于读文字成立 */
.cfg__section.is-locked .cfg__field {
  opacity: 0.72;
}

.cfg__section-notes {
  padding-top: 8px;
  margin-top: 8px;
  border-top: 1px solid var(--ts-border);
}

.cfg__section-notes p {
  margin: 0 0 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ts-text-secondary);
}

/*
  ：字段不再「一项独占一整行」。

  ⚠️ 12 栅格挂在**分区**（.cfg__section）上，不是挂在 .cfg__fields 上。
     .cfg__fields 的直接子元素是分区，真正的参数项在分区**里面**；挂在 .cfg__fields 上时，
     `grid-column: span 4` 会被套在「分区」这个唯一子元素身上 ⇒ 看着像生效、其实等于没改
     （ 首轮就是这么错的：类型检查与 lint 全绿，只有浏览器实测的几何能抓到）。
     分区内的非参数项（小标题 / 提示 / 参考说明）一律整行，只有 .cfg__field 参与栅格。

  ⚠️ 跨度只是**宽屏**的排布建议 —— 窄屏一律降为整行（见文件末尾的媒体查询）。
     半行宽度会把「SMTP 服务器地址」「附件存储目录」这类长值挤成省略号，
     而这类页面里「值看不全」比「不紧凑」代价大得多。

  ⚠️ 每一项内是**纵向**排列（名字 → 说明 → 控件），不是原先的「左名右控件」：
     半行宽度里再左右分栏，控件只剩不到 120px。
     因此下面所有 `flex: <basis>` 都必须去掉 —— 在 column 方向上，
     `flex-basis` 作用于**高度**，`flex: 1 1 100%` 会把一行撑成整屏高。
*/
.cfg__fields {
  display: flex;
  flex-direction: column;
  /* 分区之间留距离：分区内是 10px 的栅格行距，两者不能是同一个量级 */
  gap: 18px;
}

.cfg__section {
  display: grid;
  grid-template-columns: repeat(12, minmax(0, 1fr));
  gap: 10px 14px;
}

/* 分区内除参数项以外的一切（小标题 / 通道提示 / 参考说明）都占整行 */
.cfg__section > * {
  grid-column: 1 / -1;
}

.cfg__field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px;
  background: var(--ts-bg);
  border-radius: 8px;
}

.cfg__field.is-span-short {
  grid-column: span 4;
}

.cfg__field.is-span-medium {
  grid-column: span 6;
}

.cfg__field.is-span-full {
  grid-column: span 12;
}

.cfg__field.is-disabled .cfg__field-name {
  color: var(--ts-text-secondary);
}

.cfg__field-label {
  min-width: 0;
}

.cfg__field-name {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  font-size: 14px;
  font-weight: 500;
}

.cfg__field-desc {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--ts-text-secondary);
}

.cfg__field-control {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

/* 控件填满格子：格子宽度已经由跨度决定，再叠一个 340px 上限会在宽格里留出空白 */
.cfg__control {
  width: 100%;
}

.cfg__field-unit {
  font-size: 13px;
  color: var(--ts-text-secondary);
}

.cfg__field-tip {
  font-size: 12px;
}

.cfg__field.is-error .cfg__control {
  --el-input-border-color: var(--el-color-danger);
}

.cfg__field-error {
  margin: 0;
  font-size: 12px;
  color: var(--el-color-danger);
  text-align: left;
}

.cfg__card-notes {
  padding-top: 10px;
  margin-top: 10px;
  border-top: 1px solid var(--ts-border);
}

.cfg__card-notes p {
  margin: 0 0 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ts-text-secondary);
}

.cfg__card-footer {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  padding-top: 12px;
  margin-top: 12px;
  border-top: 1px solid var(--ts-border);
}

.cfg__footer-tip {
  font-size: 12px;
}

/* logo 预览：与侧边栏 mark 同尺寸，看到的就是实际效果 */
.cfg__logo-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  overflow: hidden;
  font-size: 15px;
  font-weight: 500;
  color: #fff;
  background: var(--ts-primary);
  border: 1px solid var(--ts-border);
  border-radius: 10px;
}

/* 隐藏原生 file input：由「上传图片」按钮代为触发 */
.cfg__file {
  display: none;
}

/* ---------------- 底部固定保存栏 ---------------- */

.cfg__bar {
  position: fixed;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 10;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  padding: 10px 24px;
  background: var(--ts-card-bg, #fff);
  border-top: 1px solid var(--ts-border);
  box-shadow: 0 -2px 8px rgb(0 0 0 / 4%);
}

.cfg__bar-status {
  font-size: 13px;
}

.cfg__bar-ok {
  color: var(--el-color-success);
}

.cfg__bar-error {
  color: var(--el-color-danger);
}

.cfg__bar-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

@media (max-width: 1199px) {
  /* 中等屏：一行最多两个 —— 短项从 4 列放宽到 6 列，否则输入框会被压得过窄 */
  .cfg__field.is-span-short {
    grid-column: span 6;
  }
}

@media (max-width: 900px) {
  /* 窄屏：一律整行（跨度是宽屏建议，这里统一作废） */
  .cfg__field.is-span-short,
  .cfg__field.is-span-medium {
    grid-column: span 12;
  }

  .cfg__body {
    flex-direction: column;
  }

  .cfg__nav {
    position: static;
    flex-basis: auto;
    width: 100%;
  }

  .cfg__nav-list {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
  }

  .cfg__nav-item {
    margin-bottom: 0;
  }

  .cfg__search {
    width: 100%;
  }

  .cfg__field-control {
    justify-content: flex-start;
  }

  .cfg__field-error {
    text-align: left;
  }

  .cfg__bar {
    padding: 10px 12px;
  }

  .cfg__bar-actions .el-button {
    flex: 1 1 auto;
  }
}
</style>
