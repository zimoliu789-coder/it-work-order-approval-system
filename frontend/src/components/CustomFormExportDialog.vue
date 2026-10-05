<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useExport } from '@/composables/useExport'
import { useResponsive } from '@/composables/useResponsive'
import { ORDER_STATUS_OPTIONS, type OrderStatusCode } from '@/types/order'
import type { ApplyTypeItem } from '@/types/applyType'

/**
 * 自定义表单数据导出对话框（ · M6）
 *
 * <h2>为什么要有这个对话框，而不是像其它列表那样「一键带当前筛选条件导出」</h2>
 * 自定义表单导出的**列**取决于「该申请类型下工单实际引用过的表单版本」，
 * 因此必须先把「导出哪个类型」问清楚——这不是多一步交互，而是唯一能把列集合定下来的输入。
 * 对话框里只有三件事：状态、时间范围、以及一句说明（列怎么来的、附件在哪）。
 * 刻意不引入新交互概念（没有「选择字段」「选择版本」这类额外开关）。
 *
 * <h2>为什么提示语里要专门提「附件在第二个工作表」</h2>
 * 表单数据里 FILE 字段的值恒为 `-`（附件按工单独立关联，不在 form_data_json 里）。
 * 不提前说清楚，用户打开文件看到一列 `-` 会以为导出有缺陷。
 */
const props = defineProps<{
  modelValue: boolean
  /** 要导出数据的申请类型（由列表行带入） */
  applyType: ApplyTypeItem | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
}>()

const { isMobile } = useResponsive()
const { loading, runExport } = useExport()

const form = reactive({
  status: null as OrderStatusCode | null,
  range: null as [string, string] | null
})

/**
 * 每次打开复位条件。
 *
 * 用 `watch` 而不是在按钮点击处复位：真正的「打开」有多种来源
 * （点行内按钮、后续可能的批量入口），把复位绑在「可见性变化」上才不会漏。
 * 这里 watch 的是 props（外部状态），不存在「程序化赋值被 watch 清掉」的问题。
 */
watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      form.status = null
      form.range = null
    }
  }
)

const title = computed(() => {
  const name = props.applyType?.typeName
  return name ? `导出表单数据 · ${name}` : '导出表单数据'
})

function onVisibleChange(value: boolean): void {
  emit('update:modelValue', value)
}

async function submit(): Promise<void> {
  const applyType = props.applyType
  if (!applyType) {
    ElMessage.warning('未指定申请类型，无法导出')
    return
  }
  try {
    await runExport(
      {
        type: 'CUSTOM_FORM',
        // 导出的是该类型下所有人的工单；非管理员会被后端直接拒绝（该导出仅管理员可用）
        scope: 'ALL',
        customForm: {
          applyTypeId: applyType.id,
          status: form.status ?? undefined,
          submitTimeFrom: form.range?.[0] ?? undefined,
          submitTimeTo: form.range?.[1] ?? undefined
        }
      },
      `自定义表单数据_${applyType.typeCode}.xlsx`
    )
    emit('update:modelValue', false)
  } catch {
    // 失败提示由请求层统一给出；对话框保持打开，用户可以直接调整条件重试
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    :width="isMobile ? '94%' : '520px'"
    @update:model-value="onVisibleChange"
  >
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="列 = 该申请类型历史表单版本的字段并集"
      description="同一字段跨版本只占一列；附件明细在第二个工作表「附件清单」中，表单里的附件列显示为 -。"
    />

    <el-form class="ts-custom-export__form" label-width="88px" :label-position="isMobile ? 'top' : 'right'">
      <el-form-item label="工单状态">
        <el-select v-model="form.status" class="ts-custom-export__field" placeholder="全部状态" clearable>
          <el-option
            v-for="item in ORDER_STATUS_OPTIONS"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="提交时间">
        <el-date-picker
          v-model="form.range"
          class="ts-custom-export__field"
          type="daterange"
          value-format="YYYY-MM-DD"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          unlink-panels
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="loading" @click="submit">导出</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.ts-custom-export__form {
  margin-top: 16px;
}

.ts-custom-export__field {
  width: 100%;
}
</style>
