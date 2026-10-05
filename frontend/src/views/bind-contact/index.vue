<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import authApi from '@/api/auth'
import { ApiError } from '@/api/request'
import { useSiteStore } from '@/store/site'
import { useUserStore } from '@/store/user'
import type { ContactType } from '@/types/api'
import SiteLogo from '@/components/SiteLogo.vue'

/**
 * 首次登录绑定联系方式（上线前需求 三 / 八）。
 *
 * <h2>为什么做成独立整屏页而不是弹窗</h2>
 * 说明是「不绑定就不能进系统」。弹窗可以被关掉（无论是用户点 X、还是按 Esc），
 * 而一旦关掉，用户就已经身在系统里了 —— 那时再补一道「拦截每个页面」的逻辑，
 * 等于把一条规则写在两个地方。做成独立路由后，「不能进系统」由**路由守卫**保证：
 * `requireContactBinding` 为真时，除本页外的任何目标地址都会被重定向回来。
 *
 * <h2>阈值与开关都来自服务端</h2>
 * - 「要不要弹本页」由后端下发的 `requireContactBinding` 决定（本页不自己推导）；
 * - 「能绑哪几种」由 `GET /api/auth/forgot-password/meta` 的 `channels` 决定 ——
 *   管理员把手机验证关掉后，手机输入框应当置灰并说明原因，
 *   否则用户填了半天手机号却被告知「该方式已关闭」，只能白忙一场。
 *
 * <h2>为什么两个输入框「至少填一个」</h2>
 * 绑定联系方式的唯一目的是「将来能通过它找回密码」。只绑一个即可达成目的，
 * 强制两个都填会把一件 30 秒能完成的事变成需要先去翻邮箱的麻烦事；
 * 而两个都不填则等于没绑 —— 因此判据是「至少一个非空」，由后端二次确认。
 *
 * <h2>：为什么填完号码还要先拿验证码</h2>
 * 改造前一次提交就能把联系方式改成任意号码 —— 只要会话在手，就能把<b>别人的</b>
 * 手机号绑到自己名下（进而在找回密码时接收验证码）。现在「能绑上去」的前提是
 * 「能读到发到该号码上的验证码」，而验证码由后端发到<b>新号码</b>，
 * 并额外校验「提交目标 == 发码目标」。两个渠道各验各的码，互不顶替。
 */
const router = useRouter()
const siteStore = useSiteStore()
const userStore = useUserStore()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const errorMessage = ref('')
const channels = ref<ContactType[]>([])
const metaLoaded = ref(false)

const form = reactive({
  phone: '',
  email: '',
  phoneCode: '',
  emailCode: ''
})

const phoneEnabled = computed(() => channels.value.includes('SMS'))
const emailEnabled = computed(() => channels.value.includes('EMAIL'))

// ------------------------------------------------------------------
// 验证码：发送按钮 + 60 秒重发倒计时
// ------------------------------------------------------------------

const phoneSending = ref(false)
const emailSending = ref(false)
const phoneCountdown = ref(0)
const emailCountdown = ref(0)
const RESEND_SECONDS = 60
let timer: number | undefined

/** 两个渠道共用一个 tick（各自独立计数），避免开两个定时器相互干扰 */
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
  errorMessage.value = ''
  const target = channel === 'SMS' ? form.phone.trim() : form.email.trim()
  if (!target) {
    errorMessage.value = channel === 'SMS' ? '请先填写手机号' : '请先填写邮箱'
    return
  }
  const sending = channel === 'SMS' ? phoneSending : emailSending
  sending.value = true
  try {
    const result = await authApi.sendBindContactCode({ contactType: channel, target })
    startCountdown(channel)
    if (result.devCode) {
      // 开发环境后端未接短信/邮件网关，会把验证码随响应回传（生产环境该字段不存在）。
      // 这里直接回填，省去每次联调都去翻后端日志。
      if (channel === 'SMS') {
        form.phoneCode = result.devCode
      } else {
        form.emailCode = result.devCode
      }
      ElMessage.warning(`开发环境验证码：${result.devCode}`)
    } else {
      ElMessage.success(`验证码已发送至 ${result.maskedTarget}`)
    }
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '验证码发送失败，请稍后重试'
  } finally {
    sending.value = false
  }
}

const rules: FormRules = {
  phone: [
    {
      validator: (_rule: unknown, value: string, callback: (error?: Error) => void): void => {
        if (!value) {
          callback()
          return
        }
        if (!/^1[3-9]\d{9}$/.test(value)) {
          callback(new Error('请输入 11 位手机号'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ],
  email: [
    {
      validator: (_rule: unknown, value: string, callback: (error?: Error) => void): void => {
        if (!value) {
          callback()
          return
        }
        if (!/^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/.test(value)) {
          callback(new Error('邮箱格式不正确'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ]
}

async function handleSubmit(): Promise<void> {
  errorMessage.value = ''
  const phone = form.phone.trim()
  const email = form.email.trim()
  if (!phone && !email) {
    errorMessage.value = '请至少填写手机号或邮箱中的一项'
    return
  }
  const form$ = formRef.value
  if (!form$) {
    return
  }
  const valid = await form$.validate().catch(() => false)
  if (!valid) {
    return
  }
  // 填了就必须要码：漏掉这一步提交上去也会被后端拒绝（后端是唯一权威），
  // 这里提前拦一道只是为了让用户不必等一次失败的往返。
  if (phone && !form.phoneCode.trim()) {
    errorMessage.value = '请输入手机验证码'
    return
  }
  if (email && !form.emailCode.trim()) {
    errorMessage.value = '请输入邮箱验证码'
    return
  }

  submitting.value = true
  try {
    await userStore.bindContact({
      phone: phone || null,
      email: email || null,
      phoneCode: phone ? form.phoneCode.trim() : null,
      emailCode: email ? form.emailCode.trim() : null
    })
    ElMessage.success('绑定成功')
    await router.replace('/dashboard')
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '绑定失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  // 刷新页面直接落到本页时 store 是空的：先用 Cookie 恢复会话，
  // 否则「是否已完成绑定」无从判断，守卫的判定也会失去依据。
  if (!userStore.isLogin) {
    try {
      await userStore.fetchCurrentUser()
    } catch {
      await router.replace('/login')
      return
    }
  }
  // 已经满足条件的人（例如手工敲 URL 进来）直接放行，
  // 不让他停在一张无事可做的页面上。
  if (!userStore.requireContactBinding) {
    await router.replace('/dashboard')
    return
  }
  try {
    const meta = await authApi.forgotPasswordMeta()
    channels.value = meta.channels
    metaLoaded.value = true
    // 两个验证开关都被关时，用户**无法**完成绑定（没有渠道能验证号码归属）。
    // 后端在这种情况下不会下发 requireContactBinding=true，但用户可能手工敲本页 URL ——
    // 这里兜底放行，避免他被永久堵在一个点不过去的页面上。
    if (!meta.enabled) {
      await router.replace('/dashboard')
    }
  } catch {
    // 元信息拿不到时保守放行两种渠道，真正的唯一性与格式校验都在服务端
    channels.value = ['SMS', 'EMAIL']
    metaLoaded.value = true
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
  <div class="ts-bc">
    <div class="ts-bc__panel">
      <header class="ts-bc__head">
        <span class="ts-bc__mark"><SiteLogo /></span>
        <h1 class="ts-bc__title">绑定手机号或邮箱</h1>
        <p class="ts-bc__subtitle">{{ siteStore.siteName }}</p>
      </header>

      <el-alert
        title="为了保障账号安全，请绑定手机号或邮箱（至少一个）"
        type="warning"
        :closable="false"
        show-icon
        class="ts-bc__alert"
      >
        <p class="ts-bc__alert-body">
          绑定后，忘记密码时可通过手机短信或邮件自助重置，无需再联系管理员。
          两项都绑定更安全，但至少需要填写一项才能进入系统。
          为确认号码属于本人，<strong>每一项都需要先获取并填写验证码</strong>。
        </p>
      </el-alert>

      <el-alert
        v-if="errorMessage"
        :title="errorMessage"
        type="error"
        :closable="false"
        show-icon
        class="ts-bc__alert"
      />

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @submit.prevent="handleSubmit"
      >
        <el-form-item label="手机号" prop="phone">
          <el-input
            v-model="form.phone"
            :disabled="!phoneEnabled"
            maxlength="11"
            placeholder="请输入 11 位手机号"
            clearable
          >
            <template #prefix><el-icon><Iphone /></el-icon></template>
          </el-input>
          <p v-if="metaLoaded && !phoneEnabled" class="ts-bc__field-hint">
            管理员已关闭手机验证，请改用邮箱绑定。
          </p>
        </el-form-item>

        <el-form-item v-if="phoneEnabled && form.phone.trim()" label="手机验证码">
          <div class="ts-bc__code-row">
            <el-input
              v-model="form.phoneCode"
              maxlength="6"
              placeholder="请输入短信验证码"
              clearable
            />
            <el-button
              class="ts-bc__code-btn"
              :loading="phoneSending"
              :disabled="phoneCountdown > 0"
              @click="sendCode('SMS')"
            >
              {{ phoneCountdown > 0 ? `${phoneCountdown} 秒后重发` : '获取验证码' }}
            </el-button>
          </div>
        </el-form-item>

        <el-form-item label="邮箱" prop="email">
          <el-input
            v-model="form.email"
            :disabled="!emailEnabled"
            placeholder="请输入常用邮箱"
            clearable
          >
            <template #prefix><el-icon><Message /></el-icon></template>
          </el-input>
          <p v-if="metaLoaded && !emailEnabled" class="ts-bc__field-hint">
            管理员已关闭邮箱验证，请改用手机号绑定。
          </p>
        </el-form-item>

        <el-form-item v-if="emailEnabled && form.email.trim()" label="邮箱验证码">
          <div class="ts-bc__code-row">
            <el-input
              v-model="form.emailCode"
              maxlength="6"
              placeholder="请输入邮件验证码"
              clearable
            />
            <el-button
              class="ts-bc__code-btn"
              :loading="emailSending"
              :disabled="emailCountdown > 0"
              @click="sendCode('EMAIL')"
            >
              {{ emailCountdown > 0 ? `${emailCountdown} 秒后重发` : '获取验证码' }}
            </el-button>
          </div>
        </el-form-item>

        <el-button
          type="primary"
          size="large"
          class="ts-bc__submit"
          :loading="submitting"
          native-type="submit"
        >
          完成并进入系统
        </el-button>
      </el-form>

      <p class="ts-bc__hint">
        手机号与邮箱在系统内全局唯一，一个号码只能绑定一个账号。
      </p>
      <div class="ts-bc__links">
        <el-button link type="primary" @click="userStore.logout().then(() => router.replace('/login'))">
          退出登录
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ts-bc {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  min-height: 100dvh;
  padding: 24px 16px;
  background: var(--ts-bg);
}

.ts-bc__panel {
  width: 100%;
  max-width: 460px;
  padding: 32px 28px;
  background: var(--ts-surface);
  border: 1px solid var(--ts-border);
  border-radius: 14px;
}

.ts-bc__head {
  text-align: center;
  margin-bottom: 20px;
}

.ts-bc__mark {
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

.ts-bc__title {
  margin: 14px 0 6px;
  font-size: 19px;
  font-weight: 500;
  color: var(--ts-text);
}

.ts-bc__subtitle {
  margin: 0;
  font-size: 13px;
  color: var(--ts-text-secondary);
}

.ts-bc__alert {
  margin-bottom: 16px;
}

.ts-bc__alert-body {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.7;
}

.ts-bc__field-hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ts-text-hint);
}

.ts-bc__code-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.ts-bc__code-row .el-input {
  flex: 1;
}

.ts-bc__code-btn {
  flex: 0 0 auto;
  min-width: 112px;
}

.ts-bc__submit {
  width: 100%;
  margin-top: 4px;
}

.ts-bc__hint {
  margin: 16px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ts-text-hint);
  text-align: center;
}

.ts-bc__links {
  margin-top: 10px;
  text-align: center;
}

@media (max-width: 767px) {
  .ts-bc {
    align-items: flex-start;
    padding: 28px 16px;
  }

  .ts-bc__panel {
    padding: 22px 18px;
    border-radius: 12px;
  }

  .ts-bc__code-btn {
    min-width: 96px;
  }
}
</style>
