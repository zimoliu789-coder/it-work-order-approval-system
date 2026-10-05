<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Plus, Refresh, SwitchButton } from '@element-plus/icons-vue'
import { useUserStore } from '@/store/user'
import { haApi } from '@/api/ha'
import { useResponsive } from '@/composables/useResponsive'
import { copyText } from '@/utils/clipboard'
import {
  HA_HEARTBEAT_DEFAULT,
  HA_HEARTBEAT_MAX,
  HA_HEARTBEAT_MIN,
  formatHeartbeatAge,
  formatMoment,
  formatSyncDelay,
  haNodeStatusTag,
  haSyncStateHint,
  haSyncStateTag,
  switchoverActionLabel,
  type HaActionResult,
  type HaConfigDetail,
  type HaDeployGuide,
  type HaNodeCreatePayload,
  type HaNodeItem,
  type HaNodeUpdatePayload,
  type HaOverview,
  type HaSwitchoverAction
} from '@/types/ha'

/**
 * 主备双机热备配置（， ）
 *
 * <h2>「域名 + VIP」访问模型 —— 这个页面存在的理由</h2>
 * <pre>
 *   员工 ──(固定域名 oa.company.com)──▶ DNS ──▶ 虚拟 IP(VIP)
 *                                              │
 *                     主节点持有 VIP 并对外服务 ─┴─ 主节点宕机 ⇒ VIP 自动漂到备节点
 * </pre>
 * <p>关键性质是：<b>漂移的是 VIP，域名不变</b>。因此员工不需要知道「现在哪台是主」，
 * 这正是需求 [166] 行「员工不用管哪台是主」的落地形态；
 * 也让需求 [174] 行的「员工无感知」成为可验证的事实而不是一句承诺。
 *
 * <h2>这个页面刻意只做三件事</h2>
 * <ol>
 *   <li><b>把配置变成参数</b>：域名 / 两个 VIP / 网卡 / 心跳阈值填进来，系统记住；</li>
 *   <li><b>把系统知道的渲染成可直接抄用的文本</b>：`.env.ha` 片段与 keepalived 配置 ——
 *       需求 [157]/[158] 的痛点正是「维护人员不懂 keepalived / 主从复制，只能改配置文件」，
 *       所以交付物不能只是几个输入框，必须把「改哪个文件的哪一段」也生成出来；</li>
 *   <li><b>如实回报现实</b>：部署资产在不在、脚本能不能跑、本次是不是演练 ——
 *       全部由服务端探针给出并原样展示。这套界面最容易犯的错是「静默假成功」，
 *       而运维页面上的假成功比一个明确的报错危险得多。</li>
 * </ol>
 *
 * <h2>⚠️ 为什么「管理员密码」这一栏要写明「不保存」</h2>
 * <p>需求 [171] 行的交互里有「输入备机管理员密码」这一步。本系统<b>接收后立即丢弃</b>：
 * 一旦落库，这个库就从「业务数据泄露」升级为「整个内网所有服务器的控制权泄露」，
 * 而且它等价于一个内网横向移动工具。真正的握手由目标机上的
 * `setup-replication.sh` 以 root 执行完成。界面上必须把这一点写在输入框旁边，
 * 否则维护人员会以为点了确认就等于系统替他登录了备机 —— 那会导致
 * 「以为配好了、实际没配」这种最难排查的状态。
 *
 * <h2>关于演练模式（dry-run）</h2>
 * <p>后端默认 `app.ha.dry-run=true`：点「手动切换主备」只返回<b>将要执行的命令</b>，
 * 不真正执行。这是 fail-safe 取向 —— 执行成功意味着线上流量立刻换机器。
 * 页面必须把这一点说清楚，不能让维护人员把一次演练当成真实切换。
 */
const { isMobile } = useResponsive()
const userStore = useUserStore()

const canManage = computed(() => userStore.hasPerm('ha:manage'))

const loading = ref(false)
const saving = ref(false)
const overview = ref<HaOverview | null>(null)
const guide = ref<HaDeployGuide | null>(null)
/** 正在执行的切换动作（空串表示没有进行中的动作） */
const switching = ref<'' | HaSwitchoverAction>('')
const syncing = ref(false)
const actionResult = ref<HaActionResult | null>(null)

const enabled = computed(() => overview.value?.enabled === true)
const deployment = computed(() => overview.value?.deployment ?? null)
const nodes = computed<HaNodeItem[]>(() => overview.value?.nodes ?? [])
const detail = computed<HaConfigDetail | null>(() => overview.value?.config ?? null)

/** 部署指引里默认展开的折叠面板 */
const openSections = ref<string[]>(['env'])

// ------------------------------------------------------------------
// 配置表单（第 1 步）
// ------------------------------------------------------------------

const form = reactive({
  enabled: false,
  nodeName: '',
  domain: '',
  vipWeb: '',
  vipDb: '',
  vrrpIface: '',
  heartbeatTimeoutSeconds: HA_HEARTBEAT_DEFAULT
})

/** 用库里的值回填表单；缺省字段一律回落到空串（后端文本列是 NOT NULL DEFAULT ''） */
function fillForm(config: HaConfigDetail | null): void {
  form.enabled = config?.enabled === true
  form.nodeName = config?.nodeName ?? ''
  form.domain = config?.domain ?? ''
  form.vipWeb = config?.vipWeb ?? ''
  form.vipDb = config?.vipDb ?? ''
  form.vrrpIface = config?.vrrpIface ?? ''
  form.heartbeatTimeoutSeconds = config?.heartbeatTimeoutSeconds ?? HA_HEARTBEAT_DEFAULT
}

/**
 * 前端只挡「形状明显不对」的输入，不复制后端的完整校验规则。
 *
 * 权威判定在服务端（`HaConfigValidator`）—— 包括「两个 VIP 不能相同」
 * 「域名不含 http:// 与端口」「心跳阈值 3-600」等。这里只做一件事：
 * 让明显填错的用户在点保存之前就得到反馈，而不是提交一次再看到错误。
 * 刻意<b>不</b>在这里实现第二份完整规则：两份规则一旦不同步，
 * 会出现「前端放行、后端拒绝」且提示不一致的现象。
 */
function precheck(): string | null {
  const vipWeb = form.vipWeb.trim()
  const vipDb = form.vipDb.trim()
  if (form.enabled && !vipWeb) {
    return '启用主备时必须填写「Web 虚拟 IP」——员工统一访问的就是这个地址'
  }
  if (vipWeb && vipDb && vipWeb === vipDb) {
    return 'Web 虚拟 IP 与数据库虚拟 IP 不能相同：它们是两个相互独立的 vrrp_instance，用同一个地址会脑裂'
  }
  const heartbeat = form.heartbeatTimeoutSeconds
  if (heartbeat == null || heartbeat < HA_HEARTBEAT_MIN || heartbeat > HA_HEARTBEAT_MAX) {
    return `心跳超时取值范围为 ${HA_HEARTBEAT_MIN}-${HA_HEARTBEAT_MAX} 秒`
  }
  return null
}

async function saveConfig(): Promise<void> {
  const problem = precheck()
  if (problem) {
    ElMessage.warning(problem)
    return
  }
  saving.value = true
  try {
    await haApi.saveConfig({ ...form })
    ElMessage.success(form.enabled ? '主备配置已保存并启用' : '主备配置已保存')
    await refreshOverview()
    fillForm(detail.value)
  } finally {
    saving.value = false
  }
}

// ------------------------------------------------------------------
// 节点（第 2 步）
// ------------------------------------------------------------------

const addVisible = ref(false)
const adding = ref(false)
const addForm = reactive<HaNodeCreatePayload>({
  nodeName: '',
  nodeIp: '',
  adminPassword: '',
  remark: ''
})

const editVisible = ref(false)
const editing = ref(false)
const editId = ref<number | null>(null)
const editForm = reactive<HaNodeUpdatePayload>({ nodeName: '', remark: '' })

function openAdd(): void {
  addForm.nodeName = ''
  addForm.nodeIp = ''
  addForm.adminPassword = ''
  addForm.remark = ''
  addVisible.value = true
}

async function submitAdd(): Promise<void> {
  if (!addForm.nodeIp.trim()) {
    ElMessage.warning('请填写备机 IP 地址')
    return
  }
  adding.value = true
  try {
    await haApi.addNode({ ...addForm })
    // 安全起见：口令只存在于内存中，提交后立即清掉（后端也同样不使用它）
    addForm.adminPassword = ''
    addVisible.value = false
    ElMessage.success('备节点已登记，等待其上报心跳')
    await refreshOverview()
  } finally {
    adding.value = false
  }
}

function openEdit(row: HaNodeItem): void {
  editId.value = row.id
  editForm.nodeName = row.nodeName ?? ''
  editForm.remark = row.remark ?? ''
  editVisible.value = true
}

async function submitEdit(): Promise<void> {
  const id = editId.value
  if (id == null) {
    return
  }
  editing.value = true
  try {
    await haApi.updateNode(id, { ...editForm })
    editVisible.value = false
    ElMessage.success('节点信息已更新')
    await refreshOverview()
  } finally {
    editing.value = false
  }
}

async function removeNode(row: HaNodeItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `将移除备节点「${row.nodeName || row.nodeIp}」（${row.nodeIp}）。`
        + '移除后该机器不再出现在列表里；若它仍在运行，请同时停掉它上面的心跳脚本。是否继续？',
      '确认移除节点',
      { type: 'warning', confirmButtonText: '移除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await haApi.removeNode(row.id)
    ElMessage.success('节点已移除')
    await refreshOverview()
  } catch {
    // 失败提示由响应拦截器统一给出（例如「不能移除本机节点」）
  }
}

// ------------------------------------------------------------------
// 运维动作
// ------------------------------------------------------------------

async function doSwitchover(action: HaSwitchoverAction): Promise<void> {
  if (action !== 'status') {
    const warning =
      action === 'to-peer'
        ? '本机将让出虚拟 IP，由备节点接管（期间约 10~15 秒服务不可用）。'
          + '在 keepalived 配置了 nopreempt 的前提下，本机恢复后不会自动抢回。'
        : '将清除本机维护标记，等待 keepalived 重新仲裁虚拟 IP 归属 —— 本机可能重新持有虚拟 IP。'
    try {
      await ElMessageBox.confirm(
        `${warning}\n\n动作：${switchoverActionLabel(action)}`,
        '确认主备切换',
        { type: 'warning', confirmButtonText: '执行', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
  }

  switching.value = action
  try {
    actionResult.value = await haApi.switchover(action)
    notifyActionResult(actionResult.value)
    await refreshOverview()
  } catch {
    // 请求本身不成立（未启用 / 脚本不存在）→ 拦截器已给出明确错误，无需再弹
  } finally {
    switching.value = ''
  }
}

async function doSync(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '将重建数据库复制链路并触发一次全量同步。首次全量同步耗时取决于数据量，'
        + '期间两台机器的数据可能短暂不一致。是否继续？',
      '确认立即同步',
      { type: 'warning', confirmButtonText: '执行同步', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  syncing.value = true
  try {
    actionResult.value = await haApi.syncNow()
    notifyActionResult(actionResult.value)
    await refreshOverview()
  } catch {
    // 同上
  } finally {
    syncing.value = false
  }
}

/**
 * 动作结果提示。
 *
 * 三种结果必须给出<b>不同</b>的提示色与文案：
 * 演练（黄，且明确「未真正执行」）、失败（红，附脚本输出）、成功（绿）。
 * 若把演练也提示成绿色「切换成功」，维护人员会以为线上已经换过机器了。
 */
function notifyActionResult(result: HaActionResult): void {
  if (result.dryRun) {
    ElMessage.warning('演练模式：已生成命令，未真正执行')
  } else if (result.success) {
    ElMessage.success('动作已执行成功')
  } else {
    ElMessage.error('脚本执行失败，请查看下方输出定位原因')
  }
}

// ------------------------------------------------------------------
// 部署片段复制
// ------------------------------------------------------------------

async function copy(text: string, label: string): Promise<void> {
  const ok = await copyText(text)
  if (ok) {
    ElMessage.success(`${label}已复制到剪贴板`)
  } else {
    ElMessage.warning('复制失败，请手动选中文本复制')
  }
}

// ------------------------------------------------------------------
// 加载
// ------------------------------------------------------------------

// ------------------------------------------------------------------
// 一键化
// ------------------------------------------------------------------

/** 本机节点行（页面顶部状态区用） */
const localNode = computed<HaNodeItem | null>(() => nodes.value.find((node) => node.isLocal) ?? null)

/** 高级区默认折叠：常用路径是上面那两个按钮 */
const advancedSections = ref<string[]>([])

const quickBusy = ref(false)
const joinVisible = ref(false)
const joinForm = reactive({ masterIp: '', joinToken: '' })

/**
 * 一键启用主节点。
 *
 * <p>直接把当前表单里的配置一起提交：启用与「配好域名 / VIP」是同一件事的两面 ——
 * 分成两步会让用户点了启用却因为 VIP 没填而失败，而他并不知道该去哪填。
 * 校验失败时后端会给出明确原因（`HaConfigValidator`），前端原样展示。
 */
async function handleEnable(): Promise<void> {
  quickBusy.value = true
  try {
    const result = await haApi.enable({ ...form, enabled: true })
    ElMessage.success(result.message || '主节点已启用')
    await refreshAll(false)
  } catch {
    // 失败提示已由请求层统一弹出
  } finally {
    quickBusy.value = false
  }
}

async function handleJoin(): Promise<void> {
  if (!joinForm.masterIp.trim()) {
    ElMessage.warning('请填写主节点 IP')
    return
  }
  if (!joinForm.joinToken.trim()) {
    ElMessage.warning('请填写加入令牌')
    return
  }
  quickBusy.value = true
  try {
    const result = await haApi.joinCluster({
      masterIp: joinForm.masterIp.trim(),
      joinToken: joinForm.joinToken.trim()
    })
    ElMessage.success(result.message || '已加入集群')
    joinVisible.value = false
    joinForm.masterIp = ''
    joinForm.joinToken = ''
    await refreshAll(false)
  } catch {
    // 失败提示已由请求层统一弹出
  } finally {
    quickBusy.value = false
  }
}

async function refreshOverview(): Promise<void> {
  overview.value = await haApi.overview()
}

async function loadGuide(): Promise<void> {
  guide.value = await haApi.guide()
}

async function refreshAll(showLoading = true): Promise<void> {
  if (showLoading) {
    loading.value = true
  }
  try {
    await Promise.all([refreshOverview(), loadGuide()])
    fillForm(detail.value)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void refreshAll(true)
})

/** 供模板使用（避免在模板里直接引用 types 的函数造成可读性下降） */
const syncHint = computed(() => haSyncStateHint(detail.value?.syncState))
</script>

<template>
  <div v-loading="loading" class="ha-page">
    <!--
      一键化：默认只显示「状态 + 两个按钮」。
      详细配置与部署诊断收进下方默认折叠的「高级」区 ——
      说明是「管理员不用复制 .env 文件、不用手动跑 preflight 和 setup-replication 脚本」，
      常用路径必须短到一眼看完。
    -->
    <el-card class="ha-page__card ha-page__quick" shadow="never">
      <template #header>
        <div class="ha-page__card-head">
          <span>主备状态</span>
          <el-tag :type="enabled ? 'success' : 'info'" size="small" effect="plain">
            {{ enabled ? '已启用' : '未启用' }}
          </el-tag>
        </div>
      </template>

      <el-descriptions :column="isMobile ? 1 : 3" border size="small">
        <el-descriptions-item label="本机节点">
          {{ localNode ? `${localNode.nodeName}（${localNode.nodeRole}）` : '尚未登记' }}
        </el-descriptions-item>
        <el-descriptions-item label="节点数">{{ nodes.length }}</el-descriptions-item>
        <el-descriptions-item label="统一访问域名">{{ detail?.domain || '尚未配置' }}</el-descriptions-item>
      </el-descriptions>

      <div class="ha-page__quick-actions">
        <el-button type="primary" :loading="quickBusy" @click="handleEnable">启用本机为主节点</el-button>
        <el-button :loading="quickBusy" @click="joinVisible = true">加入集群（作为备节点）</el-button>
      </div>

      <!--
        ⚠️ 必须如实写出「不会自动切换」：本系统按拍板**不引入 keepalived**。
        假装能自动切，比明确说不能切危险得多 —— 维护人员会在故障当晚才发现
        「原来还要自己去改 DNS」。
      -->
      <el-alert
        class="ha-page__notice-tip"
        type="info"
        show-icon
        :closable="false"
        title="本系统不引入 keepalived：故障后需要手工或脚本切换 DNS / 虚拟 IP，不会自动切换。"
      />
    </el-card>

    <el-alert
      v-if="overview && !enabled"
      class="ha-page__notice"
      type="warning"
      show-icon
      :closable="false"
      title="主备双机热备当前未启用"
    >
      <template #default>
        <div>
          未启用时本页只能填写配置、查看节点与生成部署片段，
          <b>不能执行真实的切换与同步动作</b>。启用后系统才会把本机登记为主节点。
        </div>
        <div class="ha-page__notice-tip">
          启用步骤：① 打开下方「启用主备」开关并填写 Web 虚拟 IP 后保存；
          ② 添加备节点；③ 按本页「部署指引」在目标机器上完成 keepalived 与复制链路配置。
        </div>
      </template>
    </el-alert>

    <!-- ============================ 状态总览 ============================ -->
    <el-card class="ha-page__card" shadow="never">
      <template #header>
        <div class="ha-page__card-head">
          <span>状态总览</span>
          <el-button :icon="Refresh" text :loading="loading" @click="refreshAll(true)">刷新</el-button>
        </div>
      </template>

      <el-descriptions :column="isMobile ? 1 : 3" border>
        <el-descriptions-item label="主备开关">
          <el-tag :type="enabled ? 'success' : 'info'" size="small">
            {{ enabled ? '已启用' : '未启用' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="当前节点角色">
          <template v-if="overview?.localRole">
            <el-tag :type="haNodeStatusTag(overview.localStatus)" size="small">
              {{ overview.localRoleLabel }} · {{ overview.localStatusLabel }}
            </el-tag>
          </template>
          <span v-else class="ha-page__muted">尚未登记本机节点</span>
        </el-descriptions-item>
        <el-descriptions-item label="员工访问地址">
          <span v-if="detail?.displayAddress" class="ha-page__mono">{{ detail.displayAddress }}</span>
          <span v-else class="ha-page__muted">尚未配置</span>
        </el-descriptions-item>
        <el-descriptions-item label="部署资产">
          <el-tag v-if="!deployment?.haDirConfigured" type="info" size="small">未配置目录</el-tag>
          <el-tag v-else-if="!deployment.deployDirExists" type="danger" size="small">目录不存在</el-tag>
          <el-tag v-else type="success" size="small">已就绪</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="执行模式">
          <el-tag :type="deployment?.dryRunEnabled ? 'warning' : 'danger'" size="small">
            {{ deployment?.dryRunEnabled ? '演练（不真正执行）' : '真实执行' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="最后切换时间">
          {{ formatMoment(detail?.lastSwitchAt, '从未切换') }}
        </el-descriptions-item>
      </el-descriptions>

      <el-alert
        v-if="deployment"
        class="ha-page__probe"
        :type="deployment.switchoverScriptPresent && !deployment.dryRunEnabled ? 'error' : 'info'"
        show-icon
        :closable="false"
        :title="deployment.hint"
      >
        <div v-if="deployment.haDir" class="ha-page__probe-dir">
          部署资产目录：<code>{{ deployment.haDir }}</code>
        </div>
      </el-alert>

      <el-alert
        class="ha-page__model"
        type="info"
        show-icon
        :closable="false"
        title="员工的访问路径（域名 + 虚拟 IP / VIP）"
      >
        <div>
          员工访问 <b>固定域名</b> → DNS 解析到 <b>虚拟 IP（VIP）</b> → 当前持有虚拟 IP 的节点对外服务。
          主节点故障时虚拟 IP 会自动漂移到备节点，<b>域名不变、员工无感知、在途工单不丢失</b>。
        </div>
        <div class="ha-page__notice-tip">
          因此域名是比 IP 更重要的一项配置：只有域名能在主备切换时保持不变。
          没有域名时（纯 IP 访问）也能工作，但员工会看到地址变化。
        </div>
      </el-alert>
    </el-card>

    <!-- 高级配置与部署诊断（ 起默认折叠）：一键启用走不通时才需要看这里 -->
    <el-collapse v-model="advancedSections" class="ha-page__advanced">
      <el-collapse-item name="advanced" title="高级配置与部署诊断（手动配置 / 部署指引 / 环境变量 / keepalived）">
    <!-- ======================= 一步：启用与虚拟 IP ======================= -->
    <el-card class="ha-page__card" shadow="never">
      <template #header>
        <div class="ha-page__card-head">
          <span>第 1 步 · 启用主备与虚拟 IP</span>
          <span class="ha-page__muted">这里是主节点上的配置；备机上的配置由「部署指引」生成</span>
        </div>
      </template>

      <el-form label-width="140px" :disabled="!canManage">
        <el-form-item label="启用主备">
          <el-switch v-model="form.enabled" />
          <span class="ha-page__muted ha-page__inline-tip">
            保存并启用后，系统会把本机自动登记为主节点；关闭开关不会删除已登记的节点
          </span>
        </el-form-item>
        <el-form-item label="本机节点名称">
          <el-input v-model="form.nodeName" maxlength="64" placeholder="如 ticket-master（留空则自动取名）" />
        </el-form-item>
        <el-form-item label="员工访问域名">
          <el-input v-model="form.domain" maxlength="128" placeholder="如 oa.company.com（留空表示直连虚拟 IP）" />
        </el-form-item>
        <el-form-item label="Web 虚拟 IP">
          <el-input v-model="form.vipWeb" maxlength="64" placeholder="如 192.168.1.100" />
          <span class="ha-page__muted ha-page__inline-tip">员工实际访问的漂移地址，启用时必填</span>
        </el-form-item>
        <el-form-item label="数据库虚拟 IP">
          <el-input v-model="form.vipDb" maxlength="64" placeholder="如 192.168.1.101（可与 Web VIP 不同）" />
          <span class="ha-page__muted ha-page__inline-tip">刻意与 Web VIP 分开：两者互不干扰，也不允许相同</span>
        </el-form-item>
        <el-form-item label="VRRP 网卡">
          <el-input v-model="form.vrrpIface" maxlength="32" placeholder="如 eth0 / ens192（留空由脚本取默认路由网卡）" />
        </el-form-item>
        <el-form-item label="心跳超时（秒）">
          <el-input-number
            v-model="form.heartbeatTimeoutSeconds"
            :min="HA_HEARTBEAT_MIN"
            :max="HA_HEARTBEAT_MAX"
            :step="1"
          />
          <span class="ha-page__muted ha-page__inline-tip">
            默认 {{ HA_HEARTBEAT_DEFAULT }} 秒。不要设得比 keepalived 的健康检查更快，
            否则页面判定断连时虚拟 IP 可能还没漂移
          </span>
        </el-form-item>
      </el-form>

      <div class="ha-page__actions">
        <el-button type="primary" :loading="saving" :disabled="!canManage" @click="saveConfig">
          保存配置
        </el-button>
        <el-button :disabled="saving" @click="fillForm(detail)">放弃修改</el-button>
        <span v-if="!canManage" class="ha-page__muted">当前账号没有主备管理权限，仅可查看</span>
      </div>
    </el-card>

    <!-- =========================== 二步：节点 =========================== -->
    <el-card class="ha-page__card" shadow="never">
      <template #header>
        <div class="ha-page__card-head">
          <span>第 2 步 · 节点列表</span>
          <el-button
            type="primary"
            plain
            size="small"
            :icon="Plus"
            :disabled="!canManage || !enabled"
            @click="openAdd"
          >
            添加备节点
          </el-button>
        </div>
      </template>

      <el-alert
        v-if="!enabled"
        class="ha-page__hint"
        type="info"
        show-icon
        :closable="false"
        title="请先在上方打开「启用主备」并保存，再添加备节点"
      />

      <el-table :data="nodes" size="small" row-key="id">
        <el-table-column label="节点名称" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ (row as HaNodeItem).nodeName || (row as HaNodeItem).nodeIp }}</span>
            <el-tag v-if="(row as HaNodeItem).isLocal" class="ha-page__tag" type="success" size="small">
              本机
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="nodeIp" label="IP 地址" min-width="140" />
        <el-table-column label="角色" min-width="100">
          <template #default="{ row }">
            {{ (row as HaNodeItem).nodeRoleLabel }}
          </template>
        </el-table-column>
        <el-table-column label="状态" min-width="110">
          <template #default="{ row }">
            <el-tag :type="haNodeStatusTag((row as HaNodeItem).nodeStatus)" size="small">
              {{ (row as HaNodeItem).nodeStatusLabel }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最后心跳" min-width="170">
          <template #default="{ row }">
            <span v-if="(row as HaNodeItem).lastHeartbeatAt">
              {{ formatMoment((row as HaNodeItem).lastHeartbeatAt) }}
              <span class="ha-page__muted">
                （{{ formatHeartbeatAge((row as HaNodeItem).heartbeatAgeSeconds) }}）
              </span>
            </span>
            <span v-else class="ha-page__muted">从未上报</span>
          </template>
        </el-table-column>
        <el-table-column label="备注" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">
            {{ (row as HaNodeItem).remark || '—' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="140" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              size="small"
              :disabled="!canManage"
              @click="openEdit(row as HaNodeItem)"
            >
              编辑
            </el-button>
            <el-button
              link
              type="danger"
              size="small"
              :disabled="!canManage || !(row as HaNodeItem).removable"
              @click="removeNode(row as HaNodeItem)"
            >
              移除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="ha-page__muted ha-page__table-tip">
        系统采用主备双机形态，只允许登记一个备节点（keepalived 模板的 unicast_peer 只接受一个对端）。
        本机节点由「启用主备」自动登记，不可移除。
      </div>
    </el-card>

    <!-- ====================== 数据同步与运维动作 ====================== -->
    <el-card class="ha-page__card" shadow="never">
      <template #header>
        <div class="ha-page__card-head">
          <span>数据同步状态</span>
          <el-tag :type="haSyncStateTag(detail?.syncState)" size="small">
            {{ detail?.syncStateLabel || '未知' }}
          </el-tag>
        </div>
      </template>

      <el-alert
        class="ha-page__hint"
        :type="detail?.syncState === 'FAILED' ? 'error' : detail?.syncState === 'LAGGING' ? 'warning' : 'info'"
        show-icon
        :closable="false"
        :title="syncHint"
      />

      <el-descriptions class="ha-page__detail" :column="isMobile ? 1 : 3" border size="small">
        <el-descriptions-item label="最后同步时间">
          {{ formatMoment(detail?.lastSyncAt, '从未同步') }}
        </el-descriptions-item>
        <el-descriptions-item label="同步延迟">
          {{ formatSyncDelay(detail?.syncDelaySeconds) }}
        </el-descriptions-item>
        <el-descriptions-item label="最近切换">
          {{ formatMoment(detail?.lastSwitchAt, '从未切换') }}
        </el-descriptions-item>
      </el-descriptions>

      <div class="ha-page__actions">
        <el-button
          type="primary"
          :loading="syncing"
          :disabled="!canManage || !enabled"
          @click="doSync"
        >
          立即同步
        </el-button>
        <el-button
          :icon="SwitchButton"
          :loading="switching === 'to-peer'"
          :disabled="!canManage || !enabled"
          @click="doSwitchover('to-peer')"
        >
          本机让出（切到备节点）
        </el-button>
        <el-button
          :loading="switching === 'back'"
          :disabled="!canManage || !enabled"
          @click="doSwitchover('back')"
        >
          切回本机
        </el-button>
        <el-button
          text
          :loading="switching === 'status'"
          :disabled="!canManage || !enabled"
          @click="doSwitchover('status')"
        >
          查询维护标记
        </el-button>
      </div>

      <el-alert
        v-if="actionResult"
        class="ha-page__result"
        :type="actionResult.dryRun ? 'warning' : actionResult.success ? 'success' : 'error'"
        show-icon
        :closable="false"
        :title="actionResult.message"
      >
        <div class="ha-page__result-row">
          <span class="ha-page__muted">命令：</span>
          <code class="ha-page__code-inline">{{ actionResult.command }}</code>
          <el-button link type="primary" size="small" @click="copy(actionResult.command, '命令')">
            复制
          </el-button>
        </div>
        <div v-if="actionResult.dryRun" class="ha-page__result-row">
          <b>本次为演练（dry-run），未真正执行。</b>
          以上是系统将要运行的命令，可复制到目标机器上手工执行；
          确认无误后由运维配置 HA_DRY_RUN=false，本页才能直接执行。
        </div>
        <pre v-if="actionResult.output" class="ha-page__code">{{ actionResult.output }}</pre>
      </el-alert>
    </el-card>

    <!-- ============================ 部署指引 ============================ -->
    <el-card class="ha-page__card" shadow="never">
      <template #header>
        <div class="ha-page__card-head">
          <span>第 3 步 · 部署指引</span>
          <el-button
            :icon="Refresh"
            text
            :disabled="loading"
            @click="loadGuide"
          >
            重新生成
          </el-button>
        </div>
      </template>

      <el-alert
        class="ha-page__hint"
        type="info"
        show-icon
        :closable="false"
        :title="guide?.note || '正在生成部署片段…'"
      />

      <ol class="ha-page__steps">
        <li v-for="(step, index) in guide?.steps ?? []" :key="index">{{ step }}</li>
      </ol>

      <el-collapse v-model="openSections">
        <el-collapse-item name="env" title="环境变量片段（粘贴进两台机器的 deploy/ha/.env.ha）">
          <div class="ha-page__code-head">
            <span class="ha-page__muted">
              密钥与口令一律是 __CHANGE_ME__ 占位符 —— 本系统不知道也不应该知道它们的值
            </span>
            <el-button link type="primary" size="small" @click="copy(guide?.envSnippet ?? '', '环境变量片段')">
              复制全部
            </el-button>
          </div>
          <pre class="ha-page__code">{{ guide?.envSnippet }}</pre>
        </el-collapse-item>
        <el-collapse-item name="keepalived" title="keepalived 配置（由模板 + 当前配置渲染）">
          <div v-if="guide?.keepalivedConf" class="ha-page__code-head">
            <span class="ha-page__muted">
              该文件需在目标机器上以 root 写入 /etc/keepalived/keepalived.conf
            </span>
            <el-button
              link
              type="primary"
              size="small"
              @click="copy(guide?.keepalivedConf ?? '', 'keepalived 配置')"
            >
              复制全部
            </el-button>
          </div>
          <pre v-if="guide?.keepalivedConf" class="ha-page__code">{{ guide?.keepalivedConf }}</pre>
          <el-alert
            v-else
            type="warning"
            show-icon
            :closable="false"
            title="服务器上没有找到 keepalived 配置模板，无法渲染"
          >
            <div>
              请先按 <code>deploy/DEPLOY.md</code> 把 <code>deploy/ha</code> 部署到服务器，
              并确认 <code>app.ha.deploy-dir</code> 指向该目录。
              环境变量片段仍然可以直接使用 —— 它只依赖本页已保存的配置值。
            </div>
          </el-alert>
        </el-collapse-item>
      </el-collapse>
    </el-card>
      </el-collapse-item>
    </el-collapse>

    <!-- 加入集群弹窗：只问两件事 —— 主节点 IP 与加入令牌 -->
    <el-dialog v-model="joinVisible" title="加入集群（作为备节点）" width="520px">
      <el-form label-width="120px">
        <el-form-item label="主节点 IP" required>
          <el-input v-model="joinForm.masterIp" placeholder="如 192.168.1.100" />
        </el-form-item>
        <el-form-item label="加入令牌" required>
          <el-input v-model="joinForm.joinToken" placeholder="主节点页面上的「内部通道令牌」" />
        </el-form-item>
      </el-form>
      <p class="ha-page__muted">
        令牌必须与主节点的 INTERNAL_ALERT_TOKEN 逐字一致；两台机器不一致时，
        心跳会被对端拒绝（403），表现为「节点状态一直不更新」。
      </p>
      <template #footer>
        <el-button @click="joinVisible = false">取消</el-button>
        <el-button type="primary" :loading="quickBusy" @click="handleJoin">加入</el-button>
      </template>
    </el-dialog>

    <!-- ======================= 添加备节点弹窗 ======================= -->
    <el-dialog v-model="addVisible" title="添加备节点" width="520px">
      <el-form label-width="120px">
        <el-form-item label="备机 IP" required>
          <el-input v-model="addForm.nodeIp" placeholder="如 192.168.1.101（当前仅支持 IPv4）" />
        </el-form-item>
        <el-form-item label="节点名称">
          <el-input v-model="addForm.nodeName" placeholder="如 ticket-slave（留空则按 IP 自动取名）" />
        </el-form-item>
        <el-form-item label="管理员密码">
          <el-input v-model="addForm.adminPassword" type="password" show-password placeholder="可不填" />
          <div class="ha-page__muted ha-page__inline-tip">
            ⚠️ <b>本系统不会保存、也不会转发这个口令</b>（提交后立即丢弃）。
            它只用于确认你知道自己在做什么；真正的握手需要在备机上以 root 执行
            <code>scripts/setup-replication.sh</code>。系统不代替你登录备机。
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="addForm.remark" maxlength="200" placeholder="如「机房 B 备用机」" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addVisible = false">取消</el-button>
        <el-button type="primary" :loading="adding" @click="submitAdd">确认添加</el-button>
      </template>
    </el-dialog>

    <!-- ========================= 编辑节点弹窗 ========================= -->
    <el-dialog v-model="editVisible" title="编辑节点" width="520px">
      <el-form label-width="120px">
        <el-form-item label="节点名称">
          <el-input v-model="editForm.nodeName" placeholder="留空则展示时回落到 IP" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="editForm.remark" maxlength="200" placeholder="留空 = 清空" />
        </el-form-item>
        <el-form-item label="IP 地址">
          <el-input :model-value="nodes.find((n) => n.id === editId)?.nodeIp ?? ''" disabled />
          <div class="ha-page__muted ha-page__inline-tip">
            IP 是节点身份（心跳按它对齐行），不可修改。要换 IP 请「移除旧节点 + 添加新节点」，
            这样两次动作都会留下审计。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editing" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ha-page {
  padding: 16px;
}

.ha-page__notice {
  margin-bottom: 16px;
}

.ha-page__notice-tip {
  margin-top: 8px;
  font-size: 13px;
}

.ha-page__card {
  margin-bottom: 16px;
}

.ha-page__card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.ha-page__muted {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.ha-page__inline-tip {
  margin-left: 8px;
}

.ha-page__tag {
  margin-left: 6px;
}

.ha-page__probe,
.ha-page__model,
.ha-page__hint {
  margin-top: 12px;
}

.ha-page__probe-dir {
  margin-top: 4px;
  font-size: 13px;
  word-break: break-all;
}

.ha-page__detail {
  margin-top: 12px;
}

.ha-page__table-tip {
  margin-top: 12px;
}

.ha-page__actions {
  display: flex;
  gap: 8px;
  margin-top: 16px;
  flex-wrap: wrap;
  align-items: center;
}

.ha-page__result {
  margin-top: 16px;
}

.ha-page__result-row {
  margin-top: 6px;
  font-size: 13px;
  word-break: break-all;
}

.ha-page__steps {
  margin: 12px 0 0;
  padding-left: 20px;
  line-height: 1.9;
}

.ha-page__code-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 6px;
}

.ha-page__code {
  margin: 0;
  padding: 12px;
  max-height: 420px;
  overflow: auto;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  font-family: var(--el-font-family-monospace, monospace);
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-all;
}

.ha-page__code-inline {
  font-family: var(--el-font-family-monospace, monospace);
  background: var(--el-fill-color-light);
  padding: 1px 4px;
  border-radius: 3px;
}
</style>
