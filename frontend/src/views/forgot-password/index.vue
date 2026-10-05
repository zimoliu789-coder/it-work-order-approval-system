<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import authApi from '@/api/auth'
import { ApiError } from '@/api/request'
import { useSiteStore } from '@/store/site'
import type { ContactType, ForgotPasswordChannels, ForgotPasswordMeta } from '@/types/api'
import type { PasswordPolicy } from '@/types/password'
import SiteLogo from '@/components/SiteLogo.vue'
import PasswordPolicyTips from '@/components/PasswordPolicyTips.vue'

/**
 * 找回密码（上线前需求 二 / 五 / 六）—— 三步向导。
 *
 * <h2>为什么做成三步而不是一页表单</h2>
 * 「输入账号 → 选渠道 → 输验证码与新密码」这三步各自依赖上一步的**服务端结论**：
 * 渠道列表取决于该账号绑了哪个联系方式、系统又开了哪个开关；而「验证码位数」
 * 是管理员可配的。把它们压在一页上，前端就必须自己推导这些值 ——
 * 一旦推导与后端不一致，用户会看到「页面说 6 位、后端要 4 位」这类无解的矛盾。
 * 三步走让每一步都只渲染服务端刚给出的答案。
 *
 * <h2>安全相关的展示口径</h2>
 * - 渠道目标一律显示**打码值**（138****8888），完整号码只存在于服务端；
 * - 「该账号未绑定任何联系方式」「未找到绑定该手机号/邮箱的账号」这些提示
 *   全部由后端给出，前端只负责原样展示 —— 措辞本身就是防枚举设计的一部分
 *   （见后端 ForgotPasswordService 的说明），前端绝不能自己改写。
 */
const router = useRouter()
const siteStore = useSiteStore()

/** 0 = 输入账号；1 = 选择接收渠道；2 = 验证码 + 新密码 */
const step = ref(0)

const meta = ref<ForgotPasswordMeta | null>(null)
const channels = ref<ForgotPasswordChannels | null>(null)
const policy = ref<PasswordPolicy | null>(null)

const accountFormRef = ref<FormInstance>()
const resetFormRef = ref<FormInstance>()
const account = ref('')
const errorMessage = ref('')
const checking = ref(false)

// 用 undefined 而不是 null：el-radio-group 的 v-model 类型不接受 null，
// 而「未选择」在语义上本来就是 undefined。
const contactType = ref<ContactType | undefined>(undefined)
const sending = ref(false)
const devCode = ref('')

const code = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const resetting = ref(false)
/** 重新发送倒计时（秒） */
const countdown = ref(0)
let timer: number | undefined

const codeLength = computed(() => channels.value?.codeLength ?? meta.value?.codeLength ?? 6)
const expireMinutes = computed(() => channels.value?.codeExpireMinutes ?? 5)
const minLength = computed(() => policy.value?.minLength ?? 8)
const maxLength = computed(() => policy.value?.maxLength ?? 64)
const minCharTypes = computed(() => policy.value?.minCharTypes ?? 2)

/** 功能是否可用（两个验证开关都被关掉时为 false） */
const enabled = computed(() => meta.value?.enabled !== false)

/**
 * 交给 el-form 的 model。
 *
 * 用 computed 而不是 `:model="{ account }"` 内联对象：内联写法每次渲染都会
 * 新建一个对象，el-form-item 在异步校验回调里读到的是**当时的旧对象**，
 * 会造成「刚输入的账号被校验成空」这类只在快速输入时出现的问题。
 */
const accountModel = computed(() => ({ account: account.value }))
const resetModel = computed(() => ({
  code: code.value,
  newPassword: newPassword.value,
  confirmPassword: confirmPassword.value
}))

const accountRules: FormRules = {
  account: [
    { required: true, message: '请输入登录名、姓名、手机号或邮箱', trigger: 'blur' },
    { max: 128, message: '长度不能超过 128 个字符', trigger: 'blur' }
  ]
}

/** 渠道选项：由后端过滤后的 channels 与打码目标拼出，前端不自行判断可用性 */
const channelOptions = computed(() => {
  const list = channels.value?.channels ?? []
  return list.map((type) => ({
    value: type,
    label: type === 'SMS' ? '手机短信' : '邮箱',
    target: type === 'SMS' ? channels.value?.maskedPhone : channels.value?.maskedEmail
  }))
})

function countCharTypes(value: string): number {
  let types = 0
  if (/[a-z]/.test(value)) types += 1
  if (/[A-Z]/.test(value)) types += 1
  if (/[0-9]/.test(value)) types += 1
  if (/[^A-Za-z0-9]/.test(value)) types += 1
  return types
}

/**
 * 与后端 PasswordPolicyService 同强度校验。
 *
 * 阈值取自后端下发的策略（密码策略接口对本页免鉴权），而不是写死 8 / 2：
 * 管理员把最小长度调到 12 后，若这里仍按 8 位放行，用户会在提交时才被拒绝 ——
 * 「先让他过、再打回来」是最容易让人怀疑表单坏了的体验。
 * 即便如此，**服务端仍是唯一事实源**：这里的校验只是提前反馈。
 */
const validateNewPassword = (_rule: unknown, value: string, callback: (error?: Error) => void): void => {
  if (!value) {
    callback(new Error('请输入新密码'))
    return
  }
  if (value.length < minLength.value) {
    callback(new Error(`密码长度不能少于 ${minLength.value} 位`))
    return
  }
  if (value.length > maxLength.value) {
    callback(new Error(`密码长度不能超过 ${maxLength.value} 位`))
    return
  }
  if (countCharTypes(value) < minCharTypes.value) {
    callback(new Error(`密码需包含大写字母、小写字母、数字、特殊字符中的至少 ${minCharTypes.value} 类`))
    return
  }
  callback()
}

const validateConfirm = (_rule: unknown, value: string, callback: (error?: Error) => void): void => {
  if (!value) {
    callback(new Error('请再次输入新密码'))
    return
  }
  if (value !== newPassword.value) {
    callback(new Error('两次输入的新密码不一致'))
    return
  }
  callback()
}

const resetRules = computed<FormRules>(() => ({
  code: [
    { required: true, message: '请输入验证码', trigger: 'blur' },
    {
      pattern: new RegExp(`^\\d{${codeLength.value}}$`),
      message: `请输入 ${codeLength.value} 位数字验证码`,
      trigger: 'blur'
    }
  ],
  newPassword: [{ validator: validateNewPassword, trigger: 'blur' }],
  confirmPassword: [{ validator: validateConfirm, trigger: 'blur' }]
}))

function startCountdown(seconds = 60): void {
  stopCountdown()
  countdown.value = seconds
  timer = window.setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) {
      stopCountdown()
    }
  }, 1000)
}

function stopCountdown(): void {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
  countdown.value = 0
}

/** 第一步：提交账号 → 拉取可用渠道 */
async function handleAccountNext(): Promise<void> {
  errorMessage.value = ''
  if (!accountFormRef.value) {
    return
  }
  const valid = await accountFormRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  checking.value = true
  try {
    channels.value = await authApi.forgotPasswordChannels(account.value.trim())
    contactType.value = channelOptions.value.length === 1 ? channelOptions.value[0].value : undefined
    step.value = 1
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '查询失败，请稍后重试'
  } finally {
    checking.value = false
  }
}

/** 第二步：按所选渠道下发验证码 → 进入第三步 */
async function handleSendCode(): Promise<void> {
  errorMessage.value = ''
  if (!contactType.value) {
    errorMessage.value = '请选择接收验证码的方式'
    return
  }
  sending.value = true
  try {
    const result = await authApi.forgotPasswordSendCode({
      account: account.value.trim(),
      contactType: contactType.value
    })
    devCode.value = result.devCode ?? ''
    startCountdown()
    step.value = 2
    ElMessage.success(`验证码已发送至 ${result.maskedTarget}`)
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '发送失败，请稍后重试'
  } finally {
    sending.value = false
  }
}

/** 第三步：校验验证码并重置密码 */
async function handleReset(): Promise<void> {
  errorMessage.value = ''
  const form = resetFormRef.value
  if (!form) {
    return
  }
  const valid = await form.validate().catch(() => false)
  if (!valid) {
    return
  }
  resetting.value = true
  try {
    await authApi.forgotPasswordReset({
      account: account.value.trim(),
      code: code.value.trim(),
      newPassword: newPassword.value
    })
    stopCountdown()
    ElMessage.success('密码已重置，请登录')
    // 带上 reset=1，让登录页再显示一条常驻提示 ——
    // 路由跳转后 toast 可能刚好消散，用户会不确定「到底成没成功」。
    await router.replace({ path: '/login', query: { reset: '1' } })
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '重置失败，请稍后重试'
    // 验证码错误会让后端累加失败计数（错 5 次作废），此时清空输入让用户重新确认
    code.value = ''
  } finally {
    resetting.value = false
  }
}

function backToAccount(): void {
  errorMessage.value = ''
  step.value = 0
  channels.value = null
  contactType.value = undefined
  code.value = ''
  newPassword.value = ''
  confirmPassword.value = ''
  devCode.value = ''
  stopCountdown()
}

onMounted(async () => {
  try {
    meta.value = await authApi.forgotPasswordMeta()
  } catch {
    // 元信息拉不到时不阻断：仍允许用户试，真正的闸门在服务端
    meta.value = null
  }
  try {
    policy.value = await authApi.passwordPolicy()
  } catch {
    policy.value = null
  }
})

onBeforeUnmount(stopCountdown)
</script>

<template>
  <div class="ts-fp">
    <div class="ts-fp__panel">
      <header class="ts-fp__head">
        <span class="ts-fp__mark"><SiteLogo /></span>
        <h1 class="ts-fp__title">找回密码</h1>
        <p class="ts-fp__subtitle">{{ siteStore.siteName }}</p>
      </header>

      <el-alert
        v-if="!enabled"
        title="找回密码暂不可用"
        type="warning"
        :closable="false"
        show-icon
        class="ts-fp__alert"
      >
        <p class="ts-fp__alert-body">
          管理员已关闭全部验证方式（手机与邮箱），系统无法下发验证码，请联系管理员在后台重置密码。
        </p>
      </el-alert>

      <template v-else>
        <el-steps :active="step" align-center finish-status="success" class="ts-fp__steps">
          <el-step title="验证账号" />
          <el-step title="选择方式" />
          <el-step title="重置密码" />
        </el-steps>

        <el-alert
          v-if="errorMessage"
          :title="errorMessage"
          type="error"
          :closable="false"
          show-icon
          class="ts-fp__alert"
        />

        <!-- 第一步：输入账号 -->
        <el-form
          v-if="step === 0"
          ref="accountFormRef"
          :model="accountModel"
          :rules="accountRules"
          label-position="top"
          size="large"
          @submit.prevent="handleAccountNext"
        >
          <el-form-item label="账号" prop="account">
            <el-input
              v-model="account"
              placeholder="请输入登录名（数字账号）、姓名、手机号或邮箱"
              clearable
              @keyup.enter="handleAccountNext"
            >
              <template #prefix><el-icon><User /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-button
            type="primary"
            size="large"
            class="ts-fp__submit"
            :loading="checking"
            native-type="submit"
          >
            下一步
          </el-button>
        </el-form>

        <!-- 第二步：选择接收渠道 -->
        <div v-else-if="step === 1" class="ts-fp__body">
          <p class="ts-fp__who">
            正在为
            <strong>{{ channels?.displayName || account }}</strong>
            重置密码
          </p>
          <el-radio-group v-model="contactType" class="ts-fp__channels">
            <label v-for="opt in channelOptions" :key="opt.value" class="ts-fp__channel">
              <el-radio :value="opt.value">
                <span class="ts-fp__channel-label">{{ opt.label }}</span>
                <span class="ts-fp__channel-target">{{ opt.target }}</span>
              </el-radio>
            </label>
          </el-radio-group>

          <div class="ts-fp__actions">
            <el-button @click="backToAccount">上一步</el-button>
            <el-button type="primary" :loading="sending" @click="handleSendCode">发送验证码</el-button>
          </div>
        </div>

        <!-- 第三步：验证码 + 新密码 -->
        <el-form
          v-else
          ref="resetFormRef"
          :model="resetModel"
          :rules="resetRules"
          label-position="top"
          size="large"
          @submit.prevent="handleReset"
        >
          <el-alert
            v-if="devCode"
            type="info"
            :closable="false"
            show-icon
            class="ts-fp__alert"
            :title="`开发环境验证码：${devCode}`"
          >
            <p class="ts-fp__alert-body">生产环境不会显示验证码，会通过短信或邮件发送。</p>
          </el-alert>

          <el-form-item :label="`验证码（${codeLength} 位，${expireMinutes} 分钟内有效）`" prop="code">
            <el-input
              v-model="code"
              :maxlength="codeLength"
              placeholder="请输入验证码"
              clearable
            >
              <template #prefix><el-icon><Message /></el-icon></template>
              <template #append>
                <el-button :disabled="countdown > 0" :loading="sending" @click="handleSendCode">
                  {{ countdown > 0 ? `${countdown} 秒后重发` : '重新发送' }}
                </el-button>
              </template>
            </el-input>
          </el-form-item>

          <el-form-item label="新密码" prop="newPassword">
            <el-input
              v-model="newPassword"
              type="password"
              placeholder="请输入新密码"
              show-password
              autocomplete="new-password"
            >
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </el-form-item>

          <el-form-item label="确认新密码" prop="confirmPassword">
            <el-input
              v-model="confirmPassword"
              type="password"
              placeholder="请再次输入新密码"
              show-password
              autocomplete="new-password"
              @keyup.enter="handleReset"
            >
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </el-form-item>

          <el-button
            type="primary"
            size="large"
            class="ts-fp__submit"
            :loading="resetting"
            native-type="submit"
          >
            重置密码
          </el-button>
        </el-form>

        <PasswordPolicyTips v-if="step === 2 && policy" :rules="policy.rules" class="ts-fp__policy" />
      </template>

      <div class="ts-fp__links">
        <el-button link type="primary" @click="router.replace('/login')">返回登录</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ts-fp {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  min-height: 100dvh;
  padding: 24px 16px;
  background: var(--ts-bg);
}

.ts-fp__panel {
  width: 100%;
  max-width: 460px;
  padding: 32px 28px;
  background: var(--ts-surface);
  border: 1px solid var(--ts-border);
  border-radius: 14px;
}

.ts-fp__head {
  text-align: center;
  margin-bottom: 20px;
}

.ts-fp__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  border-radius: 12px;
  background: var(--ts-primary);
  color: #fff;
  font-size: 15px;
  font-weight: 500;
}

.ts-fp__title {
  margin: 14px 0 6px;
  font-size: 19px;
  font-weight: 500;
  color: var(--ts-text);
}

.ts-fp__subtitle {
  margin: 0;
  font-size: 13px;
  color: var(--ts-text-secondary);
}

.ts-fp__steps {
  margin-bottom: 22px;
}

.ts-fp__alert {
  margin-bottom: 16px;
}

.ts-fp__alert-body {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.7;
}

.ts-fp__body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.ts-fp__who {
  margin: 0;
  font-size: 14px;
  color: var(--ts-text-secondary);
}

.ts-fp__channels {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
}

.ts-fp__channel {
  display: block;
  padding: 10px 12px;
  border: 1px solid var(--ts-border);
  border-radius: 10px;
}

.ts-fp__channel-label {
  margin-right: 8px;
}

.ts-fp__channel-target {
  color: var(--ts-text-hint);
  font-size: 13px;
}

.ts-fp__actions {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
}

.ts-fp__submit {
  width: 100%;
  margin-top: 4px;
}

.ts-fp__policy {
  margin-top: 16px;
}

.ts-fp__links {
  margin-top: 16px;
  text-align: center;
}

@media (max-width: 767px) {
  .ts-fp {
    align-items: flex-start;
    padding: 28px 16px;
  }

  .ts-fp__panel {
    padding: 22px 18px;
    border-radius: 12px;
  }
}
</style>
