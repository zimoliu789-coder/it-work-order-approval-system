<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Clock, Promotion } from '@element-plus/icons-vue'
import { formTemplateApi } from '@/api/form'
import FormDesigner from '@/components/FormDesigner.vue'
import { useResponsive } from '@/composables/useResponsive'
import { useUserStore } from '@/store/user'
import { cloneSchema, emptySchema, type FormSchema, type FormTemplateDetail, type FormTemplateVersion } from '@/types/form'

/**
 * 表单设计器页
 *
 * 承载「编辑模板基本信息 + 拖拽设计字段 + 保存草稿 / 发布版本 + 查看版本历史」。
 *
 * <h2>为什么设计器是可复用组件、而这一页是薄壳</h2>
 * 设计器组件只认一份 {@link FormSchema}，不关心「模板 id 是多少、保存调哪个接口」；
 * 这些都属于「页面」的职责。分开后，将来若要在别处复用设计器（例如复制模板），
 * 只需再写一个薄壳页，不必改动设计器本身。
 *
 * <h2>发布后的行为</h2>
 * 发布成功会重新拉取详情：此时 draft 变为 false，页面顶部提示「已发布版本 vN，此版本不可再改；
 * 继续编辑会另存为新草稿」。这正是「已发布版本不可修改」的可视化表达。
 */
const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const { isMobile } = useResponsive()

const canManage = computed(() => userStore.hasPerm('form_template:manage'))

const templateId = computed(() => Number(route.params.templateId))

const loading = ref(false)
const saving = ref(false)
const detail = ref<FormTemplateDetail | null>(null)
const versions = ref<FormTemplateVersion[]>([])
const versionsVisible = ref(false)

const form = reactive({
  templateName: '',
  description: ''
})

const schema = ref<FormSchema>(emptySchema())

/** 是否存在未发布草稿（发布按钮的可用性依据之一） */
const hasDraft = computed(() => detail.value?.draft === true)

const statusText = computed(() => {
  if (!detail.value) {
    return ''
  }
  if (detail.value.draft) {
    return `当前编辑：v${detail.value.versionNo ?? '-'}（草稿，未发布）`
  }
  return `当前编辑：v${detail.value.versionNo ?? '-'}（已发布，继续保存会另存为新草稿）`
})

async function loadDetail(): Promise<void> {
  loading.value = true
  try {
    const data = await formTemplateApi.detail(templateId.value)
    detail.value = data
    form.templateName = data.templateName
    form.description = data.description ?? ''
    schema.value = data.schema ? cloneSchema(data.schema) : emptySchema()
  } catch {
    detail.value = null
  } finally {
    loading.value = false
  }
}

async function loadVersions(): Promise<void> {
  try {
    versions.value = await formTemplateApi.versions(templateId.value)
  } catch {
    versions.value = []
  }
}

async function openVersions(): Promise<void> {
  versionsVisible.value = true
  await loadVersions()
}

/** 保存草稿：整体覆盖式保存（看到什么就存什么） */
async function handleSave(): Promise<void> {
  if (!canManage.value) {
    return
  }
  const name = form.templateName.trim()
  if (!name) {
    ElMessage.warning('请填写模板名称')
    return
  }
  saving.value = true
  try {
    await formTemplateApi.update(templateId.value, {
      templateName: name,
      description: form.description.trim() || null,
      schema: schema.value
    })
    ElMessage.success('草稿已保存')
    await loadDetail()
  } catch {
    // 由请求层统一提示
  } finally {
    saving.value = false
  }
}

/** 发布会先落一次草稿再发布：避免「改了字段却忘了保存」导致发布的是旧内容 */
async function handlePublish(): Promise<void> {
  if (!canManage.value) {
    return
  }
  const name = form.templateName.trim()
  if (!name) {
    ElMessage.warning('请填写模板名称')
    return
  }
  try {
    await ElMessageBox.confirm(
      '发布后该版本将被冻结、不可再修改，且可被申请类型引用。确认发布？',
      '发布表单版本',
      { type: 'warning' }
    )
  } catch {
    return
  }
  saving.value = true
  try {
    await formTemplateApi.update(templateId.value, {
      templateName: name,
      description: form.description.trim() || null,
      schema: schema.value
    })
    const versionId = await formTemplateApi.publish(templateId.value)
    ElMessage.success(`已发布版本（id ${versionId}），可用于创建申请类型`)
    await loadDetail()
  } catch {
    // 校验未通过时后端返回字段级明细，请求层已提示
  } finally {
    saving.value = false
  }
}

function goBack(): void {
  void router.push('/system/apply-type')
}

onMounted(loadDetail)
</script>

<template>
  <div class="ts-page">
    <section class="ts-card">
      <div class="ts-designer-page__head">
        <div class="ts-designer-page__head-main">
          <el-button link :icon="ArrowLeft" @click="goBack">返回申请类型管理</el-button>
          <h3 class="ts-designer-page__title">
            {{ detail?.templateName ?? '表单设计器' }}
            <el-tag v-if="detail" size="small" effect="plain" :type="detail.published ? 'success' : 'info'">
              {{ detail.statusLabel ?? detail.status }}
            </el-tag>
          </h3>
          <p class="ts-text-hint">{{ statusText }}</p>
        </div>
        <el-button size="small" plain :icon="Clock" @click="openVersions">版本历史</el-button>
      </div>

      <el-form
        v-loading="loading"
        class="ts-designer-page__meta"
        label-width="88px"
        :label-position="isMobile ? 'top' : 'right'"
      >
        <el-form-item label="模板名称" required>
          <el-input v-model="form.templateName" :disabled="!canManage" maxlength="64" />
        </el-form-item>
        <el-form-item label="模板说明">
          <el-input
            v-model="form.description"
            :disabled="!canManage"
            type="textarea"
            :rows="2"
            maxlength="255"
            show-word-limit
          />
        </el-form-item>
      </el-form>

      <el-alert
        v-if="hasDraft && canManage"
        type="warning"
        :closable="false"
        show-icon
        title="当前有未发布的草稿改动，点击右下角「发布版本」后才会生成可被引用的新版本。"
        class="ts-designer-page__alert"
      />

      <FormDesigner
        v-model="schema"
        :readonly="!canManage"
        :saving="saving"
        class="ts-mt-16"
        @save="handleSave"
        @publish="handlePublish"
      />
    </section>

    <!-- 版本历史 -->
    <el-dialog v-model="versionsVisible" title="版本历史" :width="isMobile ? '94%' : '620px'">
      <el-table :data="versions" border size="small">
        <el-table-column prop="versionNo" label="版本" width="80">
          <template #default="{ row }">v{{ row.versionNo }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.draft" size="small" type="warning" effect="plain">草稿</el-tag>
            <el-tag v-else size="small" type="success" effect="plain">已发布</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="字段数" width="80">
          <template #default="{ row }">{{ row.fieldCount ?? 0 }}</template>
        </el-table-column>
        <el-table-column prop="publishedAt" label="发布时间" min-width="160">
          <template #default="{ row }">{{ row.publishedAt ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="publishedByName" label="发布人" min-width="110">
          <template #default="{ row }">{{ row.publishedByName ?? '—' }}</template>
        </el-table-column>
      </el-table>
      <p class="ts-text-hint ts-mt-16">
        <el-icon><Promotion /></el-icon>
        已发布的版本不可修改；被申请类型引用的版本会决定该类型新提交工单的表单口径。
      </p>
    </el-dialog>
  </div>
</template>

<style scoped>
.ts-designer-page__head {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;
}

.ts-designer-page__head-main {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.ts-designer-page__title {
  display: flex;
  gap: 8px;
  align-items: center;
  margin: 0;
  font-size: 16px;
  font-weight: 500;
}

.ts-designer-page__meta {
  max-width: 720px;
  margin-top: 12px;
}

.ts-designer-page__alert {
  margin-top: 8px;
}
</style>
