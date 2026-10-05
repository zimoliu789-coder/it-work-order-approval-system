<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import setupApi from '@/api/setup'
import authApi from '@/api/auth'
import { ApiError } from '@/api/request'
import { useSiteStore } from '@/store/site'
import SiteLogo from '@/components/SiteLogo.vue'
import { resetSetupCheck } from '@/router/guard'

/**
 * 初始化向导（本次新增）
 *
 * 适用场景：**第一次部署、数据库里还没有超级管理员**。
 * 访问系统会被路由守卫引导到这里，由部署者现场设定超管账号名与密码；
 * 创建成功后系统完成初始化，本页从此不再出现（再访问会自动跳登录页）。
 *
 * 三条设计约定：
 * - 账号名**不使用**员工的「5 位以上纯数字」规则 —— 超管是运维记忆的入口，允许字母；
 * - 密码走与员工同源的密码策略（规则文案由后端下发，不在这里写死）；
 * - 提交成功后**不自动登录**：让部署者用刚设的账号走一遍登录，顺带验证账号可用。
 */
const router = useRouter()
const siteStore = useSiteStore()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const checking = ref(true)
const errorMessage = ref('')
const policyRules = ref<string[]>([])
const minLength = ref(8)

interface SetupForm {
  username: string
  displayName: string
  password: string
  confirmPassword: string
}

const form = reactive<SetupForm>({
  username: '',
  displayName: '',
  password: '',
  confirmPassword: ''
})

const rules = computed<FormRules<SetupForm>>(() => ({
  username: [
    { required: true, message: '请输入超管账号名', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9_.-]{3,32}$/,
      message: '只能包含字母、数字、下划线、点或短横线，长度 3~32 位',
      trigger: 'blur'
    }
  ],
  password: [
    { required: true, message: '请输入登录密码', trigger: 'blur' },
    { min: minLength.value, message: `密码长度不能少于 ${minLength.value} 位`, trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入登录密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (value !== form.password) {
          callback(new Error('两次输入的密码不一致'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ]
}))

onMounted(async () => {
  try {
    const status = await setupApi.status()
    if (status.initialized) {
      // 已初始化：本页无意义，直接回登录页
      await router.replace('/login')
      return
    }
  } catch {
    errorMessage.value = '无法连接服务端，请确认后端已启动后刷新页面。'
  } finally {
    checking.value = false
  }
  // 密码策略文案（免认证接口）：与实际校验同源，不在这里写死阈值
  try {
    const policy = await authApi.passwordPolicy()
    policyRules.value = policy.rules
    minLength.value = policy.minLength
  } catch {
    /* 拿不到策略时不阻断初始化，仅不展示策略列表 */
  }
})

async function handleSubmit(): Promise<void> {
  if (!formRef.value) {
    return
  }
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    await setupApi.initialize({
      username: form.username.trim(),
      password: form.password,
      confirmPassword: form.confirmPassword,
      displayName: form.displayName.trim() || undefined
    })
    await ElMessageBox.alert(
      `超级管理员账号「${form.username.trim()}」已创建。请返回登录页用它登录。`,
      '初始化完成',
      { confirmButtonText: '去登录', type: 'success' }
    )
    ElMessage.success('初始化完成')
    // 必须失效路由守卫里的状态缓存：否则守卫仍以为「未初始化」，
    // 会把刚跳回 /login 的访问者又重定向到 /setup，形成死循环。
    resetSetupCheck(true)
    await router.replace('/login')
  } catch (error) {
    errorMessage.value =
      error instanceof ApiError ? error.message : '初始化失败，请检查网络或联系运维'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="ts-setup">
    <div class="ts-setup__card">
      <div class="ts-setup__brand">
        <span class="ts-setup__mark"><SiteLogo /></span>
        <div class="ts-setup__brand-text">
          <h1 class="ts-setup__title">{{ siteStore.siteName }}</h1>
          <p class="ts-setup__subtitle">首次部署 · 初始化超级管理员</p>
        </div>
      </div>

      <p v-if="checking" class="ts-setup__checking">正在检查系统状态…</p>

      <template v-else>
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="这是系统的第一次初始化"
          description="请设定一个超级管理员账号。该账号创建后不可修改、不可被重置，请妥善保管；完成后本页不会再出现。"
          class="ts-setup__alert"
        />

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          class="ts-setup__form"
          @submit.prevent="handleSubmit"
        >
          <el-form-item label="超管账号名" prop="username">
            <el-input
              v-model="form.username"
              placeholder="如 admin、it_admin（字母/数字/._-，3~32 位）"
              maxlength="32"
              autocomplete="off"
            />
          </el-form-item>
          <el-form-item label="显示名称（可选）" prop="displayName">
            <el-input
              v-model="form.displayName"
              placeholder="留空则与账号名相同"
              maxlength="32"
              autocomplete="off"
            />
          </el-form-item>
          <el-form-item label="登录密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              show-password
              placeholder="请输入登录密码"
              autocomplete="new-password"
            />
          </el-form-item>
          <el-form-item label="确认密码" prop="confirmPassword">
            <el-input
              v-model="form.confirmPassword"
              type="password"
              show-password
              placeholder="请再次输入登录密码"
              autocomplete="new-password"
              @keyup.enter="handleSubmit"
            />
          </el-form-item>

          <PasswordPolicyTips v-if="policyRules.length > 0" :rules="policyRules" />

          <p v-if="errorMessage" class="ts-setup__error">{{ errorMessage }}</p>

          <el-button
            type="primary"
            class="ts-setup__submit"
            :loading="submitting"
            @click="handleSubmit"
          >
            完成初始化
          </el-button>
        </el-form>
      </template>
    </div>
  </div>
</template>

<style scoped>
.ts-setup {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 24px 16px;
  background: linear-gradient(160deg, #eef3fb 0%, #f7f9fc 60%);
}

.ts-setup__card {
  width: 100%;
  max-width: 460px;
  padding: 28px 28px 32px;
  background: #fff;
  border: 1px solid var(--ts-border, #e4e7ed);
  border-radius: 14px;
  box-shadow: 0 12px 32px rgb(31 45 61 / 8%);
}

.ts-setup__brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 18px;
}

/* 尺寸与配色由外层容器决定（SiteLogo 刻意不接收 props，见其组件注释） */
.ts-setup__mark {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  overflow: hidden;
  font-size: 16px;
  color: #fff;
  background: var(--el-color-primary);
  border-radius: 12px;
}

.ts-setup__title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}

.ts-setup__subtitle {
  margin: 4px 0 0;
  font-size: 13px;
  color: #909399;
}

.ts-setup__checking {
  margin: 24px 0;
  color: #909399;
  text-align: center;
}

.ts-setup__alert {
  margin-bottom: 18px;
}

.ts-setup__submit {
  width: 100%;
  margin-top: 18px;
}

.ts-setup__error {
  margin: 12px 0 0;
  font-size: 13px;
  color: #f56c6c;
}
</style>
