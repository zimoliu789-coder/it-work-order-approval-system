<script setup lang="ts">
import { computed, onMounted, reactive, ref, nextTick } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules, type TreeInstance } from 'element-plus'
import { useUserStore } from '@/store/user'
import roleApi from '@/api/role'
import metaApi from '@/api/meta'
import TablePage from '@/components/TablePage.vue'
import type { DataScopeCode, DataScopeOption, PermNode, RoleItem } from '@/types/permission'
import permissionPolicyApi from '@/api/permissionPolicy'
import type { PermissionPolicyItem } from '@/types/permissionPolicy'
import type { ColumnDef } from '@/types/table'

/**
 * 角色与权限管理（需求方三波·第一波·）
 *
 * 三件事：
 * 1. 角色 CRUD（编码 / 名称 / 数据权限 / 备注 / 启用 / 排序）；
 * 2. 菜单树 + 操作权限勾选（覆盖式保存）；
 * 3. 内置角色保护：内置不可删除、不可改编码；super_admin 权限恒为全量且不可编辑。
 *
 * 权限：查看 role:view、写操作 role:manage，默认仅 super_admin。
 * 前端按权限隐藏按钮，后端 api 仍独立校验。
 *
 * ---
 *  · W4-E：**T2 档** —— 只迁 `TablePage` 骨架，**不接筛选持久化**。
 * 角色数量是个位数、且本页根本没有筛选条件 ⇒ 无分页（`:show-pagination="false"`）。
 * 移动端走通用卡片（改造前窄屏是横向滚动表格， 要求转卡片）。
 */
const userStore = useUserStore()
const canManage = computed(() => userStore.hasPerm('role:manage'))

/** 列定义（代码为事实源；T2 不启用列设置） */
const columns: ColumnDef[] = [
  { key: 'roleCode', label: '角色编码', minWidth: 140, showOverflowTooltip: true, card: 'title' },
  { key: 'roleName', label: '角色名称', minWidth: 140, showOverflowTooltip: true },
  { key: 'dataScopeLabel', label: '数据权限', minWidth: 110 },
  { key: 'builtin', label: '类型', minWidth: 90 },
  { key: 'enabled', label: '状态', minWidth: 90 },
  { key: 'userCount', label: '使用人数', minWidth: 90 },
  { key: 'permissionCount', label: '权限数', minWidth: 90 },
  { key: 'remark', label: '备注', minWidth: 160, showOverflowTooltip: true, cardHideOnEmpty: true },
  { key: 'action', label: '操作', minWidth: 200, fixed: 'right', configurable: false, card: false }
]

const loading = ref(false)
const roles = ref<RoleItem[]>([])
const permTree = ref<PermNode[]>([])
const dataScopes = ref<DataScopeOption[]>([])

// ------------------------------------------------------------------
// 编辑 / 新建角色
// ------------------------------------------------------------------
const formVisible = ref(false)
const formMode = ref<'create' | 'edit'>('create')
const formRef = ref<FormInstance>()
const form = reactive({
  roleCode: '',
  roleName: '',
  dataScope: 'SELF' as DataScopeCode,
  remark: '',
  enabled: true,
  sortNo: 100
})

const rules: FormRules = {
  roleCode: [
    { required: true, message: '请输入角色编码', trigger: 'blur' },
    { pattern: /^[a-z][a-z0-9_]{1,31}$/, message: '小写字母开头，仅含小写字母/数字/下划线，长度 2-32', trigger: 'blur' }
  ],
  roleName: [{ required: true, message: '请输入角色名称', trigger: 'blur' }],
  dataScope: [{ required: true, message: '请选择数据权限', trigger: 'change' }]
}

// ------------------------------------------------------------------
// 权限勾选
// ------------------------------------------------------------------
const permVisible = ref(false)
const permRole = ref<RoleItem | null>(null)
const permSaving = ref(false)
const treeRef = ref<TreeInstance>()

const permRoleReadonly = computed(() => permRole.value?.roleCode === 'super_admin')
const permRoleCount = computed(() => {
  if (!permRole.value) {
    return 0
  }
  return permRole.value.permissions?.length ?? permRole.value.permissionCount ?? 0
})

async function loadRoles(): Promise<void> {
  loading.value = true
  try {
    roles.value = await roleApi.list()
  } catch {
    roles.value = []
  } finally {
    loading.value = false
  }
}

async function loadMeta(): Promise<void> {
  try {
    const [tree, scopes] = await Promise.all([metaApi.permissions(), metaApi.dataScopes()])
    permTree.value = tree
    dataScopes.value = scopes
  } catch {
    // 元数据接口不可用时，权限树留空；角色列表仍可用
    permTree.value = []
    dataScopes.value = []
  }
}

function openCreate(): void {
  formMode.value = 'create'
  form.roleCode = ''
  form.roleName = ''
  form.dataScope = 'SELF'
  form.remark = ''
  form.enabled = true
  form.sortNo = 100
  formVisible.value = true
  void nextTick(() => formRef.value?.clearValidate())
}

function openEdit(row: RoleItem): void {
  formMode.value = 'edit'
  form.roleCode = row.roleCode
  form.roleName = row.roleName
  form.dataScope = row.dataScope
  form.remark = row.remark ?? ''
  form.enabled = row.enabled
  form.sortNo = row.sortNo
  formVisible.value = true
  void nextTick(() => formRef.value?.clearValidate())
}

async function submitForm(): Promise<void> {
  if (!formRef.value) {
    return
  }
  await formRef.value.validate(async (valid) => {
    if (!valid) {
      return
    }
    const payload = {
      roleCode: form.roleCode,
      roleName: form.roleName,
      dataScope: form.dataScope,
      remark: form.remark || null,
      enabled: form.enabled,
      sortNo: form.sortNo
    }
    try {
      if (formMode.value === 'create') {
        await roleApi.create(payload)
        ElMessage.success('角色已创建，请为其配置权限')
      } else {
        await roleApi.update(form.roleCode, payload)
        ElMessage.success('角色已更新')
      }
      formVisible.value = false
      await loadRoles()
    } catch {
      // 编码重复 / 内置保护等由请求层提示
    }
  })
}

async function openPermissions(row: RoleItem): Promise<void> {
  try {
    permRole.value = await roleApi.detail(row.roleCode)
  } catch {
    permRole.value = { ...row }
  }
  permVisible.value = true
  await nextTick()
  // super_admin 权限恒全量，展示但禁用编辑
  treeRef.value?.setCheckedKeys(permRole.value?.permissions ?? [], false)
}

async function savePermissions(): Promise<void> {
  if (!permRole.value || !treeRef.value) {
    return
  }
  // 只提交叶子权限码（分组节点 group:xxx 不可授予）
  const checked = (treeRef.value.getCheckedKeys(false) as string[]).filter((code) => !code.startsWith('group:'))
  permSaving.value = true
  try {
    const result = await roleApi.setPermissions(permRole.value.roleCode, { permissions: checked })
    ElMessage.success(`已保存 ${result.affected} 项权限`)
    permVisible.value = false
    await loadRoles()
  } catch {
    // 越权 / 非法码由请求层提示
  } finally {
    permSaving.value = false
  }
}

async function removeRole(row: RoleItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除角色「${row.roleName}」吗？删除后使用该角色的员工将失去对应权限。`,
      '删除角色',
      { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await roleApi.remove(row.roleCode)
    ElMessage.success('角色已删除')
    await loadRoles()
  } catch {
    // 内置 / 仍被使用由请求层提示
  }
}

// ------------------------------------------------------------------
// 可申请权限
// ------------------------------------------------------------------

/**
 * 全部权限码的策略（可申请性 + 风险等级）。
 *
 * <p>保存时**只提交改动过的行**：40 多个权限码逐行 PUT 会产生几十个请求，
 * 而绝大多数行根本没动过。原始快照存在 `policyOriginal` 里做对比。
 */
const policies = ref<PermissionPolicyItem[]>([])
const policyLoading = ref(false)
const policySaving = ref(false)
const policyOriginal = ref<Record<string, { applicable: boolean; riskLevel: string }>>({})

const applicableCount = computed(() => policies.value.filter((row) => row.applicable).length)
const highRiskCount = computed(() => policies.value.filter((row) => row.riskLevel === 'HIGH').length)

async function loadPolicies(): Promise<void> {
  if (!canManage.value) {
    return
  }
  policyLoading.value = true
  try {
    const list = await permissionPolicyApi.list()
    policies.value = list
    const snapshot: Record<string, { applicable: boolean; riskLevel: string }> = {}
    for (const row of list) {
      snapshot[row.code] = { applicable: row.applicable, riskLevel: row.riskLevel }
    }
    policyOriginal.value = snapshot
  } catch {
    // 策略加载失败不阻塞角色管理本身（它是页面的附加区块）
    policies.value = []
    policyOriginal.value = {}
  } finally {
    policyLoading.value = false
  }
}

async function savePolicies(): Promise<void> {
  const changed = policies.value.filter((row) => {
    const original = policyOriginal.value[row.code]
    return !original || original.applicable !== row.applicable || original.riskLevel !== row.riskLevel
  })
  if (changed.length === 0) {
    ElMessage.info('没有需要保存的改动')
    return
  }
  policySaving.value = true
  try {
    for (const row of changed) {
      await permissionPolicyApi.update(row.code, { applicable: row.applicable, riskLevel: row.riskLevel })
    }
    ElMessage.success(`已保存 ${changed.length} 项策略`)
    await loadPolicies()
  } catch {
    // 失败提示已由请求层统一弹出
  } finally {
    policySaving.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadRoles(), loadMeta()])
  // 可申请权限区只在有 role:manage 时加载（无权限的人看不到它）
  void loadPolicies()
})
</script>

<template>
  <div class="ts-page">
    <section class="ts-card ts-role__head">
      <div>
        <h3 class="ts-role__title">角色与权限</h3>
        <p class="ts-text-secondary ts-role__desc">
          内置角色（super_admin / admin / user）不可删除、不可改编码；<b>super_admin</b> 权限恒为全量且不可编辑，
          保证系统永远可以救回来。自定义角色从零开始授权。
        </p>
      </div>
      <el-button v-if="canManage" type="primary" @click="openCreate">新建角色</el-button>
    </section>

    <section class="ts-card">
      <TablePage
        :columns="columns"
        :rows="roles"
        :loading="loading"
        route-path="/system/role"
        :show-pagination="false"
        :show-column-config="false"
        empty-text="暂无角色"
      >
        <template #cell-dataScopeLabel="{ row }">
          {{ (row as RoleItem).dataScopeLabel ?? (row as RoleItem).dataScope }}
        </template>
        <template #cell-builtin="{ row }">
          <el-tag :type="(row as RoleItem).builtin ? 'warning' : 'info'" size="small" effect="plain">
            {{ (row as RoleItem).builtin ? '内置' : '自定义' }}
          </el-tag>
        </template>
        <template #cell-enabled="{ row }">
          <el-tag :type="(row as RoleItem).enabled ? 'success' : 'info'" size="small" effect="plain">
            {{ (row as RoleItem).enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
        <template #cell-action="{ row }">
          <el-button link type="primary" @click="openPermissions(row as RoleItem)">权限</el-button>
          <el-button v-if="canManage" link type="primary" @click="openEdit(row as RoleItem)">编辑</el-button>
          <el-button v-if="canManage && !(row as RoleItem).builtin" link type="danger" @click="removeRole(row as RoleItem)">
            删除
          </el-button>
          <span v-else-if="canManage" class="ts-text-hint">内置保护</span>
        </template>

        <!-- 移动端：走通用卡片，操作按钮走 #card-actions -->
        <template #card-actions="{ row }">
          <el-button size="small" type="primary" plain @click="openPermissions(row as RoleItem)">权限</el-button>
          <el-button v-if="canManage" size="small" plain @click="openEdit(row as RoleItem)">编辑</el-button>
          <el-button
            v-if="canManage && !(row as RoleItem).builtin"
            size="small"
            type="danger"
            plain
            @click="removeRole(row as RoleItem)"
          >
            删除
          </el-button>
          <span v-else-if="canManage" class="ts-text-hint">内置保护</span>
        </template>
      </TablePage>
    </section>

    <!-- 新建 / 编辑角色 -->
    <el-dialog v-model="formVisible" :title="formMode === 'create' ? '新建角色' : '编辑角色'" width="520px" append-to-body>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="角色编码" prop="roleCode">
          <el-input v-model="form.roleCode" :disabled="formMode === 'edit'" placeholder="如 auditor" />
        </el-form-item>
        <el-form-item label="角色名称" prop="roleName">
          <el-input v-model="form.roleName" placeholder="如 审计员" />
        </el-form-item>
        <el-form-item label="数据权限" prop="dataScope">
          <el-select v-model="form.dataScope" class="ts-role__full">
            <el-option v-for="item in dataScopes" :key="item.code" :label="item.label" :value="item.code">
              <span>{{ item.label }}</span>
              <span class="ts-text-hint ts-role__option-hint">{{ item.description }}</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="排序号">
          <el-input v-model.number="form.sortNo" />
          <span class="ts-text-hint">数字越小越靠前</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="用途说明（可选）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>

    <!-- 权限勾选 -->
    <!--
      可申请权限：决定「哪些权限能通过『系统权限申请』拿到」以及「拿到它要不要多走一级超管」。
      放在角色页而不是新开一页：它就是「谁能有什么权限」这件事的另一面 ——
      角色页回答「某个角色默认有什么」，这里回答「某个人还能额外申请什么」。
    -->
    <section v-if="canManage" class="ts-card ts-mt-16">
      <div class="ts-flex-between ts-role__head">
        <div>
          <h3 class="ts-role__title">可申请权限</h3>
          <p class="ts-text-secondary ts-role__desc">
            勾选「可申请」决定员工能在「系统权限申请」里选到哪些权限；标为「高危」的权限在审批时会自动多走一级超管。
            未改动的项按系统默认（提权类默认不可申请 —— 拿到它们就能绕过本流程自我提权）。
          </p>
        </div>
        <el-button type="primary" :loading="policySaving" @click="savePolicies">保存策略</el-button>
      </div>
      <el-table v-loading="policyLoading" :data="policies" size="small" border max-height="440">
        <el-table-column prop="name" label="权限" min-width="220" show-overflow-tooltip />
        <el-table-column prop="code" label="权限码" min-width="190" show-overflow-tooltip />
        <el-table-column label="可申请" width="100" align="center">
          <template #default="{ row }">
            <el-checkbox v-model="row.applicable" />
          </template>
        </el-table-column>
        <el-table-column label="风险等级" width="170" align="center">
          <template #default="{ row }">
            <el-select v-model="row.riskLevel" size="small" class="ts-role__risk">
              <el-option label="普通（一级）" value="NORMAL" />
              <el-option label="高危（两级）" value="HIGH" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="110" align="center">
          <template #default="{ row }">
            <el-tag
              v-if="row.applicableOverridden || row.riskLevelOverridden"
              size="small"
              type="warning"
              effect="plain"
            >
              已自定义
            </el-tag>
            <el-tag v-else size="small" type="info" effect="plain">系统默认</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <p class="ts-text-hint ts-role__tip">
        共 {{ policies.length }} 项，其中可申请 {{ applicableCount }} 项、高危 {{ highRiskCount }} 项。
        「已自定义」表示这一项被人工改过（否则按代码默认值）。
      </p>
    </section>

    <el-dialog v-model="permVisible" :title="`权限配置 - ${permRole?.roleName ?? ''}`" width="560px" append-to-body>
      <el-alert
        v-if="permRoleReadonly"
        type="info"
        :closable="false"
        show-icon
        title="super_admin 权限恒为全量且不可编辑"
        description="这是刻意的保护：无论授权数据被如何修改，超管永远不会被锁在系统之外。"
        class="ts-role__alert"
      />
      <div v-loading="permTree.length === 0" class="ts-role__tree-wrap">
        <el-tree
          ref="treeRef"
          :data="permTree"
          node-key="code"
          show-checkbox
          default-expand-all
          :props="{ label: 'name', children: 'children' }"
          :disabled="permRoleReadonly"
        >
          <template #default="{ data }">
            <span class="ts-role__node">
              <span>{{ data.name }}</span>
              <span class="ts-text-hint ts-role__node-code">{{ data.menuPath || data.code }}</span>
            </span>
          </template>
        </el-tree>
      </div>
      <p class="ts-text-hint ts-role__tip">
        共勾选 {{ permRoleCount }} 项权限；分组节点（如「工单管理」）仅用于归类，不单独授予。
      </p>
      <template #footer>
        <el-button @click="permVisible = false">关闭</el-button>
        <el-button v-if="!permRoleReadonly && canManage" type="primary" :loading="permSaving" @click="savePermissions">
          保存权限
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ts-role__head {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.ts-role__title {
  margin: 0 0 4px;
  font-size: 16px;
  font-weight: 500;
}

.ts-role__desc {
  margin: 0;
  font-size: 13px;
}

.ts-role__full {
  width: 100%;
}

.ts-role__option-hint {
  margin-left: 12px;
  font-size: 12px;
}

.ts-role__alert {
  margin-bottom: 12px;
}

.ts-role__tree-wrap {
  max-height: 420px;
  overflow: auto;
  padding: 8px;
  border: 1px solid var(--ts-border);
  border-radius: 8px;
}

.ts-role__node {
  display: flex;
  gap: 12px;
  align-items: center;
}

.ts-role__node-code {
  font-size: 12px;
}

.ts-role__risk {
  width: 130px;
}

.ts-role__tip {
  margin: 10px 0 0;
  font-size: 12px;
}
</style>
