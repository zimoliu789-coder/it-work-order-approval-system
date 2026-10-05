<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { adApi } from '@/api/ad'
import { roleApi } from '@/api/role'
import { useResponsive } from '@/composables/useResponsive'
import {
  AD_DEFAULTS,
  SYNC_HOUR_OPTIONS,
  type AdConfigVO,
  type AdDnPreviewVO,
  type AdSyncResultVO,
  type AdTestResultVO
} from '@/types/ad'
import type { RoleCode } from '@/types/api'
import {
  buildAdPayload,
  emptyAdForm,
  fillAdForm,
  firstMissingField,
  lastSyncSucceeded,
  type AdFormState
} from '@/utils/adForm'

/**
 * AD 域控配置（；需求文档 五·「简化 + 合并」）
 *
 * <h2>本页要解决的问题：维护人员不需要懂 DN</h2>
 * <p>改造前这里是一张 11 个字段的技术表单，其中「基础 DN」「绑定 DN」最容易填错，
 * 而且填错了<b>不会报错</b> —— 只会在别人登录时变成一句「账号或密码错误」。
 *  把主流程压成 4 个必填项（大白话标签 + 示例）：
 * 启用开关、域服务器地址、域管理员账号、域管理员密码。
 * 其余全部由后端 {@code AdDnResolver} 自动处理（端口 389/636、基础 DN、绑定 DN、属性映射）。
 *
 * <h2>三项设计取舍</h2>
 * <ol>
 *   <li><b>推导结果必须可见</b>：主表单下方常驻一行「系统自动使用：基础 DN = …」。
 *       这是 {@code POST /api/ad/derive-dn} 的返回值，与保存走<b>同一份后端规则</b>，
 *       因此界面显示什么、库里就存什么。「自动处理」如果看不见，出错时维护人员无从判断
 *       该不该去高级选项覆盖。</li>
 *   <li><b>「测试连接并保存」是一个动作，但闸门没有退化</b>：先校验 4 个必填项，
 *       再真的去域控完成连接 + 绑定 + 探测，只有 {@code testPassed} 为 true 才落库。
 *       合成一个按钮只是省掉一次点击，不是「测试成功就无脑保存」。</li>
 *   <li><b>表单规则全部落在 {@code utils/adForm.ts}</b>（纯函数、有单测）：
 *       本次的价值几乎全在「留空 / 填了」的语义差异上，把它留在组件里等于放弃回归保护。</li>
 * </ol>
 *
 * <h2>同步设置并入本页</h2>
 * <p>「每日自动同步」的开关与时刻原先在「系统参数设置」的 AD 分组里（{@code ad_sync_enabled} /
 * {@code ad_sync_hour}）， 搬到 {@code ad_config}（迁移 V36）并呈现在本页 ——
 * 要求「AD 的连接 + 同步一个页面搞定」。
 */
const { isMobile } = useResponsive()

const loading = ref(false)
/** 「测试连接并保存」一次完整流程（校验 → 测试 → 落库）进行中的忙碌标记 */
const submitting = ref(false)
const syncing = ref(false)

const config = ref<AdConfigVO | null>(null)
const testResult = ref<AdTestResultVO | null>(null)
const syncResult = ref<AdSyncResultVO | null>(null)
/** 自动推导预览（基础 DN / 绑定 DN 的系统取值） */
const dnPreview = ref<AdDnPreviewVO | null>(null)

/**
 * 本次会话内「测试连接已成功」。
 *
 * <p>启用 AD 时它是保存的前置条件（见 {@link handleSubmit}）。
 * 用独立布尔量而不是「拿 lastTestResult 判断」：后者是**历史**结论
 * （判据是 `ad_config.last_test_at`），可能是几天前测的，而配置早已被改过。
 * 任何连接相关字段被改动都会通过 {@link invalidateTest} 立即作废它。
 */
const testPassed = ref(false)

/** 「高级选项」折叠面板：默认收起 */
const advancedOpen = ref<string[]>([])

/** 角色下拉（默认角色）：复用「角色与权限」的可分配角色接口，支持自定义角色 */
const roleOptions = ref<Array<{ value: string; label: string }>>([])

const form = reactive<AdFormState>(emptyAdForm())

const bindPasswordConfigured = ref(false)
const securityWarning = ref<string | null>(null)

/** 连接相关字段被改动 ⇒ 上一次的测试结论作废 */
function invalidateTest(): void {
  testPassed.value = false
}

/** 端口随 SSL 切换给出建议值，但不强制覆盖用户输入 */
function handleSslChange(value: string | number | boolean): void {
  // 先作废测试结论：端口/协议变了，之前的连通性证明不再有效
  invalidateTest()
  // el-switch 的 change 事件参数类型是 string | number | boolean，这里统一按真值判断
  const on = value === true
  // 只在「当前端口是另一种协议的默认端口」时才自动切换，
  // 避免把用户手填的非标准端口（如 10389）冲掉
  if (on && form.serverPort === AD_DEFAULTS.port) {
    form.serverPort = AD_DEFAULTS.ldapsPort
  } else if (!on && form.serverPort === AD_DEFAULTS.ldapsPort) {
    form.serverPort = AD_DEFAULTS.port
  }
}

const passwordPlaceholder = computed(() =>
  bindPasswordConfigured.value ? '已配置（留空表示不修改）' : '请输入域管理员密码'
)

const syncHourOptions = SYNC_HOUR_OPTIONS

/** 主按钮文案：启用时是「测试连接并保存」，未启用时只是保存（没有可测的连接） */
const submitLabel = computed(() => (form.enabled ? '测试连接并保存' : '保存配置'))

/** 上次同步是否有失败项（解析留痕文本；无记录时为 null） */
const lastSyncOk = computed(() => lastSyncSucceeded(config.value?.lastSyncResult))

// ----------------------------------------------------------------------
// 自动推导预览
// ----------------------------------------------------------------------

/** 输入停顿 400ms 后再请求预览，避免每敲一个字符打一次后端 */
let previewTimer: number | undefined

function schedulePreview(): void {
  invalidateTest()
  if (previewTimer !== undefined) {
    window.clearTimeout(previewTimer)
  }
  previewTimer = window.setTimeout(() => {
    void refreshPreview()
  }, 400)
}

async function refreshPreview(): Promise<void> {
  const serverUrls = form.serverUrls.trim()
  if (!serverUrls) {
    dnPreview.value = null
    return
  }
  try {
    dnPreview.value = await adApi.deriveDn({
      serverUrls,
      baseDn: form.baseDn.trim() || null,
      bindDn: form.bindDn.trim() || null
    })
  } catch {
    // 预览是辅助信息，失败不阻断主流程（保存时的服务端归一化仍然生效）
    dnPreview.value = null
  }
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const vo = await adApi.config()
    // 先只拿「域地址 → 基础 DN」的纯推导结果，用于判断库里那个值是自动填的还是人工填的
    // （见 utils/adForm.ts#fillAdForm 的注释）
    let derivedBaseDn: string | null = null
    if ((vo.serverUrls ?? '').trim()) {
      try {
        derivedBaseDn = (await adApi.deriveDn({ serverUrls: vo.serverUrls ?? '' })).baseDn ?? null
      } catch {
        derivedBaseDn = null
      }
    }
    Object.assign(form, fillAdForm(vo, derivedBaseDn))
    config.value = vo
    bindPasswordConfigured.value = vo.bindPasswordConfigured === true
    securityWarning.value = vo.securityWarning ?? null
    await refreshPreview()
  } catch {
    // 错误由请求层统一提示
  } finally {
    loading.value = false
  }
}

async function loadRoleOptions(): Promise<void> {
  try {
    const list = await roleApi.options()
    roleOptions.value = list.map((item) => ({ value: item.roleCode as RoleCode, label: item.roleName }))
  } catch {
    roleOptions.value = [{ value: 'user', label: '普通员工' }]
  }
}

// ----------------------------------------------------------------------
// 提交：校验 → 测试连接 → 保存
// ----------------------------------------------------------------------

async function handleSubmit(): Promise<void> {
  if (form.enabled) {
    const missing = firstMissingField(form, bindPasswordConfigured.value)
    if (missing) {
      ElMessage.warning(missing)
      return
    }
  }
  const payload = buildAdPayload(form)
  submitting.value = true
  testResult.value = null
  try {
    let probed: number | null = null
    if (form.enabled) {
      // 闸门：启用时必须先测通。测不通就到此为止，绝不落库 ——
      // 一份连不上的 AD 配置会让全公司登不进来，而「保存」是即时生效的
      const result = await adApi.testConnection(payload)
      testResult.value = result
      testPassed.value = result.ok === true
      if (!testPassed.value) {
        ElMessage.error('连接测试未通过，配置未保存 —— 请按下方提示修改后重试')
        return
      }
      probed = result.userCount ?? null
    } else {
      testPassed.value = false
    }

    await adApi.save(payload)
    ElMessage.success(
      probed == null ? '配置已保存' : `连接成功，探测到 ${probed} 个用户，配置已保存`
    )
    await load()
  } catch {
    testPassed.value = false
    // 错误由请求层统一提示
  } finally {
    submitting.value = false
  }
}

async function handleSync(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '确认立即同步域用户？同步会按登录名增量更新本地账号：新增域账号、刷新已有账号的姓名 / 邮箱 / 手机号 / 部门，' +
        '并把「域控中已删除或已禁用」的账号在本地禁用（不物理删除，保留历史工单）。',
      '立即同步',
      { type: 'warning', confirmButtonText: '确认同步', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  syncing.value = true
  syncResult.value = null
  try {
    syncResult.value = await adApi.sync()
    ElMessage.success('同步完成')
    await load()
  } catch {
    // 错误由请求层统一提示
  } finally {
    syncing.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadRoleOptions(), load()])
})
</script>

<template>
  <div v-loading="loading" class="ts-page ts-ad">
    <!-- ============ 主卡片：4 项必填 + 一键测试并保存 ============ -->
    <section class="ts-card">
      <h3 class="ts-ad__title">域账号登录配置</h3>
      <p class="ts-text-secondary ts-ad__desc">
        只需填写下面 4 项即可。端口、基础 DN、绑定 DN、属性映射等由系统自动处理，
        测试连接成功后会自动保存；需要手工干预时再展开页面底部的「高级选项」。
      </p>

      <el-alert
        v-if="securityWarning"
        :title="securityWarning"
        type="warning"
        :closable="false"
        show-icon
        class="ts-ad__alert"
      >
        <p class="ts-ad__alert-body">
          跳过证书校验意味着无法识别域控是否被冒充（中间人可截获域口令）。
          仅在内网自建 CA 未导入系统信任库时临时使用，并尽快把根证书导入信任库后改回严格校验。
        </p>
      </el-alert>

      <el-alert
        v-else-if="config && !config.enabled"
        title="域账号登录当前未启用，全部账号走本地密码认证"
        type="info"
        :closable="false"
        show-icon
        class="ts-ad__alert"
      />

      <el-form
        label-width="130px"
        :label-position="isMobile ? 'top' : 'right'"
        class="ts-ad__form"
        @submit.prevent
      >
        <el-form-item label="启用域账号登录">
          <el-switch v-model="form.enabled" active-text="启用" inactive-text="关闭" @change="invalidateTest" />
          <span class="ts-ad__field-hint">
            关闭时全部账号走本地密码；开启后除超级管理员外的账号优先走域认证（域控连不上时自动降级本地密码）。
          </span>
        </el-form-item>

        <el-form-item label="域服务器地址">
          <el-input
            v-model="form.serverUrls"
            placeholder="例如 dc01.company.com 或 192.168.1.10"
            @input="schedulePreview"
          />
          <span class="ts-ad__field-hint">
            填域控的主机名或 IP。多台用英文逗号分隔（按顺序主备）：<code>dc01.company.com,dc02.company.com</code>。
            端口由系统自动处理（普通 389、开启加密 636），无需填写。
          </span>
        </el-form-item>

        <el-form-item label="域管理员账号">
          <el-input
            v-model="form.bindDn"
            placeholder="例如 company\query 或 query@company.com"
            @input="schedulePreview"
          />
          <span class="ts-ad__field-hint">
            用于查询域账号的服务账号，只需「读取用户」权限。三种写法都支持，系统会自动转成完整 DN：
            <code>company\query</code>、<code>query@company.com</code>、或完整 DN
            <code>CN=query,CN=Users,DC=company,DC=com</code>。
          </span>
        </el-form-item>

        <el-form-item label="域管理员密码">
          <el-input
            v-model="form.bindPassword"
            type="password"
            show-password
            :placeholder="passwordPlaceholder"
            @input="invalidateTest"
          />
          <span class="ts-ad__field-hint">
            密码以 AES-GCM 加密存储，接口只返回 ****；<b>留空表示不修改</b>，请勿把 **** 提交回来。
          </span>
        </el-form-item>

        <!-- 自动推导结果：必须可见，否则「系统自动处理」就是一句看不见的承诺 -->
        <el-form-item v-if="dnPreview" label="系统自动使用">
          <div class="ts-ad__derived">
            <div class="ts-ad__derived-row">
              <span class="ts-ad__derived-label">基础 DN</span>
              <code class="ts-ad__derived-value">{{ dnPreview.baseDn ?? '（未能推导）' }}</code>
            </div>
            <div class="ts-ad__derived-row">
              <span class="ts-ad__derived-label">绑定账号</span>
              <code class="ts-ad__derived-value">{{ dnPreview.bindDn ?? '（未填写）' }}</code>
            </div>
            <p class="ts-ad__field-hint ts-ad__derived-hint">{{ dnPreview.hint }}</p>
          </div>
        </el-form-item>

        <el-form-item class="ts-ad__actions">
          <el-button
            type="primary"
            size="large"
            :loading="submitting"
            :disabled="loading"
            @click="handleSubmit"
          >
            {{ submitLabel }}
          </el-button>
          <el-button :disabled="loading || submitting" @click="load">重新加载</el-button>
          <span class="ts-ad__field-hint ts-ad__field-hint--inline">
            启用时会先测试连接，测通才保存 —— 配置错误不会因为「点了保存」而在生产生效。
          </span>
        </el-form-item>
      </el-form>
    </section>

    <!-- ============ 测试连接结果 ============ -->
    <el-card v-if="testResult" shadow="never" class="ts-ad__result">
      <template #header>
        <span>测试连接结果</span>
      </template>
      <el-result
        :icon="testResult.ok ? 'success' : 'error'"
        :title="testResult.ok ? '连接成功' : '连接失败'"
        :sub-title="testResult.message"
      />
      <el-descriptions :column="isMobile ? 1 : 3" border size="small">
        <el-descriptions-item label="目标主机">{{ testResult.host ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="探测到用户数">
          {{ testResult.userCount ?? '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="耗时">
          {{ testResult.elapsedMs != null ? `${testResult.elapsedMs} ms` : '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="传输安全">
          <el-tag :type="testResult.insecure ? 'warning' : 'success'" size="small" effect="plain">
            {{ testResult.insecure ? '未启用（或跳过证书校验）' : 'SSL/TLS 已启用' }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- ============ 同步设置（原「系统参数设置 → AD 域控」分组） ============ -->
    <el-card shadow="never" class="ts-ad__result">
      <template #header>
        <span>用户同步</span>
      </template>

      <el-form label-width="130px" :label-position="isMobile ? 'top' : 'right'" @submit.prevent>
        <el-form-item label="启用每日自动同步">
          <el-switch v-model="form.syncEnabled" active-text="启用" inactive-text="关闭" />
          <span class="ts-ad__field-hint">
            开启后每天到点自动从域控拉取账号变更；关闭则只能在这里手工「立即同步」。
            该开关随本页配置一起保存。
          </span>
        </el-form-item>

        <el-form-item label="每日同步时刻">
          <el-select v-model="form.syncHour" :disabled="!form.syncEnabled" style="width: 160px">
            <el-option
              v-for="item in syncHourOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
          <span class="ts-ad__field-hint">建议放在业务低峰期，默认凌晨 02:00。</span>
        </el-form-item>

        <el-form-item label="立即执行">
          <el-button type="warning" :loading="syncing" :disabled="loading" @click="handleSync">
            立即同步
          </el-button>
          <span class="ts-ad__field-hint ts-ad__field-hint--inline">
            不等定时任务，马上按登录名增量更新本地账号。
          </span>
        </el-form-item>

        <el-form-item label="上次同步结果">
          <div class="ts-ad__last-sync">
            <template v-if="config?.lastSyncAt">
              <el-tag
                v-if="lastSyncOk !== null"
                :type="lastSyncOk ? 'success' : 'warning'"
                size="small"
                effect="plain"
              >
                {{ lastSyncOk ? '成功' : '有失败项' }}
              </el-tag>
              <span class="ts-ad__last-sync-time">{{ config.lastSyncAt }}</span>
              <p class="ts-ad__last-sync-detail">{{ config.lastSyncResult ?? '-' }}</p>
            </template>
            <span v-else class="ts-ad__field-hint">尚未同步过。</span>
          </div>
        </el-form-item>
      </el-form>

      <!-- 刚刚手工同步过：给出结构化计数（留痕文本之外的可读版本） -->
      <div v-if="syncResult" class="ts-ad__sync-stats">
        <div class="ts-ad__stats">
          <el-statistic title="域账号总数" :value="syncResult.total" />
          <el-statistic title="新增" :value="syncResult.created" />
          <el-statistic title="更新" :value="syncResult.updated" />
          <el-statistic title="禁用" :value="syncResult.disabled" />
          <el-statistic title="无变化" :value="syncResult.unchanged" />
          <el-statistic title="失败" :value="syncResult.failed" />
        </div>
        <p class="ts-ad__result-msg">{{ syncResult.message }}</p>
        <el-alert
          v-if="syncResult.failures.length > 0"
          type="warning"
          :closable="false"
          show-icon
          class="ts-ad__alert"
        >
          <template #title>共 {{ syncResult.failures.length }} 条需要关注</template>
          <ul class="ts-ad__failures">
            <li v-for="item in syncResult.failures" :key="item">{{ item }}</li>
          </ul>
        </el-alert>
      </div>
    </el-card>

    <!-- ============ 高级选项（默认折叠） ============ -->
    <el-collapse v-model="advancedOpen" class="ts-ad__advanced">
      <el-collapse-item
        title="高级选项（端口 / 加密 / 自定义 DN / 用户筛选 / 属性映射 / 连接超时）"
        name="advanced"
      >
        <p class="ts-ad__advanced-note">
          以下项在主流程中由系统自动处理，<b>通常不需要修改</b>。
          只有在域控使用非标准配置、或需要限定同步范围时才来调整 ——
          改错它们会表现为「同步不到人」或「登录失败」，且不会在保存时报错。
        </p>

        <el-form label-width="150px" :label-position="isMobile ? 'top' : 'right'" @submit.prevent>
          <el-form-item label="端口">
            <el-input-number
              v-model="form.serverPort"
              :min="1"
              :max="65535"
              controls-position="right"
              @change="invalidateTest"
            />
            <span class="ts-ad__field-hint">
              默认 LDAP 389；开启加密后系统自动改用 636。全局编录为 3268（加密时 3269）。
            </span>
          </el-form-item>

          <el-form-item label="使用 LDAPS 加密">
            <el-switch v-model="form.useSsl" @change="handleSslChange" />
            <span class="ts-ad__field-hint">
              生产环境建议开启（SSL/TLS 加密传输，避免域口令明文经网络）。
            </span>
          </el-form-item>

          <el-form-item label="证书校验">
            <el-radio-group v-model="form.strictCert" @change="invalidateTest">
              <el-radio :value="true">严格校验（生产建议）</el-radio>
              <el-radio :value="false">跳过校验（有中间人风险）</el-radio>
            </el-radio-group>
            <span class="ts-ad__field-hint">
              仅在自建 CA 未导入信任库时临时跳过；跳过时页顶会常驻安全警告。
            </span>
          </el-form-item>

          <el-form-item label="自定义基础 DN">
            <el-input v-model="form.baseDn" placeholder="留空 = 由域地址自动推导" @input="schedulePreview" />
            <span class="ts-ad__field-hint">
              <b>留空即可</b>：系统会把域地址逐段转成 <code>DC=</code>，
              例如 <code>dc01.company.com</code> → <code>DC=company,DC=com</code>。
              填了就以你填的为准，系统不再改写。需要只同步某个部门时可填
              <code>OU=技术部,DC=company,DC=com</code>。
            </span>
          </el-form-item>

          <el-form-item label="自定义绑定 DN">
            <el-input
              v-model="form.bindDn"
              placeholder="与上方「域管理员账号」是同一个值"
              @input="schedulePreview"
            />
            <span class="ts-ad__field-hint">
              与主表单的「域管理员账号」是同一个值，这里只是方便粘贴 / 查看完整 DN。
              服务账号不放在默认的 <code>CN=Users</code> 容器时，把完整 DN 填在这里即可。
            </span>
          </el-form-item>

          <el-form-item label="用户筛选过滤">
            <el-input v-model="form.userFilter" placeholder="(&(objectClass=user)(sAMAccountName={0}))" />
            <span class="ts-ad__field-hint">
              <code>{0}</code> 是登录名占位符，全量同步时会被替换为 <code>*</code>。
              默认已覆盖标准域控；仅在需要排除特定 OU / 服务账号时才调整。
            </span>
          </el-form-item>

          <el-divider content-position="left">属性映射（系统已填好常用默认值）</el-divider>

          <el-form-item label="登录名属性">
            <el-input v-model="form.attrLogin" placeholder="sAMAccountName" />
          </el-form-item>

          <el-form-item label="姓名属性">
            <el-input v-model="form.attrName" placeholder="displayName（为空时回退 cn）" />
          </el-form-item>

          <el-form-item label="邮箱属性">
            <el-input v-model="form.attrEmail" placeholder="mail" />
          </el-form-item>

          <el-form-item label="手机号属性">
            <el-input v-model="form.attrPhone" placeholder="telephoneNumber（留空 = 不同步手机号）" />
            <span class="ts-ad__field-hint">
              同步进来的手机号会自动填到员工资料上，省掉每人手工补录。
              留空表示不同步；手机号在本地是唯一的，因此<b>已被他人占用时该字段会被自动跳过</b>，
              也不会覆盖员工自己绑定的号码。
            </span>
          </el-form-item>

          <el-form-item label="部门属性">
            <el-input v-model="form.attrDept" placeholder="department" />
          </el-form-item>

          <el-form-item label="账号状态属性">
            <el-input v-model="form.attrStatus" placeholder="userAccountControl" />
            <span class="ts-ad__field-hint">按 AD 的 ACCOUNTDISABLE 位判定账号是否被禁用</span>
          </el-form-item>

          <el-form-item label="新用户默认角色">
            <el-select v-model="form.defaultRole" style="width: 100%">
              <el-option v-for="item in roleOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
            <span class="ts-ad__field-hint">域账号首次登录自动建号 / 同步建号时分配的角色</span>
          </el-form-item>

          <el-form-item label="连接超时">
            <el-input-number
              v-model="form.connectTimeoutSeconds"
              :min="1"
              :max="30"
              controls-position="right"
              @change="invalidateTest"
            />
            <span class="ts-ad__field-hint">单位：秒。取值偏小可避免域控故障时拖慢全站登录</span>
          </el-form-item>
        </el-form>
      </el-collapse-item>
    </el-collapse>

    <!-- ============ 上次测试留痕 ============ -->
    <el-card v-if="config" shadow="never" class="ts-ad__result">
      <template #header>
        <span>上次测试记录</span>
      </template>
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="上次测试">
          {{ config.lastTestAt ?? '尚未测试' }}
          <span v-if="config.lastTestResult" class="ts-text-hint"> · {{ config.lastTestResult }}</span>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<style scoped>
.ts-ad__title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.ts-ad__desc {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.7;
}

.ts-ad__form {
  margin-top: 16px;
  max-width: 900px;
}

.ts-ad__alert {
  margin-top: 16px;
}

.ts-ad__alert-body {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.8;
}

.ts-ad__field-hint {
  display: block;
  margin-left: 12px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ts-text-hint);
}

.ts-ad__field-hint--inline {
  display: inline;
  margin-left: 12px;
}

/* 自动推导结果：用带底色的块突出「系统将会这样配」 */
.ts-ad__derived {
  width: 100%;
  padding: 10px 12px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
}

.ts-ad__derived-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 12px;
  line-height: 2;
}

.ts-ad__derived-label {
  flex: 0 0 64px;
  color: var(--ts-text-hint);
}

.ts-ad__derived-value {
  word-break: break-all;
  color: var(--el-text-color-primary);
}

.ts-ad__derived-hint {
  margin-left: 0;
  margin-top: 4px;
}

.ts-ad__advanced {
  margin-top: 16px;
  max-width: 1000px;
}

.ts-ad__advanced :deep(.el-collapse-item__content) {
  padding-bottom: 4px;
}

.ts-ad__advanced-note {
  margin: 0 0 12px;
  font-size: 12px;
  line-height: 1.8;
  color: var(--ts-text-hint);
}

.ts-ad__actions {
  margin-top: 8px;
}

.ts-ad__result {
  margin-top: 16px;
}

.ts-ad__result-msg {
  margin: 12px 0 0;
  font-size: 12px;
  line-height: 1.7;
}

.ts-ad__last-sync {
  width: 100%;
}

.ts-ad__last-sync-time {
  margin-left: 8px;
  font-size: 13px;
}

.ts-ad__last-sync-detail {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ts-text-hint);
  word-break: break-all;
}

.ts-ad__sync-stats {
  margin-top: 12px;
}

.ts-ad__failures {
  margin: 6px 0 0;
  padding-left: 18px;
  font-size: 12px;
  line-height: 1.8;
  max-height: 200px;
  overflow-y: auto;
}

/* 统计行：PC 一行排开，窄屏自动换行 */
.ts-ad__stats {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 24px;
  margin-bottom: 4px;
}

.ts-ad :deep(.el-statistic__head) {
  font-size: 12px;
}

.ts-ad :deep(.el-statistic__content) {
  font-size: 18px;
}

@media (max-width: 767px) {
  .ts-ad__field-hint {
    margin-left: 0;
  }

  .ts-ad__field-hint--inline {
    display: block;
    margin-left: 0;
    margin-top: 8px;
  }
}
</style>
