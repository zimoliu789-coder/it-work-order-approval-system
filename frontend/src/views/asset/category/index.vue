<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { deviceCategoryApi } from '@/api/device'
import { useResponsive } from '@/composables/useResponsive'
import { useUserStore } from '@/store/user'
import type { DeviceCategory } from '@/types/device'

/**
 * 设备分类管理（：一级分类 + 二级分类）
 *
 * 设计要点：
 * - 分类固定两级。一级分类下挂二级分类；层级在新增时确定，之后只能改名，不能改层级
 *   （层级变更会让已挂接设备的归属错乱）；
 * - 删除守卫：仍有子分类或仍被设备引用（含已软删除设备）时后端拒绝，前端给出明确说明；
 * - 排序：同级列表整组提交后端重排，因此「上移/下移」按钮对移动端同样可用。
 *
 * 权限：admin 具备「设备台账管理」权限，故分类维护对 admin 同样开放。
 */

const { isMobile } = useResponsive()
const userStore = useUserStore()

const canEdit = computed(() => userStore.role === 'super_admin' || userStore.role === 'admin')

const loading = ref(false)
const saving = ref(false)
const tree = ref<DeviceCategory[]>([])
const activeId = ref<number | null>(null)

const dialogVisible = ref(false)
const dialogMode = ref<'createPrimary' | 'createSecondary' | 'edit'>('createPrimary')
const editingId = ref<number | null>(null)
const form = reactive({ categoryName: '', remark: '' })

const activeCategory = computed(() => tree.value.find((item) => item.id === activeId.value) ?? null)
const secondaryList = computed(() => activeCategory.value?.children ?? [])

const dialogTitle = computed(() => {
  if (dialogMode.value === 'createPrimary') return '新增一级分类'
  if (dialogMode.value === 'createSecondary') return '新增二级分类'
  return '编辑分类'
})

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  try {
    tree.value = await deviceCategoryApi.tree()
    if (tree.value.length === 0) {
      activeId.value = null
      return
    }
    const stillExists = activeId.value != null && tree.value.some((item) => item.id === activeId.value)
    if (!stillExists) {
      activeId.value = tree.value[0].id
    }
  } catch {
    tree.value = []
  } finally {
    loading.value = false
  }
}

function selectPrimary(category: DeviceCategory): void {
  activeId.value = category.id
}

function openCreatePrimary(): void {
  dialogMode.value = 'createPrimary'
  editingId.value = null
  form.categoryName = ''
  form.remark = ''
  dialogVisible.value = true
}

function openCreateSecondary(): void {
  if (!activeCategory.value) {
    ElMessage.warning('请先在左侧选择一级分类')
    return
  }
  dialogMode.value = 'createSecondary'
  editingId.value = null
  form.categoryName = ''
  form.remark = ''
  dialogVisible.value = true
}

function openEdit(category: DeviceCategory): void {
  dialogMode.value = 'edit'
  editingId.value = category.id
  form.categoryName = category.categoryName
  form.remark = category.remark ?? ''
  dialogVisible.value = true
}

async function handleSubmit(): Promise<void> {
  const categoryName = form.categoryName.trim()
  if (!categoryName) {
    ElMessage.warning('请填写分类名称')
    return
  }
  const remark = form.remark.trim() ? form.remark.trim() : null
  saving.value = true
  try {
    if (dialogMode.value === 'createPrimary') {
      await deviceCategoryApi.create({ categoryName, parentId: null, remark })
      ElMessage.success('一级分类已创建')
    } else if (dialogMode.value === 'createSecondary') {
      await deviceCategoryApi.create({ categoryName, parentId: activeCategory.value?.id ?? null, remark })
      ElMessage.success('二级分类已创建')
    } else if (editingId.value != null) {
      await deviceCategoryApi.update(editingId.value, { categoryName, remark })
      ElMessage.success('分类已更新')
    }
    dialogVisible.value = false
    await load()
  } catch {
    // 错误提示由请求层统一处理（同级重名等）
  } finally {
    saving.value = false
  }
}

async function handleDelete(category: DeviceCategory): Promise<void> {
  const isPrimary = category.level === 1
  const extra = isPrimary
    ? '该分类下的二级分类将一并受保护；若分类下仍有设备，删除会被拒绝。'
    : '若分类下仍有设备（含已删除设备），删除会被拒绝。'
  try {
    await ElMessageBox.confirm(
      `确认删除${isPrimary ? '一级' : '二级'}分类「${category.categoryName}」？${extra}`,
      '删除设备分类',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deviceCategoryApi.remove(category.id)
    ElMessage.success('分类已删除')
    await load()
  } catch {
    // 有子分类 / 有设备引用时后端返回明确提示
  }
}

/**
 * 同级上移 / 下移
 *
 * 后端排序接口要求整组提交，因此本地先换位再提交完整 ID 序列。
 */
async function move(siblings: DeviceCategory[], index: number, offset: number, parentId: number): Promise<void> {
  const target = index + offset
  if (target < 0 || target >= siblings.length) {
    return
  }
  const orderedIds = siblings.map((item) => item.id)
  const [moved] = orderedIds.splice(index, 1)
  orderedIds.splice(target, 0, moved)
  try {
    await deviceCategoryApi.sort(parentId, orderedIds)
    await load()
  } catch {
    // 并发增删导致集合不一致时后端会提示刷新
  }
}
</script>

<template>
  <div class="ts-page">
    <section class="ts-card">
      <div class="ts-flex-between ts-cat__head">
        <div>
          <h3 class="ts-cat__title">设备分类管理</h3>
          <p class="ts-text-secondary ts-cat__desc">
            支持一级 + 二级分类。分类下仍有设备或仍有子分类时不可删除；设备挂接由「设备台账」维护。
          </p>
        </div>
        <el-button v-if="canEdit" type="primary" @click="openCreatePrimary">+ 新增一级分类</el-button>
      </div>

      <div v-loading="loading" class="ts-cat__layout ts-mt-16">
        <!-- 一级分类 -->
        <div class="ts-cat__panel">
          <div class="ts-cat__panel-head">
            <strong>一级分类</strong>
            <span class="ts-text-hint">{{ tree.length }} 个</span>
          </div>
          <div v-if="tree.length === 0" class="ts-cat__empty">
            <el-empty :image-size="60" description="暂无分类，请先新增一级分类" />
          </div>
          <ul v-else class="ts-cat__list">
            <li
              v-for="(item, index) in tree"
              :key="item.id"
              class="ts-cat__item"
              :class="{ 'is-active': item.id === activeId }"
              @click="selectPrimary(item)"
            >
              <div class="ts-cat__item-main">
                <span class="ts-cat__name">{{ item.categoryName }}</span>
                <el-tag size="small" effect="plain" type="info">设备 {{ item.deviceCount }}</el-tag>
              </div>
              <div v-if="canEdit" class="ts-cat__item-actions" @click.stop>
                <el-button link size="small" :disabled="index === 0" @click="move(tree, index, -1, 0)">上移</el-button>
                <el-button
                  link
                  size="small"
                  :disabled="index === tree.length - 1"
                  @click="move(tree, index, 1, 0)"
                >
                  下移
                </el-button>
                <el-button link type="primary" size="small" @click="openEdit(item)">改名</el-button>
                <el-button link type="danger" size="small" @click="handleDelete(item)">删除</el-button>
              </div>
            </li>
          </ul>
        </div>

        <!-- 二级分类 -->
        <div class="ts-cat__panel">
          <div class="ts-cat__panel-head">
            <strong>二级分类</strong>
            <div class="ts-cat__panel-head-right">
              <span class="ts-text-hint">
                {{ activeCategory ? `归属：${activeCategory.categoryName}` : '未选择一级分类' }}
              </span>
              <el-button v-if="canEdit" size="small" type="primary" plain @click="openCreateSecondary">
                + 新增二级分类
              </el-button>
            </div>
          </div>
          <div v-if="secondaryList.length === 0" class="ts-cat__empty">
            <el-empty :image-size="60" description="该一级分类下暂无二级分类（设备可直接挂一级分类）" />
          </div>
          <ul v-else class="ts-cat__list">
            <li v-for="(item, index) in secondaryList" :key="item.id" class="ts-cat__item">
              <div class="ts-cat__item-main">
                <span class="ts-cat__name">{{ item.categoryName }}</span>
                <el-tag size="small" effect="plain" type="info">设备 {{ item.deviceCount }}</el-tag>
              </div>
              <div v-if="canEdit" class="ts-cat__item-actions">
                <el-button
                  link
                  size="small"
                  :disabled="index === 0"
                  @click="move(secondaryList, index, -1, activeCategory?.id ?? 0)"
                >
                  上移
                </el-button>
                <el-button
                  link
                  size="small"
                  :disabled="index === secondaryList.length - 1"
                  @click="move(secondaryList, index, 1, activeCategory?.id ?? 0)"
                >
                  下移
                </el-button>
                <el-button link type="primary" size="small" @click="openEdit(item)">改名</el-button>
                <el-button link type="danger" size="small" @click="handleDelete(item)">删除</el-button>
              </div>
            </li>
          </ul>
        </div>
      </div>

      <p v-if="!canEdit" class="ts-text-hint ts-mt-16">当前角色为只读，维护分类需要管理员及以上权限</p>
      <p v-else class="ts-text-hint ts-mt-16">
        提示：{{ isMobile ? '移动端' : 'PC 端' }}排序使用「上移 / 下移」按钮；分类改名后已挂接设备的历史记录不受影响。
      </p>
    </section>

    <!-- 新增 / 编辑 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" :width="isMobile ? '94%' : '460px'">
      <el-form label-width="80px" :label-position="isMobile ? 'top' : 'right'">
        <el-form-item label="分类名称" required>
          <el-input v-model="form.categoryName" maxlength="64" show-word-limit placeholder="例如：电脑 / 笔记本" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="255" show-word-limit placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ts-cat__head {
  gap: 16px;
  align-items: flex-start;
}

.ts-cat__title {
  margin: 0;
  font-size: 16px;
  font-weight: 500;
}

.ts-cat__desc {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
}

.ts-cat__layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
}

.ts-cat__panel {
  border: 1px solid var(--ts-border);
  border-radius: 10px;
  padding: 12px;
  background: #fbfcfe;
  min-height: 180px;
}

.ts-cat__panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--ts-border);
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.ts-cat__panel-head-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.ts-cat__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.ts-cat__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid transparent;
  border-radius: 8px;
  cursor: pointer;
  flex-wrap: wrap;
}

.ts-cat__item:hover {
  background: #f2f6ff;
}

.ts-cat__item.is-active {
  background: #eef4ff;
  border-color: var(--ts-primary, #409eff);
}

.ts-cat__item-main {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.ts-cat__name {
  font-weight: 500;
  word-break: break-all;
}

.ts-cat__item-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
}

.ts-cat__empty {
  padding: 12px 0;
}

@media (max-width: 767px) {
  .ts-cat__head {
    flex-direction: column;
  }

  .ts-cat__head .el-button {
    width: 100%;
  }

  .ts-cat__layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .ts-cat__item {
    align-items: flex-start;
    flex-direction: column;
  }

  .ts-cat__item-actions {
    width: 100%;
  }
}
</style>
