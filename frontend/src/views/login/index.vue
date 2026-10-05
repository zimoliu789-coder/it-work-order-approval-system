<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { useUserStore } from '@/store/user'
import { useSiteStore } from '@/store/site'
import authApi from '@/api/auth'
import { ApiError } from '@/api/request'
import type { LoginRequest } from '@/types/api'
import SiteLogo from '@/components/SiteLogo.vue'

/**
 * 登录页（ 登录、 登录安全、 移动端适配）
 *
 * 上线前 起，账号字段同时接受两种形态：
 * - **5 位以上纯数字** → 按登录名（username）精确匹配；
 * - **中文姓名** → 按姓名（real_name）匹配，重名时报「该姓名对应多个账号」。
 * 后端 `UserServiceImpl#findByLoginAccount` 先试登录名再试姓名，
 * 因此这里不需要（也不应该）在前端做形态判断 —— 用户填什么都原样提交。
 *
 * 测试要点：
 * - 手机端（375px）表单不超出屏幕、输入框获得焦点时不被键盘遮挡
 * - 错误提示紧贴对应字段并给出剩余尝试次数
 * - 第 6 次连续提交返回 429 限流提示
 * - 「无法登录？」在手机验证与邮箱验证都被关闭时**不显示**（）
 */
const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
// 登录页也要显示系统名称与 logo（）：此时还没有会话，数据来自免认证接口，
// 由 main.ts 启动时统一拉取并存入 store；未返回前显示与后端一致的兜底值。
const siteStore = useSiteStore()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const errorMessage = ref('')

/**
 * 找回密码入口是否可见。
 *
 * 初值取 `true` 会让「两个开关都关」的部署在接口返回前短暂闪出一个死链入口，
 * 因此默认**不可见**，拿到 meta 且 enabled 为真时才显示 —— 宁可晚一点出现，也不要闪错。
 */
const forgotEntryVisible = ref(false)

/** 从找回密码页跳回时的成功提示（见 forgot-password 页的 query 说明） */
const resetNotice = computed(() => route.query.reset === '1')

const form = reactive<LoginRequest>({
  username: '',
  password: ''
})

const rules: FormRules<LoginRequest> = {
  username: [
    { required: true, message: '请输入登录名或姓名', trigger: 'blur' },
    { max: 64, message: '长度不能超过 64 个字符', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { max: 64, message: '密码长度不能超过 64 个字符', trigger: 'blur' }
  ]
}

const mockEnabled = import.meta.env.VITE_USE_MOCK === 'true'

const redirectTarget = computed(() => {
  const redirect = route.query.redirect
  if (typeof redirect !== 'string') {
    return '/dashboard'
  }
  // 防开放重定向：只接受站内已注册路由。
  // 拒绝 //evil.com（协议相对）与含反斜杠的变体，再用 router.resolve 确认路由真实存在。
  if (!redirect.startsWith('/') || redirect.startsWith('//') || redirect.includes('\\')) {
    return '/dashboard'
  }
  return router.resolve(redirect).matched.length > 0 ? redirect : '/dashboard'
})

async function handleSubmit(): Promise<void> {
  errorMessage.value = ''
  if (!formRef.value) {
    return
  }
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }

  submitting.value = true
  try {
    const info = await userStore.login({ username: form.username.trim(), password: form.password })
    ElMessage.success(`欢迎回来，${info.displayName}`)
    // 三道闸门按序：强制改密 → 绑定联系方式 → 原目标地址。
    // 与 router/guard.ts 的判定顺序保持一致，避免出现「登录后跳 A、刷新后跳 B」。
    const next = info.forceChangePassword
      ? '/change-password'
      : info.requireContactBinding
        ? '/bind-contact'
        : redirectTarget.value
    await router.replace(next)
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '登录失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}

function goForgotPassword(): void {
  void router.push('/forgot-password')
}

onMounted(async () => {
  try {
    const meta = await authApi.forgotPasswordMeta()
    forgotEntryVisible.value = meta.enabled
  } catch {
    // 元信息拿不到时保守**隐藏**入口：让用户点进一个发不出验证码的页面，
    // 比暂时看不到入口更让人困惑（他会以为是自己账号有问题）。
    forgotEntryVisible.value = false
  }
})
</script>

<template>
  <div class="ts-login">
    <div class="ts-login__panel">
      <header class="ts-login__head">
        <span class="ts-login__mark"><SiteLogo /></span>
        <h1 class="ts-login__title">{{ siteStore.siteName }}</h1>
        <p class="ts-login__subtitle">请使用数字登录名或姓名登录，首次登录需修改初始密码</p>
      </header>

      <el-alert
        v-if="resetNotice"
        title="密码已重置，请登录"
        type="success"
        :closable="false"
        show-icon
        class="ts-login__alert"
      />

      <el-alert
        v-if="errorMessage"
        :title="errorMessage"
        type="error"
        :closable="false"
        show-icon
        class="ts-login__alert"
      />

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @submit.prevent="handleSubmit"
      >
        <el-form-item label="登录名 / 姓名" prop="username">
          <el-input
            v-model="form.username"
            placeholder="请输入数字登录名或姓名，例如：10001 或 张三"
            clearable
            autocomplete="username"
          >
            <template #prefix><el-icon><User /></el-icon></template>
          </el-input>
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            show-password
            autocomplete="current-password"
            @keyup.enter="handleSubmit"
          >
            <template #prefix><el-icon><Lock /></el-icon></template>
          </el-input>
        </el-form-item>

        <el-button
          type="primary"
          size="large"
          class="ts-login__submit"
          :loading="submitting"
          native-type="submit"
        >
          登 录
        </el-button>
      </el-form>

      <div v-if="forgotEntryVisible" class="ts-login__links">
        <el-button link type="primary" @click="goForgotPassword">无法登录？</el-button>
      </div>

      <p v-if="mockEnabled" class="ts-login__hint">
        当前为 Mock 模式（后端未接入），可用账号：超级管理员 / 王经理 / 张三，密码 Init@12345
      </p>
      <p v-else class="ts-login__hint">
        连续失败 5 次账号将锁定 15 分钟
      </p>

      <!--
        版权文字（）。放在卡片内部而不是卡片下方，是因为 .ts-login 是
        「flex 行 + 居中」：再塞一个兄弟节点会把它排到卡片**右边**而不是下面。
        值为空时整行不渲染（服务端默认就是空串，前端不补默认文案）。
      -->
      <p v-if="siteStore.siteCopyright" class="ts-login__copyright">
        {{ siteStore.siteCopyright }}
      </p>
    </div>
  </div>
</template>

<style scoped>
.ts-login {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  min-height: 100dvh;
  padding: 24px 16px;
  background: var(--ts-bg);
}

.ts-login__panel {
  width: 100%;
  max-width: 400px;
  padding: 32px 28px;
  background: var(--ts-surface);
  border: 1px solid var(--ts-border);
  border-radius: 14px;
}

.ts-login__head {
  text-align: center;
  margin-bottom: 24px;
}

/* 版权文字（）：卡片底部一行小字，不与提示文案争视觉重心 */
.ts-login__copyright {
  margin: 18px 0 0;
  font-size: 11px;
  line-height: 1.5;
  color: var(--ts-text-hint);
  text-align: center;
}

.ts-login__mark {
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

.ts-login__title {
  margin: 14px 0 6px;
  font-size: 19px;
  font-weight: 500;
  color: var(--ts-text);
}

.ts-login__subtitle {
  margin: 0;
  font-size: 13px;
  color: var(--ts-text-secondary);
}

.ts-login__alert {
  margin-bottom: 16px;
}

.ts-login__submit {
  width: 100%;
  margin-top: 4px;
}

.ts-login__links {
  margin-top: 14px;
  text-align: right;
}

.ts-login__hint {
  margin: 18px 0 0;
  font-size: 12px;
  line-height: 1.7;
  text-align: center;
  color: var(--ts-text-hint);
}

@media (max-width: 767px) {
  .ts-login {
    align-items: flex-start;
    padding: 32px 16px 24px;
  }

  .ts-login__panel {
    padding: 24px 18px;
    border-radius: 12px;
  }

  .ts-login__title {
    font-size: 17px;
  }

  .ts-login__mark {
    width: 40px;
    height: 40px;
    border-radius: 10px;
  }
}
</style>
