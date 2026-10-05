<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import userApi from '@/api/user'
import { useResponsive } from '@/composables/useResponsive'
import type { UserOption } from '@/types/user'

/**
 * 员工选择弹窗（：分组成员添加 / 审批人选择 / 最终小组成员维护三处复用）
 *
 * 交互：支持姓名/登录名远程搜索；列表中同时展示「当前所属分组」，
 * 便于在添加分组成员时按规范提示「该员工已在其他分组」；离职/禁用状态用标签显式标出，
 * 避免管理员误选不可用的审批人或执行人。
 */
const props = withDefaults(
  defineProps<{
    modelValue: boolean
    title?: string
    /** 多选（分组成员、小组成员）或单选（审批人） */
    multiple?: boolean
    /** 不展示的候选人（例如已在本分组/本小组中的成员） */
    excludeIds?: number[]
  }>(),
  {
    title: '选择员工',
    multiple: true,
    excludeIds: () => []
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  confirm: [users: UserOption[]]
}>()

const { isMobile } = useResponsive()

const keyword = ref('')
const loading = ref(false)
const users = ref<UserOption[]>([])
const multiSelected = ref<number[]>([])
const singleSelected = ref<number | undefined>(undefined)

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value)
})

/** 排除项在前端过滤：候选集在后端是全量分页上限内的结果，过滤成本极低 */
const list = computed(() => users.value.filter((user) => !props.excludeIds.includes(user.id)))

/**
 * 已加载过的员工缓存。搜索会整体替换 users，如果「选中集合」只从当前结果页里过滤，
 * 那么先勾选 A、再搜索 B 之后，A 会被静默丢弃（点确定时反而提示「请至少选择一名员工」）。
 * 用缓存把「勾选集合」与「当前结果页」解耦，保证跨搜索的勾选不丢失。
 */
const userCache = new Map<number, UserOption>()

const selectedCount = computed(() =>
  props.multiple ? multiSelected.value.length : singleSelected.value == null ? 0 : 1
)

async function load(): Promise<void> {
  loading.value = true
  try {
    users.value = await userApi.options({ keyword: keyword.value.trim() || undefined })
    for (const user of users.value) {
      userCache.set(user.id, user)
    }
  } catch {
    users.value = []
  } finally {
    loading.value = false
  }
}

watch(visible, (opened) => {
  if (!opened) {
    return
  }
  keyword.value = ''
  multiSelected.value = []
  singleSelected.value = undefined
  void load()
})

function handleSearch(): void {
  void load()
}

function handleConfirm(): void {
  const pickedIds = props.multiple ? multiSelected.value : singleSelected.value == null ? [] : [singleSelected.value]
  // 从缓存取用户对象（而非从当前结果页过滤），确保跨搜索的勾选不会被丢弃
  const picked = pickedIds
    .map((id) => userCache.get(id))
    .filter((user): user is UserOption => user != null && !props.excludeIds.includes(user.id))
  if (picked.length === 0) {
    ElMessage.warning('请至少选择一名员工')
    return
  }
  emit('confirm', picked)
  visible.value = false
}

function roleTag(role: string): string {
  if (role === 'super_admin') {
    return '超级管理员'
  }
  return role === 'admin' ? '管理员' : ''
}
</script>

<template>
  <el-dialog v-model="visible" :title="title" :width="isMobile ? '94%' : '560px'" append-to-body>
    <div class="ts-user-select">
      <el-input
        v-model="keyword"
        placeholder="输入姓名或登录名搜索"
        clearable
        class="ts-user-select__search"
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      >
        <template #append>
          <el-button :loading="loading" @click="handleSearch">搜索</el-button>
        </template>
      </el-input>

      <div v-loading="loading" class="ts-user-select__list">
        <el-empty v-if="!loading && list.length === 0" :image-size="60" description="没有匹配的员工" />

        <el-checkbox-group v-if="multiple" v-model="multiSelected" class="ts-user-select__group">
          <el-checkbox v-for="user in list" :key="user.id" :value="user.id" class="ts-user-select__item">
            <span class="ts-user-select__name">{{ user.displayName }}</span>
            <span class="ts-text-hint ts-user-select__group-name">{{ user.departmentName ?? '未分组' }}</span>
            <el-tag v-if="user.dimission" type="danger" size="small" effect="plain">离职</el-tag>
            <el-tag v-else-if="!user.enabled" type="info" size="small" effect="plain">已禁用</el-tag>
            <el-tag v-if="roleTag(user.role)" type="warning" size="small" effect="plain">
              {{ roleTag(user.role) }}
            </el-tag>
          </el-checkbox>
        </el-checkbox-group>

        <el-radio-group v-else v-model="singleSelected" class="ts-user-select__group">
          <el-radio v-for="user in list" :key="user.id" :value="user.id" class="ts-user-select__item">
            <span class="ts-user-select__name">{{ user.displayName }}</span>
            <span class="ts-text-hint ts-user-select__group-name">{{ user.departmentName ?? '未分组' }}</span>
            <el-tag v-if="user.dimission" type="danger" size="small" effect="plain">离职</el-tag>
            <el-tag v-else-if="!user.enabled" type="info" size="small" effect="plain">已禁用</el-tag>
          </el-radio>
        </el-radio-group>
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :disabled="selectedCount === 0" @click="handleConfirm">
        确定{{ selectedCount > 0 ? `（${selectedCount}）` : '' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.ts-user-select__search {
  margin-bottom: 12px;
}

.ts-user-select__list {
  max-height: 320px;
  overflow-y: auto;
}

.ts-user-select__group {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.ts-user-select__item {
  display: flex;
  align-items: center;
  height: auto;
  min-height: 36px;
  padding: 4px 0;
  margin-right: 0;
}

.ts-user-select__name {
  margin-right: 6px;
}

.ts-user-select__group-name {
  margin-right: 8px;
  font-size: 12px;
}

@media (max-width: 767px) {
  .ts-user-select__list {
    max-height: 46vh;
  }
}
</style>
