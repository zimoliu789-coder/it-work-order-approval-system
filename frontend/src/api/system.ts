import { http } from '@/api/request'
import type { PageResult } from '@/types/api'
import type {
  ConfigCatalog,
  ConfigTestMailPayload,
  ConfigTestMailResult,
  ConfigTestSmsPayload,
  ConfigTestSmsResult,
  LogOption,
  OperationLogItem,
  OperationLogQuery,
  SiteInfo,
  SystemConfigItem
} from '@/types/system'

/**
 * 系统管理接口
 * - 操作日志：（仅 super_admin 可查看）
 * - 系统配置： / （ 只读，用于核对 Flyway 种子数据）
 */
export const systemApi = {
  /** 分页查询操作日志 */
  getOperationLogs(query: OperationLogQuery) {
    const params: Record<string, unknown> = {
      page: query.page ?? 1,
      size: query.size ?? 20
    }
    if (query.module) params.module = query.module
    if (query.action) params.action = query.action
    if (query.operatorName) params.operatorName = query.operatorName
    if (query.result) params.result = query.result
    if (query.startTime) params.startTime = query.startTime
    if (query.endTime) params.endTime = query.endTime
    return http.get<PageResult<OperationLogItem>>('/logs', params)
  },

  /** 查询操作日志筛选项（仅 super_admin）：返回模块与动作的下拉选项 */
  getLogOptions() {
    return http.get<{ modules: LogOption[]; actions: LogOption[] }>('/logs/options')
  },

  /** 查询全部系统配置（仅 super_admin，含分组 / 说明 / 是否可改） */
  getSystemConfigs() {
    return http.get<SystemConfigItem[]>('/system/configs')
  },

  /**
   * 批量保存系统参数（需求方三波·第一波·）
   *
   * 后端「全成或全败」：任一参数取值非法则整批不写入；保存后立即刷新缓存（热生效）。
   * 返回实际变更项数（值未变的项不写库）。
   */
  updateSystemConfigs(values: Record<string, string>) {
    return http.put<{ affected: number }>('/system/configs', { values })
  },

  // ---------------- 卡片式参数页（ ·  ·  / 三） ----------------

  /**
   * 查询系统参数目录：分组 + 中文标签 + 控件类型 + 单位 + 区间 + 说明 + 当前值。
   *
   * 与 `getSystemConfigs()` 的分工：那个是扁平结构（回归脚本与旧调用方用），
   * 这个是「给人看并直接渲染成表单」的。分组与顺序由服务端定，前端不重排 ——
   * 否则后端新增一个分组、前端静默不显示，表现为「参数加了但界面找不到」。
   */
  getConfigCatalog() {
    return http.get<ConfigCatalog>('/system/configs/catalog')
  },

  /**
   * 发送测试邮件（仅内置超管）。
   *
   * 请求里可带未保存的表单值作为覆盖项（空字段 = 沿用已保存配置），
   * 因此「填完先试一下、成了再保存」是可行的。
   * 失败时后端会把原因归类为可读文案（认证失败 / 连不上 / SSL 不匹配），
   * **请直接展示 message**，不要用固定文案覆盖 —— 具体原因才是这个功能的意义。
   */
  testMail(payload: ConfigTestMailPayload) {
    return http.post<ConfigTestMailResult>('/system/configs/test-mail', payload)
  },

  /**
   * 发送测试短信（仅内置超管； · ）。
   *
   * 与 `testMail` 的**成功语义不同**：短信网关本期尚未接入，
   * 因此成功返回只代表「参数校验通过」，返回体里的 `delivered` 恒为 `false`。
   * 页面必须把 `message` 如实展示（提示「未真实发出」），
   * 而不是弹一个绿色的「发送成功」。
   *
   * 失败时后端已把原因归类为可读文案（缺哪几项 / 号码格式 / 通道未启用），
   * 请**直接展示 message**。
   */
  testSms(payload: ConfigTestSmsPayload) {
    return http.post<ConfigTestSmsResult>('/system/configs/test-sms', payload)
  },

  // ---------------- 站点品牌（：系统名称与 logo 可配置） ----------------

  /**
   * 查询站点品牌信息。
   *
   * 免认证接口（后端 permit-all）：登录页在没有会话时就要显示名称与 logo，
   * 因此它不能要求登录。只暴露「系统叫什么」「logo 长什么样」，不含业务数据。
   */
  getSiteInfo() {
    return http.get<SiteInfo>('/system/site-info')
  },

  /**
   * 上传站点 logo（仅内置超管；png / jpg，10MB 内）。
   *
   * 走 multipart：必须用 postForm，确保 FormData 不被序列化成 JSON。
   */
  uploadSiteLogo(file: File, onProgress?: (percent: number) => void) {
    const form = new FormData()
    form.append('file', file)
    return http.postForm<SiteInfo>('/system/site-logo', form, onProgress)
  },

  /** 清除图片 logo，恢复默认「IT」文字图标（仅内置超管） */
  resetSiteLogo() {
    return http.delete<SiteInfo>('/system/site-logo')
  }
}

export default systemApi
