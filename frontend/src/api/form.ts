import { http } from '@/api/request'
import type {
  FormTemplateDetail,
  FormTemplateItem,
  FormTemplatePayload,
  FormTemplateVersion
} from '@/types/form'

/**
 * 表单模板接口
 *
 * 权限：查询 `form_template:view`、变更 `form_template:manage`。
 * 按需求约定这两个权限码默认只归 super_admin —— 表单定义是所有自定义申请的「配置源头」，
 * 收得紧一些是刻意的（后端全部接口独立校验，前端隐藏只是体验）。
 */
export const formTemplateApi = {
  /** 模板列表（不含 schema） */
  list() {
    return http.get<FormTemplateItem[]>('/form/templates')
  },

  /** 模板详情（含 schema：有草稿给草稿，无草稿给最新已发布版本） */
  detail(id: number) {
    return http.get<FormTemplateDetail>(`/form/templates/${id}`)
  },

  /** 新建模板（同时创建 v1 草稿，返回模板 id） */
  create(data: FormTemplatePayload) {
    return http.post<number>('/form/templates', data)
  },

  /** 保存草稿（整体覆盖；已发布版本不受影响，会自动另开一版草稿） */
  update(id: number, data: FormTemplatePayload) {
    return http.put<void>(`/form/templates/${id}`, data)
  },

  /** 发布当前草稿为新版本，返回新版本 id（申请类型随后引用它） */
  publish(id: number) {
    return http.post<number>(`/form/templates/${id}/publish`)
  },

  /** 版本列表（倒序，含草稿） */
  versions(id: number) {
    return http.get<FormTemplateVersion[]>(`/form/templates/${id}/versions`)
  },

  /** 某版本详情（含 schema，用于版本历史预览） */
  version(versionId: number) {
    return http.get<FormTemplateVersion>(`/form/templates/versions/${versionId}`)
  },

  /** 删除模板（被申请类型引用时后端拒绝，只能停用） */
  remove(id: number) {
    return http.delete<void>(`/form/templates/${id}`)
  }
}

export default formTemplateApi
