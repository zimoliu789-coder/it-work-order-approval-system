<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import authApi from '@/api/auth'
import { useUserStore } from '@/store/user'
import type { PasswordPolicy } from '@/types/password'
import PasswordChangeForm from '@/components/PasswordChangeForm.vue'
import PasswordPolicyTips from '@/components/PasswordPolicyTips.vue'

/**
 * 修改密码页（ 密码与登录安全）
 *
 * 同时承担「首次登录强制改密」场景：
 * 后端对 force_change_password=true 的用户仅放行本页面相关接口，
 * 因此此页面不依赖侧边栏布局，独立成页，避免用户绕过。
 *
 *  收尾优化· 的两处调整：
 * 1) 表单抽成 `PasswordChangeForm`，与「个人 → 密码管理」页共用同一实现
 *    （校验强度必须一致，复制一份必然漂移）；
 * 2) 增加「密码策略说明」，内容由 `GET /api/auth/password-policy` 下发 ——
 *    该接口已加入强制改密白名单，被强制改密的用户同样能看到规则，
 *    否则他面对一句「请修改密码」却不知道要求，只能反复试错。
 *
 * 提交成功后不再强制退出登录：后端改密时会补发一枚新版本号的 Token 写回 Cookie，
 * 本机无缝续用、其它设备立即失效。按旧实现跳回登录页会与后端设计相反。
 */
const router = useRouter()
const userStore = useUserStore()

const policy = ref<PasswordPolicy | null>(null)
const forced = computed(() => userStore.forceChangePassword)
const isLdapAccount = computed(() => policy.value?.authType === 'LDAP')

async function loadPolicy(): Promise<void> {
  try {
    policy.value = await authApi.passwordPolicy()
  } catch {
    // 策略接口不可用时仍允许改密（表单回退默认阈值），仅少一段说明
    policy.value = null
  }
}

async function handleSuccess(): Promise<void> {
  ElMessage.success('密码修改成功')
  await userStore.fetchCurrentUser().catch(() => undefined)
  // 强制改密的用户改完即可正常使用系统，直接送进工作台；
  // 主动改密的用户也一并回工作台 —— 他已无必要停在这张「只做一件事」的页面上
  await router.replace('/dashboard')
}

onMounted(async () => {
  void loadPolicy()
  if (userStore.isLogin) {
    return
  }
  try {
    await userStore.fetchCurrentUser()
  } catch {
    await router.replace('/login')
  }
})
</script>

<template>
  <div class="ts-cp">
    <div class="ts-cp__panel">
      <h1 class="ts-cp__title">修改密码</h1>

      <el-alert
        v-if="forced"
        title="为保证账号安全，首次登录必须修改初始密码后才能使用系统"
        type="warning"
        :closable="false"
        show-icon
        class="ts-cp__alert"
      />
      <el-alert
        v-else
        title="修改成功后，其它设备上的登录将立即失效，本机继续有效"
        type="info"
        :closable="false"
        show-icon
        class="ts-cp__alert"
      />

      <el-alert
        v-if="isLdapAccount"
        title="域账号请在公司 AD 中修改密码"
        type="info"
        :closable="false"
        show-icon
        class="ts-cp__alert"
      >
        <p class="ts-cp__alert-body">
          你的账号来自 AD 域控，登录凭据由域控统一管理，本系统不支持在这里修改。
        </p>
      </el-alert>

      <template v-if="!isLdapAccount">
        <PasswordChangeForm :forced="forced" :policy="policy" @success="handleSuccess" />
      </template>

      <PasswordPolicyTips v-if="policy" :rules="policy.rules" class="ts-cp__policy" />

      <div class="ts-cp__links">
        <el-button v-if="!forced" link type="primary" @click="router.push('/dashboard')">
          返回工作台
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ts-cp {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  min-height: 100dvh;
  padding: 24px 16px;
  background: var(--ts-bg);
}

.ts-cp__panel {
  width: 100%;
  max-width: 520px;
  padding: 32px 28px;
  background: var(--ts-surface);
  border: 1px solid var(--ts-border);
  border-radius: 14px;
}

.ts-cp__title {
  margin: 0 0 20px;
  font-size: 18px;
  font-weight: 500;
  text-align: center;
}

.ts-cp__alert {
  margin-bottom: 14px;
}

.ts-cp__alert-body {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.7;
}

.ts-cp__policy {
  margin-top: 16px;
}

.ts-cp__links {
  margin-top: 14px;
  text-align: center;
}

@media (max-width: 767px) {
  .ts-cp {
    align-items: flex-start;
    padding: 24px 16px;
  }

  .ts-cp__panel {
    padding: 22px 18px;
    border-radius: 12px;
  }
}
</style>
