<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import authApi from '@/api/auth'
import { ApiError } from '@/api/request'
import { useListPreferences } from '@/composables/useListPreferences'
import { useResponsive } from '@/composables/useResponsive'
import { useUserStore } from '@/store/user'
import type { ContactType } from '@/types/api'
import permissionPolicyApi from '@/api/permissionPolicy'
import type { MyPermissionItem } from '@/types/permissionPolicy'
import { ROLE_LABELS } from '@/types/user'

/**
 * 个人中心（ 菜单：个人中心 / 修改密码 / 退出登录）
 *
 * 除基本信息外，还承载两类「属于我自己的设置」：
 * - **联系方式**（上线前）：每位员工都可以自行修改自己的手机号与邮箱，
 *   用于找回密码。改自己的不需要任何审批，也不需要管理权限 ——
 *   因此本页用的是 `/api/profile/bind-contact`（作用域只限当前登录者），
 *   而不是员工管理页的 `/api/users/**`（那是管别人的前缀）。
 * - **用户级界面偏好**（ · W4-E）：「记住列表筛选条件」总开关。
 *   放在这里而不是各列表页，是因为它的作用域是**全站**的 ——
 *   用户在一个页面上关掉它，不该只影响那一个页面。
 *
 * <h2>：改绑要验码，但只验「真的改了」的那个渠道</h2>
 * <p>码只对<b>值发生变化</b>的渠道要求。用户点「保存」时若两个框都没动，
 * 前端不该逼他去收一条验证码 —— 后端本来就按「未变化」处理，前端多加一道
 * 只会制造无谓的操作。判据因此与后端逐字对齐：与库中当前值比较。
 */
const router = useRouter()
const userStore = useUserStore()
const { isMobile } = useResponsive()

/** 中国大陆 11 位手机号 / 常规邮箱（与后端 AccountFormats 同规则） */
const PHONE_PATTERN = /^1[3-9]\d{9}$/
const EMAIL_PATTERN = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/

const refreshing = ref(false)
const savingContact = ref(false)
const contactError = ref('')

const contactForm = reactive({ phone: '', email: '', phoneCode: '', emailCode: '' })

/**
 * 当前启用的验证渠道（）。
 *
 * 管理员把「手机验证」关掉后，手机输入框应当置灰并说明原因 ——
 * 否则用户填完手机号点保存才被拒绝，只能白忙一场。
 * 默认两项都开，避免接口未返回时把输入框误锁成不可用。
 */
const enabledChannels = ref<ContactType[]>(['SMS', 'EMAIL'])
/** 不可用渠道 → 原因（服务端下发）。前端只展示，不自己拼文案。 */
const channelReasons = ref<Record<string, string>>({})
const phoneEnabled = computed(() => enabledChannels.value.includes('SMS'))
const emailEnabled = computed(() => enabledChannels.value.includes('EMAIL'))

// ------------------------------------------------------------------
// ：验证码（只对真正改动的渠道显示与发送）
// ------------------------------------------------------------------

const phoneSending = ref(false)
const emailSending = ref(false)
const phoneCountdown = ref(0)
const emailCountdown = ref(0)
const RESEND_SECONDS = 60
let timer: number | undefined

/** 手机号是否相对库中值发生了改动（= 需要验证码） */
const phoneDirty = computed(() => {
  const phone = contactForm.phone.trim()
  return phoneEnabled.value && !!phone && phone !== (userStore.user?.phone ?? '')
})

/** 邮箱是否相对库中值发生了改动（= 需要验证码）；忽略大小写，与后端同口径 */
const emailDirty = computed(() => {
  const email = contactForm.email.trim()
  const current = userStore.user?.email ?? ''
  return emailEnabled.value && !!email && email.toLowerCase() !== current.toLowerCase()
})

function startCountdown(channel: ContactType): void {
  if (channel === 'SMS') {
    phoneCountdown.value = RESEND_SECONDS
  } else {
    emailCountdown.value = RESEND_SECONDS
  }
  if (timer !== undefined) {
    return
  }
  timer = window.setInterval(() => {
    if (phoneCountdown.value > 0) {
      phoneCountdown.value -= 1
    }
    if (emailCountdown.value > 0) {
      emailCountdown.value -= 1
    }
    if (phoneCountdown.value === 0 && emailCountdown.value === 0 && timer !== undefined) {
      window.clearInterval(timer)
      timer = undefined
    }
  }, 1000)
}

async function sendCode(channel: ContactType): Promise<void> {
  contactError.value = ''
  const target = channel === 'SMS' ? contactForm.phone.trim() : contactForm.email.trim()
  if (!target) {
    contactError.value = channel === 'SMS' ? '请先填写手机号' : '请先填写邮箱'
    return
  }
  const sending = channel === 'SMS' ? phoneSending : emailSending
  sending.value = true
  try {
    const result = await authApi.sendBindContactCode({ contactType: channel, target })
    startCountdown(channel)
    if (result.devCode) {
      // 开发环境未接网关，后端把验证码随响应回传；生产环境该字段不存在
      if (channel === 'SMS') {
        contactForm.phoneCode = result.devCode
      } else {
        contactForm.emailCode = result.devCode
      }
      ElMessage.warning(`开发环境验证码：${result.devCode}`)
    } else {
      ElMessage.success(`验证码已发送至 ${result.maskedTarget}`)
    }
  } catch (error) {
    contactError.value = error instanceof ApiError ? error.message : '验证码发送失败，请稍后重试'
  } finally {
    sending.value = false
  }
}

const roleLabel = computed(() => ROLE_LABELS[userStore.role] ?? userStore.role)
const avatarText = computed(() => userStore.displayName.slice(0, 1) || '员')

/**
 * 列表筛选记忆总开关（默认开）。
 *
 * 注意它作用于**下次进入列表页**：在已打开的列表页上切换不会立刻重置当前视图 ——
 * 那会把「关掉开关」变成顺手清空一次用户正在看的查询，比它要解决的问题更粗暴。
 */
const { rememberQuery, setRememberQuery } = useListPreferences()

function handleRememberChange(value: boolean | string | number): void {
  const enabled = value === true
  setRememberQuery(enabled)
  ElMessage.success(enabled ? '已开启：列表将记住筛选条件' : '已关闭：列表每次从默认视图开始')
}

const infoRows = computed(() => [
  { label: '登录账号', value: userStore.user?.username ?? '-' },
  { label: '显示名称', value: userStore.user?.displayName ?? '-' },
  { label: '角色', value: roleLabel.value },
  { label: '认证方式', value: userStore.user?.authType === 'LDAP' ? 'AD 域控账户' : '本地账户' },
  // 兜底文案里不得出现开发代号（如「 配置」）—— 那是给开发看的，
  // 用户看到只会困惑「什么阶段？我该做什么？」。部门真未配置时就说「未分配」。
  { label: '部门', value: userStore.user?.departmentName ?? '未分配' },
  { label: '强制修改密码', value: userStore.forceChangePassword ? '是（请尽快修改）' : '否' }
])

/** 把 store 里的联系方式回填到表单（进入页面、保存成功后各调用一次）；顺带清空验证码 */
function syncContactForm(): void {
  contactForm.phone = userStore.user?.phone ?? ''
  contactForm.email = userStore.user?.email ?? ''
  contactForm.phoneCode = ''
  contactForm.emailCode = ''
}

async function saveContact(): Promise<void> {
  contactError.value = ''
  const phone = contactForm.phone.trim()
  const email = contactForm.email.trim()

  if (!phone && !email) {
    contactError.value = '请至少填写手机号或邮箱中的一项'
    return
  }
  // 置灰的渠道不参与提交：传了也只会被后端按「未变化」处理，
  // 但那时手机号若已被管理员作废，反而会带回一个过期的值
  if (phoneEnabled.value && phone && !PHONE_PATTERN.test(phone)) {
    contactError.value = '手机号格式不正确，请输入 11 位手机号'
    return
  }
  if (emailEnabled.value && email && !EMAIL_PATTERN.test(email)) {
    contactError.value = '邮箱格式不正确，请检查后重试'
    return
  }
  // ：发生改动的渠道必须先拿到验证码。前端拦一道只是省一次失败往返，
  // 真正的权威判定在服务端（它还会比对「发码目标 == 提交目标」）。
  if (phoneDirty.value && !contactForm.phoneCode.trim()) {
    contactError.value = '手机号已修改，请先获取并输入短信验证码'
    return
  }
  if (emailDirty.value && !contactForm.emailCode.trim()) {
    contactError.value = '邮箱已修改，请先获取并输入邮件验证码'
    return
  }

  savingContact.value = true
  try {
    await userStore.bindContact({
      phone: phoneEnabled.value ? phone || null : null,
      email: emailEnabled.value ? email || null : null,
      phoneCode: phoneDirty.value ? contactForm.phoneCode.trim() : null,
      emailCode: emailDirty.value ? contactForm.emailCode.trim() : null
    })
    syncContactForm()
    ElMessage.success('联系方式已更新')
  } catch (error) {
    contactError.value = error instanceof ApiError ? error.message : '保存失败，请稍后重试'
  } finally {
    savingContact.value = false
  }
}

async function refresh(): Promise<void> {
  refreshing.value = true
  try {
    await userStore.fetchCurrentUser()
    syncContactForm()
    ElMessage.success('信息已刷新')
  } finally {
    refreshing.value = false
  }
}

/**
 * 我的附加权限。
 *
 * <p>读失败不阻塞个人中心（退化成不显示这张卡）——
 * 它是附加信息，不该让整页因为一个次要接口失败而报错。
 */
const myPermissions = ref<MyPermissionItem[]>([])

async function loadMyPermissions(): Promise<void> {
  try {
    myPermissions.value = await permissionPolicyApi.mine()
  } catch {
    myPermissions.value = []
  }
}

onMounted(async () => {
  // 附加权限与主数据无依赖，并行发起即可（读失败只在卡片上体现为「不显示」）
  void loadMyPermissions()
  // 进入个人中心时静默刷新一次，确保展示服务端最新数据
  await userStore
    .fetchCurrentUser()
    .then(() => syncContactForm())
    .catch(() => undefined)
  try {
    const meta = await authApi.forgotPasswordMeta()
    enabledChannels.value = meta.channels
    channelReasons.value = meta.channelDisabledReasons ?? {}
  } catch {
    // 拉不到就保守放行两种渠道；真正的写入校验在服务端
  }
})

onUnmounted(() => {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
})
</script>

<template>
  <div class="ts-page">
    <section class="ts-card ts-profile__head">
      <el-avatar :size="isMobile ? 52 : 64" class="ts-profile__avatar">{{ avatarText }}</el-avatar>
      <div class="ts-profile__head-main">
        <h2 class="ts-profile__name">{{ userStore.displayName }}</h2>
        <p class="ts-text-secondary ts-profile__role">{{ roleLabel }}</p>
      </div>
      <div class="ts-profile__actions">
        <el-button :loading="refreshing" @click="refresh">刷新信息</el-button>
        <el-button type="primary" @click="router.push('/profile/password')">修改密码</el-button>
      </div>
    </section>

    <section class="ts-card ts-mt-16">
      <h3 class="ts-profile__section-title">基本信息</h3>

      <!-- 移动端：卡片式信息行（ 表格转卡片） -->
      <div class="ts-mobile-only">
        <div v-for="row in infoRows" :key="row.label" class="ts-profile__row">
          <span class="ts-text-hint">{{ row.label }}</span>
          <span>{{ row.value }}</span>
        </div>
      </div>

      <!-- PC / 平板：描述列表 -->
      <el-descriptions v-if="!isMobile" :column="2" border>
        <el-descriptions-item v-for="row in infoRows" :key="row.label" :label="row.label">
          {{ row.value }}
        </el-descriptions-item>
      </el-descriptions>

      <p class="ts-profile__hint">
        域账户（AD）不支持在本地修改密码；如需调整权限或部门，请联系系统管理员。
      </p>
    </section>

    <section class="ts-card ts-mt-16">
      <h3 class="ts-profile__section-title">联系方式</h3>
      <p class="ts-profile__section-desc">
        手机号与邮箱用于「忘记密码」时自助重置，可随时在这里修改；系统内全局唯一，一个号码只能绑定一个账号。
        管理员关闭某种验证方式后，对应输入框会置灰。<strong>修改任一号码都需要先获取并填写验证码</strong>，
        未改动的号码无需验证。
      </p>

      <el-alert
        v-if="contactError"
        :title="contactError"
        type="error"
        :closable="false"
        show-icon
        class="ts-profile__alert"
      />

      <el-form label-position="top" class="ts-profile__contact" @submit.prevent="saveContact">
        <el-form-item label="手机号">
          <el-input
            v-model="contactForm.phone"
            :disabled="!phoneEnabled"
            maxlength="20"
            placeholder="请输入 11 位手机号"
            clearable
          />
          <p v-if="!phoneEnabled" class="ts-text-hint">
            {{ channelReasons.SMS || '手机验证当前不可用，暂不能使用手机号' }}
          </p>
        </el-form-item>

        <el-form-item v-if="phoneDirty" label="手机验证码">
          <div class="ts-profile__code-row">
            <el-input
              v-model="contactForm.phoneCode"
              maxlength="6"
              placeholder="请输入短信验证码"
              clearable
            />
            <el-button
              class="ts-profile__code-btn"
              :loading="phoneSending"
              :disabled="phoneCountdown > 0"
              @click="sendCode('SMS')"
            >
              {{ phoneCountdown > 0 ? `${phoneCountdown} 秒后重发` : '获取验证码' }}
            </el-button>
          </div>
        </el-form-item>

        <el-form-item label="邮箱">
          <el-input
            v-model="contactForm.email"
            :disabled="!emailEnabled"
            maxlength="128"
            placeholder="请输入常用邮箱"
            clearable
          />
          <p v-if="!emailEnabled" class="ts-text-hint">
            {{ channelReasons.EMAIL || '邮箱验证当前不可用，暂不能使用邮箱' }}
          </p>
        </el-form-item>

        <el-form-item v-if="emailDirty" label="邮箱验证码">
          <div class="ts-profile__code-row">
            <el-input
              v-model="contactForm.emailCode"
              maxlength="6"
              placeholder="请输入邮件验证码"
              clearable
            />
            <el-button
              class="ts-profile__code-btn"
              :loading="emailSending"
              :disabled="emailCountdown > 0"
              @click="sendCode('EMAIL')"
            >
              {{ emailCountdown > 0 ? `${emailCountdown} 秒后重发` : '获取验证码' }}
            </el-button>
          </div>
        </el-form-item>

        <el-button type="primary" :loading="savingContact" native-type="submit">保存联系方式</el-button>
      </el-form>
    </section>

    <!--
      我的附加权限：审批通过后自动开通的那些权限。
      只展示**用户级授权**（角色自带的不在此列）—— 申请人最需要回答的问题是
      「我申请的那项到底开了没有」，而不是「我总共有哪些权限」（那在菜单上就能看出来）。
      只读、无操作：撤销权限是管理动作，不该出现在个人中心。
    -->
    <section v-if="myPermissions.length > 0" class="ts-card ts-mt-16">
      <h3 class="ts-profile__section-title">我的附加权限</h3>
      <p class="ts-text-secondary">
        这些是你在「系统权限申请」里申请、审批通过后自动开通的权限；角色自带的权限不在此列。
        权限开通后立即生效，无需重新登录。
      </p>
      <el-table :data="myPermissions" size="small" border>
        <el-table-column prop="permCode" label="权限码" min-width="200" show-overflow-tooltip />
        <el-table-column label="开通方式" width="120" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.source === 'APPLY' ? '审批开通' : '手工开通' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="grantedAt" label="开通时间" min-width="170" />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.active ? 'success' : 'info'" size="small" effect="plain">
              {{ row.active ? '有效' : '已撤销' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section class="ts-card ts-mt-16">
      <h3 class="ts-profile__section-title">界面偏好</h3>

      <div class="ts-profile__pref">
        <div class="ts-profile__pref-main">
          <span class="ts-profile__pref-label">记住列表筛选条件</span>
          <span class="ts-profile__pref-desc">
            开启后，故障管理、导出记录、使用记录等列表页会自动沿用你上次的筛选与查询条件；关闭后每次进入都从默认视图开始。
            已保存的筛选不会被删除，重新开启即可恢复。该设置对下次进入页面生效，不会打断当前页面正在进行的查询。
          </span>
        </div>
        <el-switch v-model="rememberQuery" class="ts-profile__pref-switch" @change="handleRememberChange" />
      </div>
    </section>
  </div>
</template>

<style scoped>
.ts-profile__head {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}

.ts-profile__avatar {
  background: var(--ts-primary);
  font-size: 20px;
  flex: 0 0 auto;
}

.ts-profile__head-main {
  flex: 1 1 auto;
  min-width: 0;
}

.ts-profile__name {
  margin: 0 0 4px;
  font-size: 18px;
  font-weight: 500;
}

.ts-profile__role {
  margin: 0;
  font-size: 13px;
}

.ts-profile__actions {
  display: flex;
  gap: 8px;
  flex: 0 0 auto;
}

.ts-profile__section-title {
  margin: 0 0 16px;
  font-size: 15px;
  font-weight: 500;
}

.ts-profile__section-desc {
  margin: -8px 0 16px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ts-text-hint);
}

.ts-profile__alert {
  margin-bottom: 14px;
}

.ts-profile__contact {
  max-width: 420px;
}

.ts-profile__code-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.ts-profile__code-row .el-input {
  flex: 1;
}

.ts-profile__code-btn {
  flex: 0 0 auto;
  min-width: 112px;
}

.ts-profile__row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 0;
  font-size: 14px;
  border-bottom: 1px solid var(--ts-border);
}

.ts-profile__row:last-child {
  border-bottom: none;
}

.ts-profile__row span:last-child {
  text-align: right;
  word-break: break-all;
}

.ts-profile__hint {
  margin: 16px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ts-text-hint);
}

.ts-profile__pref {
  display: flex;
  align-items: flex-start;
  gap: 16px;
}

.ts-profile__pref-main {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.ts-profile__pref-label {
  font-size: 14px;
  font-weight: 500;
}

.ts-profile__pref-desc {
  font-size: 12px;
  line-height: 1.7;
  color: var(--ts-text-hint);
}

.ts-profile__pref-switch {
  flex: 0 0 auto;
  margin-top: 2px;
}

@media (max-width: 767px) {
  .ts-profile__head {
    gap: 12px;
  }

  .ts-profile__actions {
    width: 100%;
  }

  .ts-profile__actions .el-button {
    flex: 1 1 0;
    margin-left: 0;
  }

  .ts-profile__code-btn {
    min-width: 96px;
  }
}
</style>
