import { http } from '@/api/request'
import type {
  HaActionResult,
  HaConfigPayload,
  HaDeployGuide,
  HaNodeCreatePayload,
  HaNodeItem,
  HaNodeUpdatePayload,
  HaOverview,
  HaSwitchoverAction
} from '@/types/ha'

/**
 * 主备双机热备接口（， ）
 *
 * <h2>权限：两个码，均只归超管</h2>
 * <p>`ha:view` 看（总览 / 指引），`ha:manage` 做（保存配置 / 增删节点 / 手动切换 / 立即同步）。
 * 两者都不下发给业务管理员 —— 本模块能把虚拟 IP 的归属从一台机器挪到另一台，
 * 等价于决定全公司的系统由哪台机器提供服务。
 *
 * <h2>⚠️ 写操作失败有两种形态，前端必须分开处理</h2>
 * <ol>
 *   <li><b>请求本身不成立</b>（未启用主备 / 脚本不存在 / 动作名非法）→ 后端抛业务异常，
 *       这里表现为 `ApiError`，走全局提示；</li>
 *   <li><b>请求合法但脚本执行失败</b>（目标机不可达、权限不足）→ 后端返回
 *       {@link HaActionResult}，`success=false` 且带脚本原始输出。
 *       调用方<b>不能</b>把它当成提交失败而吞掉输出 ——
 *       那段输出（ssh 报错、keepalived 拒绝）才是维护人员定位问题的唯一线索。</li>
 * </ol>
 */
export const haApi = {
  /** 页面总览：配置 + 本机角色状态 + 节点列表 + 部署探针（一次请求拿到同一时刻的快照） */
  overview() {
    return http.get<HaOverview>('/ha/overview')
  },

  /** 「三步走」指引 + 可直接抄用的部署片段 */
  guide() {
    return http.get<HaDeployGuide>('/ha/guide')
  },

  /** 保存配置（第 1 步：开关 + 域名 + 虚拟 IP）。文本字段留空即清空 */
  saveConfig(payload: HaConfigPayload) {
    return http.put<void>('/ha/config', payload)
  },

  /**
   * 一键启用主节点。
   *
   * <p>与 saveConfig 分开是**语义**上的区分：saveConfig 是「保存配置」，
   * enable 是「我决定让这台机器承担服务」。前者可以只存草稿，后者要求配置完整。
   */
  enable(payload: HaConfigPayload) {
    return http.post<HaActionResult>('/ha/enable', payload)
  },

  /**
   * 一键加入集群（备节点，）。
   *
   * <p>只需要主节点 IP 与加入令牌 —— 其余配置由主节点下发（见后端 exportConfig）。
   */
  joinCluster(payload: { masterIp: string; joinToken: string }) {
    return http.post<HaActionResult>('/ha/join-cluster', payload)
  },

  /** 添加备节点（第 2 步）。`adminPassword` 会被后端接收后立即丢弃 */
  addNode(payload: HaNodeCreatePayload) {
    return http.post<HaNodeItem>('/ha/nodes', payload)
  },

  /** 修改节点展示名 / 备注（IP 与角色不可改） */
  updateNode(id: number, payload: HaNodeUpdatePayload) {
    return http.put<HaNodeItem>(`/ha/nodes/${id}`, payload)
  },

  /** 移除备节点（本机节点后端会拒绝） */
  removeNode(id: number) {
    return http.delete<void>(`/ha/nodes/${id}`)
  },

  /**
   * 手动切换主备。
   *
   * 返回体的 `dryRun=true` 表示「本次只是演练，没有真的执行」——
   * 沙箱与未配置部署资产的环境都属于这一类，界面必须明确展示，
   * 不能让维护人员把一次演练当成真实切换。
   */
  switchover(action: HaSwitchoverAction) {
    return http.post<HaActionResult>(`/ha/switchover?action=${encodeURIComponent(action)}`)
  },

  /** 立即同步：调用 setup-replication.sh 重建复制链路并触发全量同步 */
  syncNow() {
    return http.post<HaActionResult>('/ha/sync')
  }
}

export default haApi
