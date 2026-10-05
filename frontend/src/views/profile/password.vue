<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import authApi from '@/api/auth'
import { useUserStore } from '@/store/user'
import type { PasswordPolicy } from '@/types/password'
import PasswordChangeForm from '@/components/PasswordChangeForm.vue'
import PasswordPolicyTips from '@/components/PasswordPolicyTips.vue'

/**
 * 密码管理（ 收尾优化·）
 *
 * 说明是「个人 → 密码管理：已登录用户自助修改密码（原密码 + 新密码 + 确认密码）
 * + 显示密码策略说明」，本页据此实现，并替换掉原先 `/system/password` 的占位页。
 *
 * <h2>两个容易做错的点</h2>
 * <ol>
 *   <li><b>AD 域账号必须整页切换为提示</b>（「AD 用户不能在本地改密码」）：
 *       域口令只存在于域控，本地改密改了也没用。若仍放出表单，用户会认真填完、
 *       再收到一个 400，并且会因为「系统要我改、又不让我改」而打电话找管理员。
 *       因此这里在<b>加载阶段</b>就按 `authType` 决定渲染哪种界面。</li>
 *   <li><b>改密成功后不要把人踢出去</b>：后端在改密时会 `token_version + 1` 作废其它设备，
 *       同时用新版本号补发一枚 Token 写回 Cookie（本机无缝续用）。
 *       若前端仍按旧实现「提示成功后强制退出登录」，就与后端行为相反 ——
 *       用户会以为「一改密码就被踢」，而实际设计恰恰是「本机不受影响」。</li>
 * </ol>
 */
const userStore = useUserStore()

const policy = ref<PasswordPolicy | null>(null)
const loading = ref(false)
/** 账号来源：优先用策略接口返回值（权威），回退到本地会话信息 */
const authType = computed(() => policy.value?.authType ?? userStore.user?.authType ?? 'LOCAL')
const isLdapAccount = computed(() => authType.value === 'LDAP')

async function loadPolicy(): Promise<void> {
  loading.value = true
  try {
    policy.value = await authApi.passwordPolicy()
  } catch {
    // 策略接口不可用时仍允许改密：表单会回退到与后端一致的默认阈值（8 位 / 2 类），
    // 策略说明区则不渲染 —— 宁可少一段说明，也不要让「改密码」这个基础功能不可用。
    policy.value = null
  } finally {
    loading.value = false
  }
}

async function handleSuccess(): Promise<void> {
  ElMessage.success('密码修改成功，其它设备上的登录已失效，本机继续有效')
  // 刷新会话信息：后端已清除强制改密标记，页面的提示语需要同步
  await userStore.fetchCurrentUser().catch(() => undefined)
}

onMounted(() => {
  void loadPolicy()
})
</script>

<template>
  <div class="ts-page">
    <section class="ts-card">
      <h3 class="ts-pwd__title">修改密码</h3>
      <p class="ts-text-secondary ts-pwd__desc">
        在这里修改你自己的登录密码。修改成功后，你在其它设备 / 浏览器上的登录会立即失效，
        当前这一台会无缝继续使用。
      </p>

      <el-alert
        v-if="userStore.forceChangePassword && !isLdapAccount"
        title="你当前处于「首次登录需修改初始密码」状态，修改完成后才能使用系统其它功能"
        type="warning"
        :closable="false"
        show-icon
        class="ts-pwd__alert"
      />

      <!-- AD 域账号：整页切换为提示（） -->
      <el-alert
        v-if="isLdapAccount"
        title="域账号请在公司 AD 中修改密码"
        type="info"
        :closable="false"
        show-icon
        class="ts-pwd__alert"
      >
        <p class="ts-pwd__alert-body">
          你的账号来自 AD 域控，登录凭据由域控统一管理，本系统不支持（也无法）在这里修改。
          请在 Windows 登录界面按 <b>Ctrl + Alt + Delete → 更改密码</b>，
          或联系公司 IT 在域控中重置。
        </p>
      </el-alert>

      <el-row v-if="!isLdapAccount" :gutter="20" class="ts-pwd__body">
        <el-col :xs="24" :md="13" class="ts-pwd__col">
          <PasswordChangeForm :policy="policy" @success="handleSuccess" />
        </el-col>
        <el-col :xs="24" :md="11" class="ts-pwd__col">
          <PasswordPolicyTips v-if="policy" :rules="policy.rules" />
        </el-col>
      </el-row>

      <!-- AD 账号也要能看到策略说明（仅供参考，例如将来转为本地账号时用得上） -->
      <div v-else-if="policy" class="ts-pwd__policy-standalone">
        <PasswordPolicyTips :rules="policy.rules" />
      </div>

      <el-skeleton v-if="loading && !policy" :rows="4" animated class="ts-pwd__skeleton" />
    </section>
  </div>
</template>

<style scoped>
.ts-pwd__title {
  margin: 0;
  font-size: 16px;
  font-weight: 500;
}

.ts-pwd__desc {
  margin: 6px 0 16px;
  font-size: 12px;
  line-height: 1.7;
}

.ts-pwd__alert {
  margin-bottom: 16px;
}

.ts-pwd__alert-body {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.8;
}

.ts-pwd__body {
  margin-top: 4px;
}

.ts-pwd__policy-standalone {
  max-width: 640px;
}

.ts-pwd__skeleton {
  max-width: 520px;
}

@media (max-width: 767px) {
  .ts-pwd__col + .ts-pwd__col {
    margin-top: 16px;
  }
}
</style>
