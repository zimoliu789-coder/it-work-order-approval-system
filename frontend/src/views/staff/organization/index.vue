<script setup lang="ts">
import { computed, h, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useUserStore } from '@/store/user'
import type { BatchResult, RoleCode } from '@/types/api'
import { departmentApi } from '@/api/department'
import { userApi } from '@/api/user'
import { adApi } from '@/api/ad'
import { roleApi } from '@/api/role'
import { useResponsive } from '@/composables/useResponsive'
import { copyText } from '@/utils/clipboard'
import {
  canDragDeptNode,
  canDropDeptNode,
  deptNameMatches,
  normalizeNodeDropType,
  resolveDropParentId,
  type DeptTreeLike
} from '@/utils/deptTree'
import { ROLE_OPTIONS } from '@/types/user'
import type { UserAccount, UserAccountQuery, UserOption, UserUpdatePayload } from '@/types/user'
import type { DepartmentNode, DepartmentOption } from '@/types/department'
import type { AllowDropType, TableInstance, TreeInstance } from 'element-plus'
import type { ColumnDef } from '@/types/table'
import { AUTH_TYPE_OPTIONS, type AccountAuthType, type AdAccountConvertVO } from '@/types/ad'
import BatchResultDialog from '@/components/BatchResultDialog.vue'
import TablePage from '@/components/TablePage.vue'
import UserImportDialog from '@/components/UserImportDialog.vue'

/**
 * 组织与人员—— 钉钉通讯录风格：**左部门树 + 右成员列表**。
 *
 * <h2>三合一</h2>
 * 本页合并了改造前的三个页面：「员工档案」（本文件的前身）、
 * 「部门管理」（`views/system/biz-group`）、「最终处理部门管理」（`views/system/handler-group`）。
 * 三者的共性都是「一个人属于哪个部门、这个部门谁说了算」—— 分成三个页面时，
 * 管理员调整一次组织结构要在三处之间来回跳，而且三处各自维护一份「部门下拉」。
 *
 * <h2>两个面板的职责边界</h2>
 * - **左树**：组织结构本身（增删改部门、调上下级、设部门主管、显示人数）；
 * - **右列表**：选中部门下的成员（沿用原员工档案的全部能力：新增 / 编辑 / 离职 /
 *   重置密码 / 账号来源互转 / 批量导入）。
 *
 * <h2>为什么「部门主管」在树上做成一等操作</h2>
 * 它是成员「直属主管」的默认值，也是审批流「申请人直属领导」规则的解析来源 ——
 * 没设主管，该部门的人提交的申请就会落到超级管理员兜底，而不是报给他的上级。
 * 这是「组织数据」直接影响「审批能不能落到人」的一条通路，因此不给它藏在二级页面里。
 *
 * <h2>部门主管 vs 成员直属主管</h2>
 * 成员列表里仍可单独指定「直属领导」（覆盖部门主管）。两者并存的口径：
 * 成员未手工指定过时跟随部门主管；一旦手工指定（后端置 `leader_override=1`），
 * 部门主管变更**不再**改写他 —— 手工指定是"例外"，例外不该被批量的规则冲掉。
 *
 * 写操作仅 super_admin；admin 只读（列表可见，按钮隐藏）。后端会强制校验，前端只按角色显隐入口。
 *
 * 业务约束：
 * - 标记离职会**连锁**：名下「使用中」工单全部转「待收回」+ 通知对应执行人 + 账号禁用，
 *   因此二次确认里必须把「名下有 N 台设备」摆到管理员眼前，避免误操作；
 * - 若该员工仍有在途审批节点待其处理，离职**不会**自动改派，需管理员事后手工处理；
 * - 超级管理员不可被标记离职、不可被禁用（后端强制，前端不给入口）；
 * - 登录名新增后不可改（后端 DTO 无该字段），编辑时只读展示。
 *
 *   追加：
 * - 「账号来源」列与筛选；
 * - AD 域账号**不可本地重置密码**（域口令只存在于域控，本地重置是无效操作），
 *   入口置灰并提示「域账号请在 AD 域控中修改密码」；
 * - 超管可在两个来源之间互转（AD → 本地生成一次性临时口令；本地 → AD 前先确认域控中存在该登录名）。
 */

const { isMobile } = useResponsive()
const userStore = useUserStore()
const isSuperAdmin = computed(() => userStore.role === 'super_admin')
/**
 * 当前登录用户 id —— 供「自己那行不显示禁用 / 标记离职」使用（）。
 *
 * 比 id 而不是姓名：姓名唯一是业务约定，但历史数据未必满足；id 是主键，恒唯一。
 */
const currentUserId = computed(() => userStore.user?.id ?? 0)

/**
 * 列定义（ · M3-B）
 *
 * 逐列照抄改造前写法，包括三处"必须分开说"的列被完整保留为插槽：
 * 直属领导的三种状态、在途审批的 0 与非 0、状态的「离职/在职 + 启用/禁用 + 离职时间」。
 * 操作列最宽（7 个按钮）且 `configurable: false` —— 它被隐藏等于本页失去全部管理能力。
 */
const columns: ColumnDef[] = [
  // 多选列（P3 批量操作）：TablePage 只渲染 type="selection"，key 仍作稳定标识
  { key: 'selection', label: '选择', width: 46, selection: true },
  { key: 'realName', label: '姓名', minWidth: 140 },
  { key: 'authTypeLabel', label: '账号来源', width: 100, align: 'center' },
  { key: 'departmentName', label: '部门', minWidth: 130, showOverflowTooltip: true },
  { key: 'leaderName', label: '直属领导', minWidth: 110, showOverflowTooltip: true },
  { key: 'roleLabel', label: '角色', width: 100, align: 'center' },
  { key: 'heldDeviceCount', label: '名下设备', width: 90, align: 'center' },
  { key: 'pendingApprovalCount', label: '在途审批', width: 90, align: 'center' },
  { key: 'status', label: '状态', width: 130, align: 'center' },
  { key: 'createdAt', label: '创建时间', minWidth: 165, showOverflowTooltip: true },
  { key: 'action', label: '操作', width: 300, fixed: 'right', configurable: false }
]

/**
 * AD 域控配置不再作为本页页签：已拆为独立页面
 * `views/system/ad/index.vue`（路径 `/system/ldap`，落「系统管理」一级目录下）。
 * 本页因此不再需要 `canViewAd` / `activeTab` —— 少一层页签，员工列表即整页内容。
 */

const loading = ref(false)
const records = ref<UserAccount[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const acting = ref(false)

// ------------------------------------------------------------------
// 部门（左树）—— 
// ------------------------------------------------------------------

/** 部门树（含人数与主管；结构由后端物化路径决定，前端只渲染） */
const deptTree = ref<DepartmentNode[]>([])
/**
 * 部门扁平选项（带 `displayPath`）。
 *
 * 与树**同时**存在不是冗余：树用于「点选一个部门看成员」，选项用于
 * 「新增/编辑部门时选择上级」—— 后者需要一个带完整路径的可读下拉，
 * 而不是一个可展开的节点。两者都来自后端，避免前端各自摊平导致口径不一。
 */
const deptOptions = ref<DepartmentOption[]>([])

/**
 * 当前选中的部门 id。
 *
 * `null` = 「全部成员」（不按部门过滤），这是进入页面时的默认态 ——
 * 一进来就锁死某个部门，会让管理员误以为系统里只有那一个部门的人。
 */
const selectedDeptId = ref<number | null>(null)

/** 手机端部门树折叠（PC 恒展开；手机上左树占满屏宽会挤掉列表） */
const deptAsideVisible = ref(true)

/** 树加载失败时只影响左树，不阻塞成员列表（两者独立降级） */
const deptLoading = ref(false)

/**
 * 当前选中部门的节点对象（用于标题栏显示「XX 部门 · N 人」）。
 *
 * 从树里递归查找而不是额外存一份：部门改名 / 移动后树会重载，
 * 再存一份就会出现「标题还是旧名字」的脏数据。
 */
const selectedDept = computed<DepartmentNode | null>(() => {
  if (selectedDeptId.value == null) {
    return null
  }
  const walk = (nodes: DepartmentNode[]): DepartmentNode | null => {
    for (const node of nodes) {
      if (node.id === selectedDeptId.value) {
        return node
      }
      const hit = walk(node.children ?? [])
      if (hit) {
        return hit
      }
    }
    return null
  }
  return walk(deptTree.value)
})

const query = reactive<{
  keyword: string
  dimission: boolean | null
  role: UserAccountQuery['role']
  departmentId: number | null
  authType: AccountAuthType | null
}>({
  keyword: '',
  dimission: null,
  role: null,
  departmentId: null,
  authType: null
})

// ------------------------------------------------------------------
// 新增 / 编辑弹窗
// ------------------------------------------------------------------
const formDialog = reactive<{
  visible: boolean
  mode: 'create' | 'edit'
  submitting: boolean
  id: number
  realName: string
  username: string
  password: string
  departmentId: number | null
  role: RoleCode
  displayName: string
  /**
   * 直属领导。
   *
   * 用途：审批流程里的「申请人直属领导」规则取的就是这个字段。
   * 所以它不是通讯录装饰，而是审批能不能落到人身上的前置数据。null = 未配置。
   */
  leaderId: number | null
  /** 编辑对象是否为 AD 域账号（决定「密码」字段是置灰提示还是可填） */
  ldapAccount: boolean
  /**
   * 编辑对象的角色是否被锁定（2026-09-20 ）。
   *
   * 仅内置超管 `administrator` 为 `true`：其角色不可被任何人降级，下拉置灰。
   * 其它超管（非 administrator）为 `false`，角色下拉不禁用，可直接改角色。
   */
  roleLocked: boolean
  /**
   * 手机号 / 邮箱（上线前）。
   *
   * 只有**内置超管**能把它们写到别人身上；其他角色保存时字段置灰且不参与提交
   * （后端也会忽略，前端只是让限制可见）。
   */
  phone: string
  email: string
  /** 本次编辑是否允许改联系方式（= 当前登录者是否为内置超管；新增时恒为 true） */
  contactEditable: boolean
}>({
  visible: false,
  mode: 'create',
  submitting: false,
  id: 0,
  realName: '',
  username: '',
  password: '',
  departmentId: null,
  role: 'user',
  displayName: '',
  leaderId: null,
  ldapAccount: false,
  roleLocked: false,
  phone: '',
  email: '',
  contactEditable: true
})

// 批量导入弹窗显隐
const importVisible = ref(false)

/**
 * 可分配角色下拉：优先取后端「可分配角色」（需求方三波·第一波· 支持自定义角色），
 * 接口不可用时回退内置三角色常量，保证「分配角色」功能不会因一个辅助接口挂掉而不可用。
 */
const roleOptions = ref<Array<{ value: RoleCode; label: string }>>([...ROLE_OPTIONS])

async function loadRoleOptions(): Promise<void> {
  try {
    const list = await roleApi.options()
    if (list.length > 0) {
      roleOptions.value = list.map((item) => ({ value: item.roleCode as RoleCode, label: item.roleName }))
    }
  } catch {
    // 回退内置角色（保持页面可用）
  }
}

/**
 * 直属领导下拉候选。
 *
 * 只允许选「在职且启用」的员工（`available`），并且编辑时排除本人 ——
 * 自己当自己的领导会让 LEADER 规则解析出一个诡异的环，意义也不存在。
 */
const leaderOptions = ref<UserOption[]>([])

const leaderCandidates = computed(() =>
  leaderOptions.value.filter(
    (user) => user.available && (formDialog.mode !== 'edit' || user.id !== formDialog.id)
  )
)

async function loadLeaderOptions(): Promise<void> {
  try {
    leaderOptions.value = await userApi.options()
  } catch {
    leaderOptions.value = []
  }
}

onMounted(async () => {
  await Promise.all([loadDepartments(), loadRoleOptions(), loadLeaderOptions()])
  await load()
})

/**
 * 左树 DOM 引用（ 搜索过滤需要调用实例方法 `filter()`）。
 *
 * 只借它调方法；树的**数据**始终来自 `deptTree`，不从实例回读 ——
 * 两处各存一份状态必然漂移。
 */
const deptTreeRef = ref<TreeInstance>()

/** 部门搜索关键词。空串 = 不过滤 */
const deptKeyword = ref('')

/**
 * 关键词变化 → 让 el-tree 重算节点可见性。
 *
 * 过滤规则在 `filterDeptNode`（纯函数 `deptNameMatches`），这里只负责喂值：
 * Element Plus 的 `filter` 按「自身命中或任一子孙命中」决定可见性，
 * 因此搜「研发」时上级路径不会被隐藏。
 */
watch(deptKeyword, (keyword) => {
  void applyDeptFilter(keyword)
})

/**
 * 加载部门树与扁平选项。
 *
 * 两个接口一起发（无依赖），任一失败都各自降级为空 —— 让「左树挂了」不至于
 * 连带把右侧成员列表也变成空白，管理员至少还能通过搜索找到人。
 */
async function loadDepartments(): Promise<void> {
  deptLoading.value = true
  try {
    const [tree, options] = await Promise.all([departmentApi.tree(), departmentApi.options()])
    deptTree.value = tree
    deptOptions.value = options
  } catch {
    deptTree.value = []
    deptOptions.value = []
  } finally {
    deptLoading.value = false
  }
  // 重新拉取会整体重建树节点 ⇒ 过滤必须重新施加：Element Plus 不会在 data
  // 变化后自动重放 `filter`，否则会出现「拖拽一下，搜索条件被悄悄清掉」。
  await applyDeptFilter()
}

// ------------------------------------------------------------------
// 左树交互
// ------------------------------------------------------------------

/**
 * 点击树节点 → 右侧列表切到该部门。
 *
 * 点「已选中的节点」第二次时取消选择、回到「全部成员」——
 * 这是列表类交互的通行预期（再点一次就是"取消筛选"），比另设一个
 * 「查看全部」按钮更省事，也让「怎么回到全部」无需教学。
 */
function handleDeptSelect(node: DepartmentNode): void {
  if (selectedDeptId.value === node.id) {
    selectedDeptId.value = null
  } else {
    selectedDeptId.value = node.id
  }
  query.departmentId = selectedDeptId.value
  page.value = 1
  void load()
}

/** 清除部门筛选（回到「全部成员」） */
function clearDeptFilter(): void {
  selectedDeptId.value = null
  query.departmentId = null
  page.value = 1
  void load()
}

/** 在树里按 id 找一个节点（找不到返回 null） */
function findDeptNode(nodes: DepartmentNode[], id: number): DepartmentNode | null {
  for (const node of nodes) {
    if (node.id === id) {
      return node
    }
    const hit = findDeptNode(node.children ?? [], id)
    if (hit) {
      return hit
    }
  }
  return null
}

// ------------------------------------------------------------------
// 左树搜索过滤
// ------------------------------------------------------------------

/**
 * 让 el-tree 按关键词重算节点可见性。
 *
 * `nextTick` 不可省：`deptTree` 刚被整体替换时树还没重建，
 * 此时调 `filter` 会作用在**旧的**节点集合上（表现为「重拉后过滤失效」）。
 */
async function applyDeptFilter(keyword: string = deptKeyword.value): Promise<void> {
  await nextTick()
  deptTreeRef.value?.filter(keyword)
}

/** el-tree 的 `filter-node-method`：命中关键词的节点（及其祖先）保持可见 */
function filterDeptNode(value: string, data: Record<string, any>): boolean {
  return deptNameMatches(data.deptName as string | null | undefined, value)
}

// ------------------------------------------------------------------
// 左树拖拽调整层级
// ------------------------------------------------------------------

/**
 * el-tree 回调里的节点实例。
 *
 * <h2>为什么不是 `{ data: DepartmentNode }`</h2>
 * Element Plus **不导出**它内部的 `Node` 类，且 `Node.data` 的类型是宽泛的
 * `TreeNodeData`（`Record<string, any>`）。把回调参数注解成 `{ data: DepartmentNode }`
 * 会在 `strictFunctionTypes` 下因**参数逆变**而无法赋给 `AllowDropFunction`（TS2322）——
 * 编译期就挡住，不会带病上线。
 *
 * 因此这里用 `Record<string, any>` 承接，再在调用处收窄成 `DeptTreeLike`。
 * 真正的规则在 `utils/deptTree` 的纯函数里：判定写在这里就只能靠人工拖拽验证，
 * 而拖拽恰恰是「错了不报错、只表现为时灵时不灵」的功能，单测够不着。
 */
type DeptDragNode = { data: Record<string, any> }

/** `allow-drag`：根节点（公司）不可拖 —— 对应后端 DEPARTMENT_ROOT_PROTECTED */
function allowDragDept(node: DeptDragNode): boolean {
  return canDragDeptNode(node.data as DeptTreeLike)
}

/** `allow-drop`：挡掉「拖到自己/子孙下」与「拖到根的前后（变成根的同级）」 */
function allowDropDept(drag: DeptDragNode, drop: DeptDragNode, type: AllowDropType): boolean {
  return canDropDeptNode(drag.data as DeptTreeLike, drop.data as DeptTreeLike, type)
}

/**
 * `node-drop`：落定后调后端移动，然后**无论成败都重新拉树**。
 *
 * <h2>为什么总是重拉，而不是就地改本地树</h2>
 * 三条理由，任一都足以否决「就地改」：
 * 1. **失败回滚**：后端会拒（成环 / 同级重名 / 根保护）。就地改就得手写回滚，
 *    等于把树算法再实现一遍，写错了界面还看不出来；
 * 2. **同级重排**：拖到兄弟前/后，后端只改父子关系、**不改 `sortOrder`**
 *    ⇒ 本地看到的顺序是假的，重拉才会回到库里的权威顺序；
 * 3. **物化路径**：`path` / `depth` 由后端重写，前端绝不重算
 *    （口径不一致会让 `path LIKE` 的子树查询静默多算或少算人）。
 *
 * 失败提示由请求层统一弹出（`api/request` 的响应拦截器），这里不重复弹。
 */
async function handleDeptDrop(
  drag: DeptDragNode,
  drop: DeptDragNode,
  dropType: 'before' | 'after' | 'inner'
): Promise<void> {
  const newParentId = resolveDropParentId(
    drag.data as DeptTreeLike,
    drop.data as DeptTreeLike,
    normalizeNodeDropType(dropType)
  )
  // 理论上到不了这里（allow-drop 已挡）；保留是为了「判定只有一处」——
  // 万一某条路径没触发 allow-drop，也不会发出非法请求。
  if (newParentId === undefined) {
    await loadDepartments()
    return
  }
  // 落点与当前父部门相同 ⇒ 只是同级重排，后端不改顺序 ⇒ 重拉还原权威顺序即可
  if (newParentId === drag.data.parentId) {
    await loadDepartments()
    return
  }
  try {
    await departmentApi.move(drag.data.id, newParentId)
    ElMessage.success(`部门「${drag.data.deptName}」已移动`)
  } catch {
    // 后端拒绝的提示已由请求层弹出，这里只负责把界面拉回与库一致
  } finally {
    await loadDepartments()
  }
}

// ------------------------------------------------------------------
// 新增 / 编辑部门
// ------------------------------------------------------------------
const deptDialog = reactive<{
  visible: boolean
  mode: 'create' | 'edit'
  submitting: boolean
  id: number
  deptName: string
  parentId: number | null
  /**
   * 打开弹窗时该部门的**原始**上级 —— 仅编辑模式有意义（ 挂账项， 修复）。
   *
   * <h2>为什么需要它</h2>
   * `PUT /api/departments/{id}` 虽是**整体替换**语义，却**只认名称/排序/流程/备注四项**：
   * 报文里的 `parentId` 会被后端直接忽略（改层级只有 `PUT /{id}/parent` 一条路）。
   * 于是「在编辑弹窗里改了上级部门、点保存」会提示「部门已保存」而层级纹丝不动 —— **静默无效**。
   * 修法：保存前与原始值比对，变了就先 `move` 再 `update`。顺序不能反 ——
   * `update` 不碰父子关系，放后面不会把刚移好的层级顶回去。
   */
  originalParentId: number | null
  sortOrder: number
  remark: string
  /**
   * 该部门当前绑定的借用审批流程版本 —— **只读携带，本页没有编辑入口**。
   *
   * <h2>为什么必须原样回传（2026-10-02  回归发现的真实坑）</h2>
   * `PUT /api/departments/{id}` 是**整体替换**语义：报文里没有这个键 ⇒ 后端收到 null ⇒ 解绑。
   * 而本页的弹窗只管名称/上级/排序/备注，若不带这一项，「管理员改个部门名」就会
   * **静默解绑**该部门的流程 —— 审批路径整条换掉，且没有任何提示。
   *
   * 反方向（后端把 null 当作「不改动」）也想过，但那样「解绑」将永远无法完成，
   * 且与改造前 `PUT /biz-groups/{id}/config` 的行为不一致（  回归 /
   * 就是守着「传 null 即解绑」这条契约）。所以选择「整体替换 + 调用方回传」，
   * 与 `views/system/apply-type` 对同名字段的处理方式一致。
   */
  approvalFlowVersionId: number | null
}>({
  visible: false,
  mode: 'create',
  submitting: false,
  id: 0,
  deptName: '',
  parentId: null,
  originalParentId: null,
  sortOrder: 0,
  remark: '',
  approvalFlowVersionId: null
})

/**
 * 「上级部门」候选。
 *
 * 编辑时必须**排除自己与自己的全部子孙** —— 把部门移到自己的子孙下会形成环，
 * 后端会拒绝（`DEPARTMENT_PARENT_INVALID`），但前端提前把它从下拉里去掉，
 * 用户就不会先选错再被打回。这是「前端让错误选项不可见」而非「替后端做校验」：
 * 后端的那道闸门仍然在，它才是事实源。
 */
const parentDeptOptions = computed<DepartmentOption[]>(() => {
  if (deptDialog.mode !== 'edit') {
    return deptOptions.value
  }
  const excluded = new Set<number>([deptDialog.id])
  const self = findDeptNode(deptTree.value, deptDialog.id)
  const walk = (nodes: DepartmentNode[]): void => {
    for (const node of nodes) {
      excluded.add(node.id)
      walk(node.children ?? [])
    }
  }
  if (self) {
    walk(self.children ?? [])
  }
  return deptOptions.value.filter((item) => !excluded.has(item.id))
})

/** 新增部门：`parentId` 由调用处给出（顶部按钮传 null = 挂在根下，「添加子部门」传当前节点 id） */
function openCreateDept(parentId: number | null): void {
  deptDialog.visible = true
  deptDialog.mode = 'create'
  deptDialog.submitting = false
  deptDialog.id = 0
  deptDialog.deptName = ''
  deptDialog.parentId = parentId ?? (selectedDeptId.value ?? null)
  // 新增没有「原始上级」的概念（本来就是空、或由调用处给定）
  deptDialog.originalParentId = null
  deptDialog.sortOrder = 0
  deptDialog.remark = ''
  deptDialog.approvalFlowVersionId = null
}

function openEditDept(node: DepartmentNode): void {
  deptDialog.visible = true
  deptDialog.mode = 'edit'
  deptDialog.submitting = false
  deptDialog.id = node.id
  deptDialog.deptName = node.deptName
  deptDialog.parentId = node.parentId
  // 记下原始上级：保存时据此判断「用户到底改没改层级」
  deptDialog.originalParentId = node.parentId
  deptDialog.sortOrder = node.sortOrder ?? 0
  deptDialog.remark = node.remark ?? ''
  // 只读携带：本页不管理流程绑定，但不回传就会被 PUT 的整体替换语义清成 null（静默解绑）
  deptDialog.approvalFlowVersionId = node.approvalFlowVersionId ?? null
}

async function submitDept(): Promise<void> {
  const deptName = deptDialog.deptName.trim()
  if (!deptName) {
    ElMessage.warning('请填写部门名称')
    return
  }
  deptDialog.submitting = true
  try {
    const payload = {
      deptName,
      parentId: deptDialog.parentId,
      sortOrder: deptDialog.sortOrder,
      remark: deptDialog.remark.trim() || null,
      // 必须回传：PUT 是整体替换语义，漏了它 = 解绑该部门的借用审批流程
      approvalFlowVersionId: deptDialog.approvalFlowVersionId
    }
    if (deptDialog.mode === 'create') {
      await departmentApi.create(payload)
      ElMessage.success('部门已创建')
    } else {
      // ⚠️「上级部门」不在 PUT 的语义里 —— 该接口整体替换的是名称/排序/流程/备注四项，
      // 报文里的 parentId 被后端**忽略**。所以改了上级必须先走 move，
      // 否则会提示「部门已保存」而层级纹丝不动（ 挂账的「静默无效」， 修复）。
      //
      // 顺序：move 在前、update 在后。update 不碰父子关系，因此不会把刚移好的层级顶回去；
      // 反过来（先 update 再 move）在 move 失败时会留下「名字改了、层级没动」这种更难解释的半成品。
      if (deptDialog.parentId !== deptDialog.originalParentId) {
        await departmentApi.move(deptDialog.id, deptDialog.parentId)
      }
      await departmentApi.update(deptDialog.id, payload)
      ElMessage.success('部门已保存')
    }
    deptDialog.visible = false
    await loadDepartments()
    // 组织变了，成员的「部门 / 直属领导」可能整体变化（部门主管联动），一并刷新
    await load()
  } catch {
    // 错误由请求层统一提示。注意 move 与 update 是**两次**请求：move 成功、update 失败时
    // 层级**已经真的变了**，若不刷新，界面会继续显示旧层级（而库里已是新的）——
    // 这正是「界面与库不一致」这类最难复现问题的来源。
    try {
      await loadDepartments()
    } catch {
      /* 忽略：请求层已提示过错误，刷新失败不该把它变成未捕获异常 */
    }
  } finally {
    deptDialog.submitting = false
  }
}

// ------------------------------------------------------------------
// 删除部门
// ------------------------------------------------------------------
/**
 * 删除部门（**仅空部门可删**）。
 *
 * 后端已收紧口径：部门下**仍有成员**时一律拒绝删除。原因是删除会连带清掉该部门的
 * 「部门主管」配置（`department_manager` 行），让原本走「部门主管」审批的工单
 * **静默改道**为超管兜底 —— 一次误点即改写一批人的审批路径。
 * 有子部门时后端同样拒绝（避免整棵子树被静默上移）。
 *
 * 前端在此先行拦截并给出可执行的指引，避免用户点完确认才被拒。
 */
async function handleDeleteDept(node: DepartmentNode): Promise<void> {
  if (node.children && node.children.length > 0) {
    ElMessage.warning(`「${node.deptName}」下还有子部门，请先删除或移走子部门`)
    return
  }
  if (node.memberCount > 0) {
    ElMessage.warning(
      `「${node.deptName}」下还有 ${node.memberCount} 名成员，请先把成员调到其他部门再删除`
    )
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认删除部门「${node.deptName}」？\n· 该部门下没有直属成员。`,
      '删除部门',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消', customStyle: { whiteSpace: 'pre-line' } }
    )
  } catch {
    return
  }
  acting.value = true
  try {
    const result = await departmentApi.remove(node.id)
    ElMessage.success(`部门「${result.deptName}」已删除`)
    clearDeptFilter()
    await loadDepartments()
  } catch {
    // 错误由请求层统一提示
  } finally {
    acting.value = false
  }
}

// ------------------------------------------------------------------
// 设置部门主管
// ------------------------------------------------------------------
const managerDialog = reactive<{
  visible: boolean
  submitting: boolean
  deptId: number
  deptName: string
  userIds: number[]
}>({
  visible: false,
  submitting: false,
  deptId: 0,
  deptName: '',
  userIds: []
})

function openSetManagers(node: DepartmentNode): void {
  managerDialog.visible = true
  managerDialog.submitting = false
  managerDialog.deptId = node.id
  managerDialog.deptName = node.deptName
  managerDialog.userIds = (node.managers ?? []).map((user) => user.id)
}

/** 部门主管候选：复用已加载的员工选项，只保留「在职且启用」的人 */
const managerCandidates = computed(() => leaderOptions.value.filter((user) => user.available))

async function submitManagers(): Promise<void> {
  managerDialog.submitting = true
  try {
    await departmentApi.setManagers(managerDialog.deptId, managerDialog.userIds)
    ElMessage.success(
      managerDialog.userIds.length > 0
        ? '部门主管已更新（未手工指定过直属领导的成员将自动跟随）'
        : '已清空该部门的主管'
    )
    managerDialog.visible = false
    await loadDepartments()
    await load()
  } catch {
    // 错误由请求层统一提示
  } finally {
    managerDialog.submitting = false
  }
}

// ------------------------------------------------------------------
// 批量操作（P3）：批量调整部门
// ------------------------------------------------------------------

const selectedIds = ref<number[]>([])
/** TablePage 暴露的内部表格实例 —— 用于把「保留失败项勾选」同步到界面上 */
const tablePageRef = ref<{ clearSelection?: () => void; tableRef?: TableInstance } | null>(null)
const batchDeptVisible = ref(false)
const batchDeptId = ref<number | null>(null)
const batchResultVisible = ref(false)
const batchResult = ref<BatchResult | null>(null)
const batchSubmitting = ref(false)

function handleSelectionChange(rows: UserAccount[]): void {
  selectedIds.value = rows.map((row) => row.id)
}

function clearSelection(): void {
  selectedIds.value = []
  // 双可选链：未挂载 / 移动端走卡片时可能都不存在
  tablePageRef.value?.clearSelection?.()
}

function openBatchDepartment(): void {
  batchDeptId.value = null
  batchDeptVisible.value = true
}

async function submitBatchDepartment(): Promise<void> {
  if (batchDeptId.value == null) {
    ElMessage.warning('请选择目标部门')
    return
  }
  batchSubmitting.value = true
  try {
    const result = await userApi.batchChangeDepartment(selectedIds.value, batchDeptId.value)
    batchDeptVisible.value = false
    batchResult.value = result
    batchResultVisible.value = true
    // 只保留失败项勾选：用户修正后可直接重试那几个人，不必在名单里重新找
    const failedIds = new Set(result.failures.map((item) => item.id))
    selectedIds.value = selectedIds.value.filter((id) => failedIds.has(id))
    const table = tablePageRef.value?.tableRef
    if (table) {
      records.value.forEach((row) => {
        table.toggleRowSelection?.(row, failedIds.has(row.id))
      })
    }
    await load()
  } finally {
    batchSubmitting.value = false
  }
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await userApi.page({
      page: page.value,
      size: size.value,
      keyword: query.keyword.trim() || undefined,
      dimission: query.dimission,
      role: query.role,
      departmentId: query.departmentId,
      authType: query.authType
    })
    records.value = result.records
    total.value = result.total
  } catch {
    records.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleSearch(): void {
  page.value = 1
  void load()
}

function handleReset(): void {
  query.keyword = ''
  query.dimission = null
  query.role = null
  query.departmentId = null
  query.authType = null
  page.value = 1
  void load()
}

function handlePageChange(next: number): void {
  page.value = next
  void load()
}

function handleSizeChange(next: number): void {
  size.value = next
  page.value = 1
  void load()
}

function departmentText(row: UserAccount): string {
  return row.departmentName ?? '-'
}

function nameText(row: UserAccount): string {
  return row.realName?.trim() || row.displayName || row.username
}

/** 账号来源标签色：AD 用 warning 以区别于本地（本地为默认 plain） */
function authTypeTagType(row: UserAccount): 'warning' | 'info' {
  return row.authType === 'LDAP' ? 'warning' : 'info'
}

// ------------------------------------------------------------------
// 写操作入口显隐
// ------------------------------------------------------------------
/**
 * 标记离职：仅超管可操作（）。
 *
 * 两条禁止：
 * - **任何超管账号**（内置超管与其他超管）都不给入口 —— 后端 `USER_SUPER_ADMIN_PROTECTED` 同样拦截；
 * - **自己那行**不给入口（）。对超管而言这条当前恒真（超管行已被上一条排除），
 *   但显式写出可防将来把入口放开给 admin 时误伤自己 —— 那会把自己直接锁在系统外，
 *   且只能靠改库救回。
 */
function canMarkDimission(row: UserAccount): boolean {
  return isSuperAdmin.value && !row.dimission && row.role !== 'super_admin' && row.id !== currentUserId.value
}

function canReinstate(row: UserAccount): boolean {
  return isSuperAdmin.value && row.dimission
}

/** 启用/禁用：仅超管可操作；对任何超管账号（含自己那行）都不做禁用（ / 二.3） */
function canToggleEnable(row: UserAccount): boolean {
  return isSuperAdmin.value && row.role !== 'super_admin' && row.id !== currentUserId.value
}

function canEdit(row: UserAccount): boolean {
  // 2026-09-20 ：内置超管（roleLocked=true，即账号 administrator）仍不可编辑；
  // 但「非 administrator 的超管」须放开编辑入口 —— 否则无法把某个离岗超管降级为普通角色。
  return isSuperAdmin.value && !row.roleLocked
}

/**
 * 重置密码（）。
 *
 * 按「当前登录者是谁」分两档：
 * - **内置超管（administrator）**：所有账号都可重置，**含其他超管**。
 *   改造前这条路径被后端完全封死（超管口令不可被重置），后果是其他超管一旦忘记口令即成死局：
 *   进不去系统 → 无法自助改密；也没有任何人能替他重置。唯一的出路是手工改库。
 * - **其他超管**：只能重置非超管账号；对超管（含 administrator）不给入口 ——
 *   否则任一超管都能顶掉最高管理员的口令，「最高」就不再存在了。
 *
 * AD 域账号照旧一律不可（）：域口令只存在于域控，本地重置出的口令永远不会被用作凭据，
 * 放行只会让管理员以为「已经重置好了」，属典型的静默无效操作。
 */
function canResetPassword(row: UserAccount): boolean {
  if (!isSuperAdmin.value || isLdapAccount(row)) {
    return false
  }
  return userStore.builtInAdmin || row.role !== 'super_admin'
}

function isLdapAccount(row: UserAccount): boolean {
  return row.authType === 'LDAP'
}

/** 账号来源互转：超管、非超管账号（超管固定本地认证） */
function canConvert(row: UserAccount): boolean {
  return isSuperAdmin.value && row.role !== 'super_admin'
}

// ------------------------------------------------------------------
// 离职 / 恢复在职
// ------------------------------------------------------------------
function dimissionConfirmText(row: UserAccount): string {
  const name = nameText(row)
  const parts: string[] = []
  parts.push(`确认将「${name}」标记为离职？`)
  if (row.heldDeviceCount > 0) {
    parts.push(
      `该员工名下现有 ${row.heldDeviceCount} 台「使用中」设备，标记后将自动转入「待收回」并通知对应实际执行人回收。`
    )
  } else {
    parts.push('该员工名下没有「使用中」的设备，无需回收。')
  }
  parts.push('账号将被禁用、无法登录，且不能再提交 / 审批 / 交付 / 归还。')
  if (row.pendingApprovalCount > 0) {
    parts.push(
      `注意：仍有 ${row.pendingApprovalCount} 个在途审批节点待其处理，离职后不会自动改派，请事后手工处理。`
    )
  }
  parts.push('如需撤销，可在本页「恢复在职」。')
  return parts.join('\n')
}

async function handleMarkDimission(row: UserAccount): Promise<void> {
  try {
    await ElMessageBox.confirm(dimissionConfirmText(row), '标记离职', {
      type: 'warning',
      confirmButtonText: '确认标记离职',
      cancelButtonText: '取消',
      customStyle: { whiteSpace: 'pre-line' }
    })
  } catch {
    return
  }
  acting.value = true
  try {
    const result = await userApi.markDimission(row.id)
    const detail = result.orderNos.length > 0 ? `\n已转入待收回的工单：${result.orderNos.join('、')}` : ''
    await ElMessageBox.alert(`${result.message}${detail}`, '操作完成', {
      type: 'success',
      confirmButtonText: '知道了',
      customStyle: { whiteSpace: 'pre-line' }
    })
    await load()
  } catch {
    // 错误由请求层统一提示
  } finally {
    acting.value = false
  }
}

async function handleReinstate(row: UserAccount): Promise<void> {
  const name = nameText(row)
  try {
    await ElMessageBox.confirm(
      `确认将「${name}」恢复在职？账号将重新启用、可正常登录；此前已回收的设备需重新提交借用申请，未完成的归还流程不会回退。`,
      '恢复在职',
      { type: 'info', confirmButtonText: '确认恢复在职', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  acting.value = true
  try {
    const result = await userApi.reinstate(row.id)
    ElMessage.success(result.message)
    await load()
  } catch {
    // 错误由请求层统一提示
  } finally {
    acting.value = false
  }
}

// ------------------------------------------------------------------
// 启用 / 禁用
// ------------------------------------------------------------------
async function handleToggleEnable(row: UserAccount): Promise<void> {
  const willDisable = row.enabled
  const label = willDisable ? '禁用' : '启用'
  const name = nameText(row)
  try {
    await ElMessageBox.confirm(
      `确认${label}「${name}」的账号？${willDisable ? '禁用后该员工将无法登录系统。' : '启用后该员工可正常登录。'}`,
      label,
      { type: 'warning', confirmButtonText: `确认${label}`, cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  acting.value = true
  try {
    if (willDisable) {
      await userApi.disable(row.id)
    } else {
      await userApi.enable(row.id)
    }
    ElMessage.success(`已${label}`)
    await load()
  } catch {
    // 错误由请求层统一提示
  } finally {
    acting.value = false
  }
}

// ------------------------------------------------------------------
// 新增 / 编辑
// ------------------------------------------------------------------
function openCreate(): void {
  formDialog.visible = true
  formDialog.mode = 'create'
  formDialog.submitting = false
  formDialog.id = 0
  formDialog.realName = ''
  formDialog.username = ''
  formDialog.password = ''
  formDialog.departmentId = null
  formDialog.role = 'user'
  formDialog.displayName = ''
  formDialog.leaderId = null
  formDialog.ldapAccount = false
  formDialog.roleLocked = false
  formDialog.phone = ''
  formDialog.email = ''
  // 新增员工时允许直接填联系方式：此时并没有「别人的联系方式」被改动，
  // 后端 insertUser 也不设内置超管门槛（与 updateUser 的口径刻意不同）。
  formDialog.contactEditable = true
}

function openEdit(row: UserAccount): void {
  formDialog.visible = true
  formDialog.mode = 'edit'
  formDialog.submitting = false
  formDialog.id = row.id
  formDialog.realName = row.realName?.trim() || ''
  formDialog.username = row.username
  formDialog.password = ''
  formDialog.departmentId = row.departmentId
  formDialog.role = row.role
  formDialog.displayName = row.displayName
  formDialog.leaderId = row.leaderId ?? null
  formDialog.ldapAccount = isLdapAccount(row)
  formDialog.roleLocked = Boolean(row.roleLocked)
  formDialog.phone = row.phone ?? ''
  formDialog.email = row.email ?? ''
  // ：只有内置超管能改**他人**的手机号与邮箱。
  // 判据取 store 的 builtInAdmin（由后端下发），不在前端自己推导角色 ——
  // 否则「其他超管」会看到一个可编辑但保存无效的输入框。
  formDialog.contactEditable = userStore.builtInAdmin
}

/** 姓名：纯中文 2~20 字（与后端 AccountFormats.CHINESE_NAME 同规则） */
const REAL_NAME_PATTERN = /^[\u4e00-\u9fa5]{2,20}$/
/** 登录名：5 位以上纯数字（与后端 AccountFormats.USERNAME 同规则） */
const USERNAME_PATTERN = /^[0-9]{5,}$/
const PHONE_PATTERN = /^1[3-9]\d{9}$/
const EMAIL_PATTERN = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/

async function submitForm(): Promise<void> {
  const realName = formDialog.realName.trim()
  if (!realName) {
    ElMessage.warning('请填写姓名')
    return
  }
  // 前端先按同一规则拦一次，用户才不会「填完一整张表再被后端打回」。
  // 规则与后端 AccountFormats 逐字对应，改一处必须改两处（后端仍是事实源）。
  if (!REAL_NAME_PATTERN.test(realName)) {
    ElMessage.warning('姓名必须是纯中文（2~20 个字）')
    return
  }
  if (formDialog.mode === 'create') {
    const username = formDialog.username.trim()
    if (!username) {
      ElMessage.warning('请填写登录名')
      return
    }
    if (!USERNAME_PATTERN.test(username)) {
      ElMessage.warning('登录名必须是5位以上纯数字，如 10001')
      return
    }
  }
  const phone = formDialog.phone.trim()
  const email = formDialog.email.trim()
  // 置灰时字段保持原值、不参与提交，因此只在可编辑时才校验
  if (formDialog.contactEditable && phone && !PHONE_PATTERN.test(phone)) {
    ElMessage.warning('手机号格式不正确，请输入 11 位手机号')
    return
  }
  if (formDialog.contactEditable && email && !EMAIL_PATTERN.test(email)) {
    ElMessage.warning('邮箱格式不正确')
    return
  }
  formDialog.submitting = true
  try {
    if (formDialog.mode === 'create') {
      await userApi.create({
        realName,
        username: formDialog.username.trim(),
        password: formDialog.password,
        departmentId: formDialog.departmentId,
        role: formDialog.role,
        displayName: formDialog.displayName.trim(),
        leaderId: formDialog.leaderId,
        phone: phone || null,
        email: email || null
      })
      ElMessage.success('新增员工成功')
    } else {
      const payload: UserUpdatePayload = {
        realName,
        departmentId: formDialog.departmentId,
        role: formDialog.role,
        displayName: formDialog.displayName.trim(),
        leaderId: formDialog.leaderId
      }
      // 只在可编辑时提交联系方式：其他角色传了也会被后端忽略，
      // 但「根本不传」更能表达意图，也不会在将来后端放宽判定时被意外利用。
      if (formDialog.contactEditable) {
        payload.phone = phone || null
        payload.email = email || null
      }
      await userApi.update(formDialog.id, payload)
      ElMessage.success('保存成功')
    }
    formDialog.visible = false
    await load()
  } catch {
    // 错误由请求层统一提示
  } finally {
    formDialog.submitting = false
  }
}

// ------------------------------------------------------------------
// 重置密码
// ------------------------------------------------------------------
async function handleResetPassword(row: UserAccount): Promise<void> {
  const name = nameText(row)
  try {
    await ElMessageBox.confirm(
      `确认重置「${name}」的密码？\n` +
        '· 系统会生成一个随机临时密码，该密码只显示一次，请务必复制后转交给他；\n' +
        '· 重置后他的全部登录会话会立即失效；\n' +
        '· 他下次登录时必须修改密码。',
      '重置密码',
      {
        type: 'warning',
        confirmButtonText: '确认重置',
        cancelButtonText: '取消',
        customStyle: { whiteSpace: 'pre-line' }
      }
    )
  } catch {
    return
  }
  acting.value = true
  try {
    // 2026-09-20 ：口令由服务端生成并回传，前端负责展示与复制。
    // 不再像改造前那样传一个写死的默认口令（管理员看不到、也无从告知员工，
    // 属「点完按钮什么也没发生」的静默失效）。
    const result = await userApi.resetPassword(row.id)
    await showResetResult(name, result.temporaryPassword)
  } catch {
    // 错误由请求层统一提示
  } finally {
    acting.value = false
  }
}

/**
 * 复制临时口令到剪贴板（2026-09-20 ）
 *
 * 成功与失败都要给反馈：失败时管理员必须知道「得手动选中复制」，
 * 否则他会以为已经复制好，把一份空剪贴板粘给员工。
 */
async function handleCopyPassword(text: string): Promise<void> {
  const copied = await copyText(text)
  if (copied) {
    ElMessage.success('临时密码已复制到剪贴板')
  } else {
    ElMessage.warning('自动复制失败，请手动选中上面的临时密码后复制')
  }
}

/**
 * 展示系统生成的临时口令（2026-09-20 ）
 *
 * 三个刻意的约束：
 * - 弹窗**不可**用 Esc / 点遮罩关闭（`closeOnClickModal: false` / `closeOnPressEscape: false`）：
 *   口令只在这一次响应里返回，误关就再也拿不到，只能再重置一次；
 * - 提供「复制」按钮而不是让管理员手抄：随机串含特殊字符，抄错一位员工就登不进去；
 * - 结构用原生元素 + 内联样式：ElMessageBox 的内容挂载在 `body` 下，组件的
 *   `<style scoped>` 触及不到它；而本项目 Element Plus 走按需引入，从 'element-plus'
 *   显式 import 组件或单独引其样式都会破坏 tree-shaking（vite.config.ts 有说明）。
 *   这里只有几行元素，内联样式是最省代价且不会污染全局的做法。
 */
async function showResetResult(name: string, tempPassword: string): Promise<void> {
  const styles = {
    intro: { margin: '0 0 12px', lineHeight: '1.6' } as const,
    label: { margin: '0 0 6px', color: 'var(--ts-text-hint)', fontSize: '13px' } as const,
    row: { display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '12px' } as const,
    value: {
      flex: '1 1 auto',
      minWidth: '0',
      padding: '8px 10px',
      border: '1px solid var(--el-border-color)',
      borderRadius: '4px',
      background: 'var(--el-fill-color-light)',
      fontFamily: 'Consolas, Monaco, monospace',
      fontSize: '16px',
      letterSpacing: '1px',
      wordBreak: 'break-all',
      userSelect: 'all'
    } as const,
    copy: {
      flex: '0 0 auto',
      padding: '8px 14px',
      border: '1px solid var(--el-color-primary)',
      borderRadius: '4px',
      background: 'transparent',
      color: 'var(--el-color-primary)',
      fontSize: '13px',
      cursor: 'pointer'
    } as const,
    hint: { margin: '0', color: '#d9480f', fontSize: '12px', lineHeight: '1.6' } as const
  }

  try {
    await ElMessageBox.alert(
      h('div', {}, [
        h('p', { style: styles.intro }, `「${name}」的密码已重置，其全部旧登录会话已失效。`),
        h('p', { style: styles.label }, '临时密码（仅显示这一次）：'),
        h('div', { style: styles.row }, [
          h('code', { style: styles.value }, tempPassword),
          h(
            'button',
            {
              type: 'button',
              style: styles.copy,
              onClick: () => {
                void handleCopyPassword(tempPassword)
              }
            },
            '复制'
          )
        ]),
        h(
          'p',
          { style: styles.hint },
          '请立即复制并转交该员工；他首次登录时会被要求修改密码。关闭本窗口后将无法再次查看。'
        )
      ]),
      '密码已重置',
      {
        type: 'warning',
        confirmButtonText: '我已记录',
        closeOnClickModal: false,
        closeOnPressEscape: false,
        showClose: false
      }
    )
  } catch {
    // alert 只有「确认」一个按钮，正常不会走到这里
  }
}

// ------------------------------------------------------------------
// 账号来源互转（ ）
// ------------------------------------------------------------------

/** 把后端返回的一次性临时口令显式展示出来（必须阻塞关闭，否则口令就丢了） */
async function showConvertResult(result: AdAccountConvertVO): Promise<void> {
  const lines: string[] = [result.message]
  if (result.temporaryPassword) {
    lines.push('')
    lines.push(`临时密码：${result.temporaryPassword}`)
    lines.push('（此密码只显示这一次，请立即复制并转交给该员工；他首次登录后必须修改）')
  }
  await ElMessageBox.alert(lines.join('\n'), '转换完成', {
    type: result.temporaryPassword ? 'warning' : 'success',
    confirmButtonText: '我已记录',
    customStyle: { whiteSpace: 'pre-line' },
    // 临时口令不可被 Esc / 点遮罩关闭：误关就再也拿不到
    closeOnClickModal: !result.temporaryPassword,
    closeOnPressEscape: !result.temporaryPassword,
    showClose: !result.temporaryPassword
  })
}

async function handleToLocal(row: UserAccount): Promise<void> {
  const name = nameText(row)
  try {
    await ElMessageBox.confirm(
      `确认把「${name}」从 AD 域账号转为本地账号？\n` +
        '转换后：系统会生成一次性临时密码（仅显示一次），该员工改用本地密码登录且首登必须改密；' +
        '其既有登录会话全部失效。\n' +
        '注意：转为本地后，该账号不再受 AD 域控状态影响 —— 若域控侧发生禁用或删除，需在本页手工禁用。',
      '转为本地账号',
      { type: 'warning', confirmButtonText: '确认转换', cancelButtonText: '取消', customStyle: { whiteSpace: 'pre-line' } }
    )
  } catch {
    return
  }
  acting.value = true
  try {
    const result = await adApi.convertToLocal(row.id)
    await showConvertResult(result)
    await load()
  } catch {
    // 错误由请求层统一提示
  } finally {
    acting.value = false
  }
}

async function handleToLdap(row: UserAccount): Promise<void> {
  const name = nameText(row)
  try {
    await ElMessageBox.confirm(
      `确认把「${name}」从本地账号转为 AD 域账号？\n` +
        '转换前系统会先在域控中确认存在同名账号（不存在则拒绝转换、不做任何修改）。\n' +
        '转换后：原本地密码立即失效，该员工改用域口令登录，其既有登录会话全部失效。',
      '转为 AD 账号',
      { type: 'warning', confirmButtonText: '确认转换', cancelButtonText: '取消', customStyle: { whiteSpace: 'pre-line' } }
    )
  } catch {
    return
  }
  acting.value = true
  try {
    const result = await adApi.convertToLdap(row.id)
    await showConvertResult(result)
    await load()
  } catch {
    // 错误由请求层统一提示
  } finally {
    acting.value = false
  }
}
</script>

<template>
  <div class="ts-page">
    <!-- ：本页合并了原「员工档案 / 部门管理 / 最终处理部门管理」三页，
         布局改为钉钉通讯录式的「左部门树 + 右成员列表」。 -->
    <div class="ts-org">
      <!-- ============ 左：部门树 ============ -->
      <aside v-show="deptAsideVisible" v-loading="deptLoading" class="ts-card ts-org__aside">
        <div class="ts-flex-between ts-org__aside-head">
          <h3 class="ts-org__aside-title">部门</h3>
          <el-button v-if="isSuperAdmin" link type="primary" @click="openCreateDept(null)">
            新增部门
          </el-button>
        </div>
        <!-- 部门搜索：部门一多，靠展开/折叠找比滚动还慢 -->
        <el-input
          v-model="deptKeyword"
          class="ts-org__search"
          size="small"
          clearable
          placeholder="搜索部门"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <!-- 「全部成员」是一个显式入口而不是把根节点当默认：
             根节点（公司）是有实体的部门，点它应当看「公司这个部门的人」，
             而「看所有人」是另一件事。混在一起会让根节点的人数与列表内容对不上。 -->
        <div
          class="ts-org__all"
          :class="{ 'is-active': selectedDeptId === null }"
          @click="clearDeptFilter"
        >
          <span>全部成员</span>
        </div>
        <el-tree
          ref="deptTreeRef"
          v-if="deptTree.length > 0"
          :data="deptTree"
          node-key="id"
          :props="{ label: 'deptName', children: 'children' }"
          highlight-current
          default-expand-all
          :expand-on-click-node="false"
          :draggable="isSuperAdmin"
          :allow-drag="allowDragDept"
          :allow-drop="allowDropDept"
          :filter-node-method="filterDeptNode"
          class="ts-org__tree"
          @node-click="handleDeptSelect"
          @node-drop="handleDeptDrop"
        >
          <template #default="{ data }">
            <div class="ts-org__node">
              <span class="ts-org__node-name">{{ data.deptName }}</span>
              <span v-if="data.handlerGroup" class="ts-org__node-badge">最终处理</span>
              <span class="ts-org__node-count">{{ data.totalMemberCount }}</span>
              <!-- 悬停才出现的操作区（PC）；手机端常显 —— 触屏没有 hover，
                   藏起来等于这些能力在手机上不存在。 -->
              <span v-if="isSuperAdmin" class="ts-org__node-actions">
                <el-button
                  link
                  size="small"
                  title="编辑部门"
                  @click.stop="openEditDept(data as DepartmentNode)"
                >
                  <el-icon><Edit /></el-icon>
                </el-button>
                <el-button
                  link
                  size="small"
                  title="设置部门主管"
                  @click.stop="openSetManagers(data as DepartmentNode)"
                >
                  <el-icon><UserFilled /></el-icon>
                </el-button>
                <el-button
                  link
                  size="small"
                  title="添加子部门"
                  @click.stop="openCreateDept(data.id)"
                >
                  <el-icon><Plus /></el-icon>
                </el-button>
                <el-button
                  link
                  size="small"
                  title="删除部门"
                  @click.stop="handleDeleteDept(data as DepartmentNode)"
                >
                  <el-icon><Delete /></el-icon>
                </el-button>
              </span>
            </div>
          </template>
        </el-tree>
        <el-empty v-else-if="!deptLoading" :image-size="60" description="暂无部门数据" />
        <!-- 拖拽没有可见抓手，必须给一句说明 —— 否则这个能力等于不存在 -->
        <p v-if="isSuperAdmin && deptTree.length > 0" class="ts-text-hint ts-org__drag-hint">
          拖拽部门可调整层级（放到部门上 = 成为其下级）。
        </p>
      </aside>

      <!-- ============ 右：成员列表 ============ -->
      <section class="ts-card ts-org__main">
      <div class="ts-flex-between ts-staff__head">
        <div>
          <h3 class="ts-staff__title">
            组织与人员
            <span v-if="selectedDept" class="ts-org__scope">{{ selectedDept.deptName }}</span>
          </h3>
          <p class="ts-text-secondary ts-staff__desc">
            点左侧部门可只看该部门成员，再点一次（或点「全部成员」）恢复全部。
            仅超级管理员可写；标记离职会将其名下「使用中」设备自动转入「待收回」并通知对应执行人，同时禁用账号。
          </p>
        </div>
        <div class="ts-flex-between ts-staff__head-actions">
          <el-button class="ts-org__aside-toggle" @click="deptAsideVisible = !deptAsideVisible">
            {{ deptAsideVisible ? '隐藏部门' : '显示部门' }}
          </el-button>
          <el-button v-if="isSuperAdmin" type="primary" @click="openCreate">添加成员</el-button>
          <el-button v-if="isSuperAdmin" @click="importVisible = true">批量导入</el-button>
          <el-button :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>

      <!-- 筛选条件 -->
      <div class="ts-staff__filters ts-mt-16">
        <el-input
          v-model="query.keyword"
          class="ts-staff__filter-keyword"
          placeholder="姓名 / 登录名"
          clearable
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-select v-model="query.dimission" class="ts-staff__filter-item" placeholder="在职状态" clearable>
          <el-option label="在职" :value="false" />
          <el-option label="离职" :value="true" />
        </el-select>
        <el-select v-model="query.role" class="ts-staff__filter-item" placeholder="角色" clearable>
          <el-option v-for="item in roleOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <!-- 账号来源筛选（ ） -->
        <el-select v-model="query.authType" class="ts-staff__filter-item" placeholder="账号来源" clearable>
          <el-option v-for="item in AUTH_TYPE_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <!-- 部门筛选由左侧部门树驱动：
             这里不再放第二个「部门下拉」—— 两份入口会让「我到底筛了哪个部门」
             出现两个事实源，树选 A、下拉选 B 时列表该听谁的没有答案。
             仅在已选部门时显示一个可关闭的标签，作为「当前筛选状态」的回显。 -->
        <el-tag
          v-if="selectedDept"
          class="ts-org__filter-tag"
          type="primary"
          effect="plain"
          closable
          @close="clearDeptFilter"
        >
          {{ selectedDept.deptName }}
        </el-tag>
        <div class="ts-staff__filter-actions">
          <el-button type="primary" :loading="loading" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </div>
      </div>

      <!-- 批量操作条（P3）：只在有勾选时出现 -->
      <div v-if="isSuperAdmin && selectedIds.length > 0" class="ts-staff__bulk ts-mt-16">
        <span class="ts-text-hint">已选 {{ selectedIds.length }} 人</span>
        <el-button type="primary" size="small" :loading="batchSubmitting" @click="openBatchDepartment">
          批量调整部门
        </el-button>
        <el-button size="small" @click="clearSelection">取消选择</el-button>
      </div>

      <TablePage
        ref="tablePageRef"
        :columns="columns"
        :rows="records"
        :loading="loading"
        route-path="/staff/organization"
        :page="page"
        :size="size"
        :total="total"
        empty-text="暂无符合条件的员工"
        class="ts-mt-16"
        @update:page="handlePageChange"
        @update:size="handleSizeChange"
        @selection-change="handleSelectionChange"
      >
        <template #cell-realName="{ row }">
          <div>{{ nameText(row as UserAccount) }}</div>
          <div class="ts-text-hint">{{ (row as UserAccount).username }}</div>
        </template>
        <template #cell-authTypeLabel="{ row }">
          <el-tag :type="authTypeTagType(row as UserAccount)" size="small" effect="plain">
            {{ (row as UserAccount).authTypeLabel }}
          </el-tag>
        </template>
        <template #cell-departmentName="{ row }">{{ departmentText(row as UserAccount) }}</template>
        <!-- ：直属领导。三种状态必须分开说 —— 未配置 / 领导账号已失效 / 有名字，
             混成「-」会让人以为功能没做，实际是数据没齐 -->
        <template #cell-leaderName="{ row }">
          <span v-if="(row as UserAccount).leaderName">{{ (row as UserAccount).leaderName }}</span>
          <span v-else-if="(row as UserAccount).leaderId != null" class="ts-text-hint">账号已失效</span>
          <span v-else class="ts-text-hint">未配置</span>
        </template>
        <template #cell-roleLabel="{ row }">
          <el-tag size="small" effect="plain">{{ (row as UserAccount).roleLabel }}</el-tag>
        </template>
        <template #cell-heldDeviceCount="{ row }">
          <span :class="{ 'ts-staff__emph': (row as UserAccount).heldDeviceCount > 0 }">
            {{ (row as UserAccount).heldDeviceCount }}
          </span>
        </template>
        <template #cell-pendingApprovalCount="{ row }">
          <el-tag
            v-if="(row as UserAccount).pendingApprovalCount > 0"
            type="warning"
            size="small"
            effect="plain"
          >
            {{ (row as UserAccount).pendingApprovalCount }}
          </el-tag>
          <span v-else class="ts-text-hint">0</span>
        </template>
        <template #cell-status="{ row }">
          <el-tag :type="(row as UserAccount).dimission ? 'info' : 'success'" size="small" effect="plain">
            {{ (row as UserAccount).dimission ? '离职' : '在职' }}
          </el-tag>
          <el-tag
            :type="(row as UserAccount).enabled ? 'success' : 'danger'"
            size="small"
            effect="plain"
            style="margin-left: 4px"
          >
            {{ (row as UserAccount).enabled ? '启用' : '禁用' }}
          </el-tag>
          <div v-if="(row as UserAccount).dimissionAt" class="ts-text-hint">
            {{ (row as UserAccount).dimissionAt }}
          </div>
        </template>
        <template #cell-action="{ row }">
          <el-button
            v-if="canEdit(row as UserAccount)"
            link
            type="primary"
            size="small"
            :loading="acting"
            @click="openEdit(row as UserAccount)"
          >
            编辑
          </el-button>

          <!-- 重置密码：AD 域账号置灰并给出原因（） -->
          <el-button
            v-if="canResetPassword(row as UserAccount)"
            link
            type="warning"
            size="small"
            :loading="acting"
            @click="handleResetPassword(row as UserAccount)"
          >
            重置密码
          </el-button>
          <el-tooltip
            v-else-if="isLdapAccount(row as UserAccount) && isSuperAdmin"
            content="域账号请在 AD 域控中重置密码"
            placement="top"
          >
            <span class="ts-staff__disabled-action">重置密码</span>
          </el-tooltip>

          <!-- 账号来源互转（） -->
          <el-button
            v-if="canConvert(row as UserAccount) && isLdapAccount(row as UserAccount)"
            link
            type="info"
            size="small"
            :loading="acting"
            @click="handleToLocal(row as UserAccount)"
          >
            转为本地账号
          </el-button>
          <el-button
            v-else-if="canConvert(row as UserAccount)"
            link
            type="info"
            size="small"
            :loading="acting"
            @click="handleToLdap(row as UserAccount)"
          >
            转为 AD 账号
          </el-button>

          <el-button
            v-if="canToggleEnable(row as UserAccount)"
            link
            :type="(row as UserAccount).enabled ? 'danger' : 'success'"
            size="small"
            :loading="acting"
            @click="handleToggleEnable(row as UserAccount)"
          >
            {{ (row as UserAccount).enabled ? '禁用' : '启用' }}
          </el-button>
          <el-button
            v-if="canMarkDimission(row as UserAccount)"
            link
            type="danger"
            size="small"
            :loading="acting"
            @click="handleMarkDimission(row as UserAccount)"
          >
            标记离职
          </el-button>
          <el-button
            v-if="canReinstate(row as UserAccount)"
            link
            type="primary"
            size="small"
            :loading="acting"
            @click="handleReinstate(row as UserAccount)"
          >
            恢复在职
          </el-button>
        </template>

      <!-- 移动端卡片保留改造前结构（含 PC 表上没有的「邮箱 / 部门」两行） -->
      <template #mobile>
        <div v-loading="loading" class="ts-staff__cards">
          <div v-for="row in records" :key="row.id" class="ts-staff__card">
            <div class="ts-flex-between">
              <strong class="ts-staff__card-title">{{ nameText(row) }}</strong>
              <div>
                <el-tag :type="authTypeTagType(row)" size="small" effect="plain">
                  {{ row.authTypeLabel }}
                </el-tag>
                <el-tag
                  :type="row.dimission ? 'info' : 'success'"
                  size="small"
                  effect="plain"
                  style="margin-left: 4px"
                >
                  {{ row.dimission ? '离职' : '在职' }}
                </el-tag>
                <el-tag
                  :type="row.enabled ? 'success' : 'danger'"
                  size="small"
                  effect="plain"
                  style="margin-left: 4px"
                >
                  {{ row.enabled ? '启用' : '禁用' }}
                </el-tag>
              </div>
            </div>
            <div class="ts-staff__card-row">
              <span class="ts-text-hint">登录名</span>
              <span class="ts-staff__card-value">{{ row.username }}</span>
            </div>
            <div v-if="row.email" class="ts-staff__card-row">
              <span class="ts-text-hint">邮箱</span>
              <span class="ts-staff__card-value">{{ row.email }}</span>
            </div>
            <div v-if="row.department" class="ts-staff__card-row">
              <span class="ts-text-hint">部门</span>
              <span class="ts-staff__card-value">{{ row.department }}</span>
            </div>
            <div class="ts-staff__card-row">
              <span class="ts-text-hint">部门</span>
              <span>{{ departmentText(row) }}</span>
            </div>
            <div class="ts-staff__card-row">
              <span class="ts-text-hint">直属领导</span>
              <span v-if="row.leaderName">{{ row.leaderName }}</span>
              <span v-else-if="row.leaderId != null" class="ts-text-hint">账号已失效</span>
              <span v-else class="ts-text-hint">未配置</span>
            </div>
            <div class="ts-staff__card-row">
              <span class="ts-text-hint">角色</span>
              <el-tag size="small" effect="plain">{{ row.roleLabel }}</el-tag>
            </div>
            <div class="ts-staff__card-row">
              <span class="ts-text-hint">名下设备</span>
              <span>{{ row.heldDeviceCount }} 台</span>
            </div>
            <div class="ts-staff__card-row">
              <span class="ts-text-hint">在途审批</span>
              <span>{{ row.pendingApprovalCount }} 个</span>
            </div>
            <div v-if="row.dimissionAt" class="ts-staff__card-row">
              <span class="ts-text-hint">离职时间</span>
              <span>{{ row.dimissionAt }}</span>
            </div>
            <div v-if="isSuperAdmin" class="ts-staff__card-actions">
              <el-button
                v-if="canEdit(row)"
                size="small"
                type="primary"
                plain
                :loading="acting"
                @click="openEdit(row)"
              >
                编辑
              </el-button>
              <el-button
                v-if="canResetPassword(row)"
                size="small"
                type="warning"
                plain
                :loading="acting"
                @click="handleResetPassword(row)"
              >
                重置密码
              </el-button>
              <!-- AD 域账号：置灰并说明原因（）。
                   移动端没有 hover，因此除了 tooltip，按钮文案本身也带上「域账号」字样，
                   保证「为什么点不动」在任何交互方式下都能看到。 -->
              <el-tooltip
                v-else-if="isLdapAccount(row)"
                content="域账号请在 AD 域控中重置密码"
                placement="top"
              >
                <span class="ts-staff__card-disabled">
                  <el-button size="small" plain disabled>重置密码（域账号）</el-button>
                </span>
              </el-tooltip>
              <el-button
                v-if="canConvert(row) && isLdapAccount(row)"
                size="small"
                type="info"
                plain
                :loading="acting"
                @click="handleToLocal(row)"
              >
                转为本地账号
              </el-button>
              <el-button
                v-else-if="canConvert(row)"
                size="small"
                type="info"
                plain
                :loading="acting"
                @click="handleToLdap(row)"
              >
                转为 AD 账号
              </el-button>
              <el-button
                v-if="canToggleEnable(row)"
                size="small"
                :type="row.enabled ? 'danger' : 'success'"
                plain
                :loading="acting"
                @click="handleToggleEnable(row)"
              >
                {{ row.enabled ? '禁用' : '启用' }}
              </el-button>
              <el-button
                v-if="canMarkDimission(row)"
                size="small"
                type="danger"
                plain
                :loading="acting"
                @click="handleMarkDimission(row)"
              >
                标记离职
              </el-button>
              <el-button
                v-if="canReinstate(row)"
                size="small"
                type="primary"
                plain
                :loading="acting"
                @click="handleReinstate(row)"
              >
                恢复在职
              </el-button>
            </div>
          </div>
          <el-empty v-if="!loading && records.length === 0" :image-size="70" description="暂无符合条件的员工" />
        </div>
      </template>
      </TablePage>
      </section>
    </div>

    <!-- 新增 / 编辑弹窗 -->
    <el-dialog
      v-model="formDialog.visible"
      :title="formDialog.mode === 'create' ? '新增员工' : '编辑员工'"
      :width="isMobile ? '96%' : '520px'"
      append-to-body
    >
      <el-form label-width="92px" :model="formDialog">
        <el-form-item label="姓名" required>
          <el-input v-model="formDialog.realName" placeholder="请输入真实姓名（纯中文）" maxlength="20" />
        </el-form-item>
        <el-form-item label="登录名" required>
          <el-input
            v-model="formDialog.username"
            placeholder="5位以上纯数字，如 10001"
            maxlength="20"
            :disabled="formDialog.mode === 'edit'"
          />
          <p class="ts-text-hint">
            {{ formDialog.mode === 'edit' ? '登录名新增后不可修改' : '5 位以上纯数字，新增后不可修改' }}
          </p>
        </el-form-item>
        <el-form-item v-if="formDialog.mode === 'create'" label="初始密码">
          <el-input v-model="formDialog.password" placeholder="请设置初始密码" maxlength="30" />
          <p class="ts-text-hint">由你设置并转告员工；新员工首次登录强制改密</p>
        </el-form-item>
        <!-- 编辑态展示「密码」字段：AD 域账号置灰并说明原因（） -->
        <el-form-item v-else label="密码">
          <el-input
            :model-value="''"
            disabled
            :placeholder="formDialog.ldapAccount ? '域账号请在 AD 域控中修改密码' : '请使用右侧「重置密码」操作'"
          />
          <p class="ts-text-hint">
            {{
              formDialog.ldapAccount
                ? '该账号来自 AD 域控，密码由域控统一管理，本系统不支持修改'
                : '如需修改该员工密码，请在列表中点击「重置密码」'
            }}
          </p>
        </el-form-item>
        <el-form-item label="部门">
          <el-select v-model="formDialog.departmentId" placeholder="请选择部门" clearable style="width: 100%">
            <el-option
              v-for="item in deptOptions"
              :key="item.id"
              :label="item.displayPath"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="formDialog.role" :disabled="formDialog.roleLocked" style="width: 100%">
            <el-option v-for="item in roleOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
          <p v-if="formDialog.roleLocked" class="ts-text-hint">
            内置超级管理员账号的角色不可更改（后端同样拦截）
          </p>
        </el-form-item>
        <el-form-item label="显示名称">
          <el-input v-model="formDialog.displayName" placeholder="默认同姓名，可留空" maxlength="30" />
        </el-form-item>
        <!-- 联系方式（上线前）：仅内置超管可修改**他人**的手机号与邮箱。
             置灰而不是隐藏 —— 「看不见」会让人以为字段不存在而四处找，
             「看得见但改不了」才把规则说清楚了。 -->
        <el-form-item label="手机号">
          <el-input
            v-model="formDialog.phone"
            :disabled="!formDialog.contactEditable"
            maxlength="20"
            placeholder="选填，11 位手机号"
          />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input
            v-model="formDialog.email"
            :disabled="!formDialog.contactEditable"
            maxlength="128"
            placeholder="选填，常用邮箱"
          />
          <p v-if="!formDialog.contactEditable" class="ts-text-hint">
            仅内置超管可修改手机号与邮箱；员工可在「个人中心」自行修改
          </p>
          <p v-else class="ts-text-hint">
            手机号与邮箱全局唯一；留空表示不设置，员工首次登录也可自行绑定
          </p>
        </el-form-item>
        <!-- ：直属领导。审批流程的「申请人直属领导」规则直接取这个字段，
             不配则走到该节点时由超管兜底并通知，所以这里给出明确提示 -->
        <el-form-item label="直属领导">
          <el-select
            v-model="formDialog.leaderId"
            placeholder="请选择直属领导（选填）"
            clearable
            filterable
            style="width: 100%"
          >
            <el-option
              v-for="item in leaderCandidates"
              :key="item.id"
              :label="item.displayName || item.username"
              :value="item.id"
            />
          </el-select>
          <p class="ts-text-hint">
            用于审批流程中的「申请人直属领导」规则；未配置时该节点将由超级管理员兜底审批
          </p>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="formDialog.submitting" @click="submitForm">
          {{ formDialog.mode === 'create' ? '新增' : '保存' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 部门弹窗（新增 / 编辑） -->
    <el-dialog
      v-model="deptDialog.visible"
      :title="deptDialog.mode === 'create' ? '新增部门' : '编辑部门'"
      :width="isMobile ? '96%' : '460px'"
      append-to-body
    >
      <el-form label-width="92px" :model="deptDialog">
        <el-form-item label="部门名称" required>
          <el-input v-model="deptDialog.deptName" placeholder="如 研发部 / 前端一组" maxlength="64" />
        </el-form-item>
        <el-form-item label="上级部门">
          <el-select v-model="deptDialog.parentId" placeholder="不选则挂在根节点下" clearable class="ts-org__full">
            <el-option
              v-for="item in parentDeptOptions"
              :key="item.id"
              :label="item.displayPath"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="deptDialog.sortOrder" :min="0" :max="9999" controls-position="right" />
          <span class="ts-text-hint ts-org__form-hint">数字越小越靠前</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="deptDialog.remark" type="textarea" :rows="2" maxlength="255" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="deptDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="deptDialog.submitting" @click="submitDept">
          {{ deptDialog.mode === 'create' ? '新增' : '保存' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 设置部门主管 -->
    <el-dialog
      v-model="managerDialog.visible"
      :title="`设置部门主管 —— ${managerDialog.deptName}`"
      :width="isMobile ? '96%' : '460px'"
      append-to-body
    >
      <p class="ts-text-secondary ts-org__dialog-desc">
        部门主管是该部门成员「直属主管」的默认值，也是审批流「申请人直属领导」的解析来源。
        成员未手工指定过直属领导时跟随此处；一旦手工指定过（成员详情里改过），本处的变更不再覆盖他。
      </p>
      <el-select
        v-model="managerDialog.userIds"
        multiple
        filterable
        placeholder="选择一名或多名部门主管"
        class="ts-org__full"
      >
        <el-option
          v-for="user in managerCandidates"
          :key="user.id"
          :label="`${user.displayName}（${user.username}）`"
          :value="user.id"
        />
      </el-select>
      <template #footer>
        <el-button @click="managerDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="managerDialog.submitting" @click="submitManagers">
          保存
        </el-button>
      </template>
    </el-dialog>

    <!-- 批量调整部门（P3） -->
    <el-dialog v-model="batchDeptVisible" title="批量调整部门" :width="isMobile ? '94%' : '460px'">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="只改归属，不动在途工单"
        description="已提交的工单仍按提交时冻结的流程与审批人走完；该调整对之后新提交的工单生效。"
      />
      <p class="ts-text-hint ts-mt-16">将选中的 {{ selectedIds.length }} 名员工的部门统一改为下方所选。</p>
      <el-select
        v-model="batchDeptId"
        placeholder="请选择目标部门"
        class="ts-mt-16"
        style="width: 100%"
        filterable
      >
        <el-option
          v-for="item in deptOptions"
          :key="item.id"
          :label="item.displayPath"
          :value="item.id"
        />
      </el-select>
      <template #footer>
        <el-button @click="batchDeptVisible = false">取消</el-button>
        <el-button type="primary" :loading="batchSubmitting" @click="submitBatchDepartment">
          确认调整
        </el-button>
      </template>
    </el-dialog>

    <BatchResultDialog v-model="batchResultVisible" :result="batchResult" title="批量调整结果" unit="人" />

    <!-- 批量导入弹窗 -->
    <UserImportDialog v-model="importVisible" @imported="load" />
  </div>
</template>

<style scoped>
/* 批量操作条（P3）：浅底 + 流式换行 */
.ts-staff__bulk {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: var(--el-fill-color-light);
  border-radius: 8px;
}

/* ---------- 组织与人员：左右两栏 ---------- */
.ts-org {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.ts-org__aside {
  flex: 0 0 264px;
  width: 264px;
  max-height: calc(100vh - 160px);
  overflow: auto;
}

.ts-org__aside-head {
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}

.ts-org__aside-title {
  margin: 0;
  font-size: 15px;
  font-weight: 500;
}

.ts-org__search {
  margin-bottom: 8px;
}

.ts-org__drag-hint {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.5;
}

/* 「全部成员」入口：与树节点同高同缩进，视觉上属于同一组 */
.ts-org__all {
  padding: 0 8px;
  height: 32px;
  line-height: 32px;
  font-size: 14px;
  border-radius: 4px;
  cursor: pointer;
}

.ts-org__all:hover {
  background: var(--el-fill-color-light);
}

.ts-org__all.is-active {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.ts-org__tree {
  background: transparent;
}

.ts-org__node {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  min-width: 0;
  padding-right: 4px;
}

.ts-org__node-name {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 人数徽标：改造前部门没有人数概念（业务分组是扁平的），
   有了树之后「这个部门有多少人」是管理员第一个想知道的事 */
.ts-org__node-count {
  flex: 0 0 auto;
  min-width: 20px;
  text-align: center;
  font-size: 12px;
  color: var(--ts-text-hint);
}

.ts-org__node-badge {
  flex: 0 0 auto;
  padding: 0 4px;
  font-size: 11px;
  line-height: 16px;
  color: var(--el-color-primary);
  border: 1px solid var(--el-color-primary-light-5);
  border-radius: 3px;
}

/* 操作区默认隐藏、悬停显示：树节点窄，四个按钮常显会把部门名挤到不可读 */
.ts-org__node-actions {
  flex: 0 0 auto;
  display: none;
  align-items: center;
  gap: 0;
}

.ts-org__node:hover .ts-org__node-actions {
  display: inline-flex;
}

.ts-org__node-actions .el-button + .el-button {
  margin-left: 2px;
}

.ts-org__main {
  flex: 1 1 auto;
  min-width: 0;
}

.ts-org__scope {
  margin-left: 8px;
  font-size: 13px;
  font-weight: 400;
  color: var(--el-color-primary);
}

.ts-org__filter-tag {
  height: 32px;
}

.ts-org__aside-toggle {
  display: none;
}

.ts-org__full {
  width: 100%;
}

.ts-org__form-hint {
  margin-left: 8px;
}

.ts-org__dialog-desc {
  margin: 0 0 12px;
  font-size: 12px;
  line-height: 1.7;
}

.ts-staff__head {
  gap: 16px;
  align-items: flex-start;
}

.ts-staff__head-actions {
  gap: 8px;
  flex-shrink: 0;
}

.ts-staff__title {
  margin: 0;
  font-size: 16px;
  font-weight: 500;
}

.ts-staff__desc {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
}

.ts-staff__filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.ts-staff__filter-item {
  width: 150px;
}

.ts-staff__filter-keyword {
  width: 200px;
}

.ts-staff__filter-actions {
  display: flex;
  gap: 8px;
}

/* 「重置密码」在 AD 域账号下置灰：保留按钮形态但明确不可点，并配 tooltip 说明原因 */
.ts-staff__disabled-action {
  margin-left: 8px;
  font-size: 12px;
  color: var(--ts-text-hint);
  cursor: not-allowed;
}

/* 移动端 AD 域账号的置灰按钮要被 tooltip 包住：
   disabled 的 button 不派发鼠标事件，触发不了 tooltip，故用一层 span 承接；
   span 自己作为 .ts-staff__card-actions 的 flex item，与其它按钮等宽。 */
.ts-staff__card-disabled {
  display: flex;
  flex: 1 1 0;
  min-width: 0;
}

.ts-staff__card-disabled .el-button {
  flex: 1 1 0;
  width: 100%;
}

.ts-staff__emph {
  font-weight: 600;
  color: #d9480f;
}

.ts-staff__cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ts-staff__card {
  padding: 12px;
  border: 1px solid var(--ts-border);
  border-radius: 10px;
  background: #fbfcfe;
}

.ts-staff__card-title {
  word-break: break-all;
}

.ts-staff__card-value {
  word-break: break-all;
  text-align: right;
}

.ts-staff__card-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 3px 0;
  font-size: 13px;
}

.ts-staff__card-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}


@media (max-width: 767px) {
  /* 手机上左右并排会各占半屏、两边都不可用 —— 改为上下堆叠，
     部门树用显式的「隐藏 / 显示部门」按钮收起（触屏没有 hover，「悬停展开」不成立）。 */
  .ts-org {
    flex-direction: column;
  }

  .ts-org__aside {
    flex: 0 0 auto;
    width: 100%;
    max-height: 46vh;
  }

  .ts-org__aside-toggle {
    display: inline-flex;
  }

  /* 触屏没有 hover：操作按钮常显，否则手机上无法维护部门 */
  .ts-org__node-actions {
    display: inline-flex;
  }

  .ts-staff__head {
    flex-direction: column;
  }

  .ts-staff__head-actions {
    width: 100%;
  }

  .ts-staff__head-actions .el-button {
    flex: 1 1 0;
    margin-left: 0;
  }

  .ts-staff__filter-item,
  .ts-staff__filter-keyword {
    width: 100%;
  }

  .ts-staff__filter-actions .el-button {
    flex: 1 1 0;
  }

  .ts-staff__card-actions .el-button {
    flex: 1 1 0;
    margin-left: 0;
  }
}
</style>

