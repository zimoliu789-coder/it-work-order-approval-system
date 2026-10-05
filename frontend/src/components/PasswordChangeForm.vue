<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { useUserStore } from '@/store/user'
import { ApiError } from '@/api/request'
import type { ChangePasswordRequest } from '@/types/api'
import type { PasswordPolicy } from '@/types/password'

/**
 * 自助改密表单（ 收尾优化·）
 *
 * 抽成组件的理由：本表单有<b>两个使用方</b> ——
 * 「强制改密」独立页（`/change-password`，无侧边栏，后端只放行少数接口）
 * 与「个人 → 密码管理」页（`/profile/password`，在布局内，用户主动改密）。
 * 两处的校验强度必须完全一致（前端先按策略拦下，用户才不会先提交再被后端打回），
 * 复制一份必然随时间漂移，因此收敛为唯一实现。
 *
 * 提交成功后的去向由父页面决定（`@success`）：
 * 强制改密场景应回工作台，自助改密场景应留在本页并提示「其它设备已失效」。
 */
const props = withDefaults(
  defineProps<{
    /** 是否处于「首次登录强制改密」场景（影响提示语与按钮文案） */
    forced?: boolean
    /** 后端下发的策略；未加载完成时回退到与后端一致的默认值（8 位 / 2 类） */
    policy?: PasswordPolicy | null
  }>(),
  { forced: false, policy: null }
)

const emit = defineEmits<{ success: [] }>()

const userStore = useUserStore()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const errorMessage = ref('')

const minLength = computed(() => props.policy?.minLength ?? 8)
const minCharTypes = computed(() => props.policy?.minCharTypes ?? 2)
const maxLength = computed(() => props.policy?.maxLength ?? 64)

const form = reactive<ChangePasswordRequest>({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const validateConfirm = (_rule: unknown, value: string, callback: (error?: Error) => void): void => {
  if (!value) {
    callback(new Error('请再次输入新密码'))
    return
  }
  if (value !== form.newPassword) {
    callback(new Error('两次输入的新密码不一致'))
    return
  }
  callback()
}

/**
 * 与后端 PasswordPolicyService 同强度校验。
 *
 * 阈值取自后端下发的策略（而非写死 8 / 2）：管理员把最小长度调到 12 后，
 * 若这里仍按 8 位放行，用户会在提交时才被后端拒绝 —— 先让他过、再打回来，
 * 是最容易让人怀疑「表单坏了」的体验。
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
  let types = 0
  if (/[A-Z]/.test(value)) types += 1
  if (/[a-z]/.test(value)) types += 1
  if (/\d/.test(value)) types += 1
  if (/[^A-Za-z0-9]/.test(value)) types += 1
  if (types < minCharTypes.value) {
    callback(new Error(`密码需包含大小写字母、数字、特殊字符中的至少 ${minCharTypes.value} 类`))
    return
  }
  const username = userStore.user?.username
  if (username && username.length >= 3 && value.toLowerCase().includes(username.toLowerCase())) {
    callback(new Error('密码不能包含登录名'))
    return
  }
  callback()
}

const rules: FormRules<ChangePasswordRequest> = {
  oldPassword: [
    { required: true, message: '请输入当前密码', trigger: 'blur' },
    { max: 64, message: '密码长度不能超过 64 位', trigger: 'blur' }
  ],
  newPassword: [{ required: true, validator: validateNewPassword, trigger: 'blur' }],
  confirmPassword: [{ required: true, validator: validateConfirm, trigger: 'blur' }]
}

/** 密码强度提示（按当前策略计算，而不是按写死的 8/2） */
const strength = computed(() => {
  const value = form.newPassword
  if (!value) {
    return { text: '尚未输入', color: 'var(--ts-text-hint)' }
  }
  let types = 0
  if (/[A-Z]/.test(value)) types += 1
  if (/[a-z]/.test(value)) types += 1
  if (/\d/.test(value)) types += 1
  if (/[^A-Za-z0-9]/.test(value)) types += 1

  if (value.length < minLength.value || types < minCharTypes.value) {
    return {
      text: `强度不足：需至少 ${minLength.value} 位且包含 ${minCharTypes.value} 类字符`,
      color: 'var(--ts-danger)'
    }
  }
  if (value.length >= 12 && types >= 3) {
    return { text: '强度较高', color: '#3f8f4f' }
  }
  return { text: '强度中等', color: 'var(--ts-warning)' }
})

async function submit(): Promise<void> {
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
    await userStore.changePassword({ ...form })
    form.oldPassword = ''
    form.newPassword = ''
    form.confirmPassword = ''
    formRef.value.clearValidate()
    emit('success')
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '密码修改失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}

defineExpose({ submit })
</script>

<template>
  <div class="ts-pwd-form">
    <el-alert
      v-if="errorMessage"
      :title="errorMessage"
      type="error"
      :closable="false"
      show-icon
      class="ts-pwd-form__alert"
    />

    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" size="large">
      <el-form-item label="当前密码" prop="oldPassword">
        <el-input v-model="form.oldPassword" type="password" show-password placeholder="请输入当前密码" />
      </el-form-item>

      <el-form-item label="新密码" prop="newPassword">
        <el-input
          v-model="form.newPassword"
          type="password"
          show-password
          :placeholder="`至少 ${minLength} 位，含大小写 / 数字 / 特殊字符中的 ${minCharTypes} 类`"
        />
      </el-form-item>
      <div class="ts-pwd-form__strength" :style="{ color: strength.color }">密码强度：{{ strength.text }}</div>

      <el-form-item label="确认新密码" prop="confirmPassword">
        <el-input
          v-model="form.confirmPassword"
          type="password"
          show-password
          placeholder="请再次输入新密码"
          @keyup.enter="submit"
        />
      </el-form-item>

      <div class="ts-pwd-form__actions">
        <slot name="actions" :submitting="submitting" :submit="submit">
          <el-button type="primary" :loading="submitting" @click="submit">
            {{ forced ? '确认修改并进入系统' : '确认修改' }}
          </el-button>
        </slot>
      </div>
    </el-form>
  </div>
</template>

<style scoped>
.ts-pwd-form__alert {
  margin-bottom: 14px;
}

.ts-pwd-form__strength {
  margin: -8px 0 16px;
  font-size: 12px;
}

.ts-pwd-form__actions {
  display: flex;
  gap: 12px;
  margin-top: 4px;
}

.ts-pwd-form__actions :deep(.el-button) {
  flex: 1 1 0;
  margin-left: 0;
}
</style>
