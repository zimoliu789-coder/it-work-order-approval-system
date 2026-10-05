package com.enterprise.ticket.common.api;

/**
 * 统一错误码（ 错误码列表 + 认证/校验类补充码）
 *
 * <p>规范明确列出的错误码全部保留原样；规范未覆盖但工程必需的认证、校验、系统级错误码
 * 追加在下方“补充错误码”区域，命名风格保持一致。
 */
public enum ErrorCode {

    // ----------------  明确定义 ----------------
    SUCCESS("SUCCESS", "操作成功", 200),
    DEVICE_UNAVAILABLE("DEVICE_UNAVAILABLE", "设备不可用（被占用/锁定/非可用状态）", 400),
    APPROVAL_FLOW_NOT_CONFIGURED("APPROVAL_FLOW_NOT_CONFIGURED", "审批流程未配置", 400),
    HANDLER_DEPARTMENT_NOT_CONFIGURED("HANDLER_DEPARTMENT_NOT_CONFIGURED",
            "尚未配置最终处理部门（IT运维组），请联系管理员", 400),
    HANDLER_DEPARTMENT_EMPTY("HANDLER_DEPARTMENT_EMPTY",
            "最终处理部门暂无在职可用成员，请联系管理员", 400),
    USER_WITHOUT_DEPARTMENT("USER_WITHOUT_DEPARTMENT", "员工未分配部门", 400),
    FORBIDDEN("FORBIDDEN", "无权限", 403),
    NO_GROUP("NO_GROUP", "无分组", 400),
    FORCE_CHANGE_PASSWORD("FORCE_CHANGE_PASSWORD", "需要强制修改密码", 403),
    //  ：强制绑定联系方式的服务端闸门（与强制改密同级，由 JwtAuthenticationFilter 抛出）
    CONTACT_BIND_REQUIRED("CONTACT_BIND_REQUIRED", "需要先绑定手机号或邮箱", 403),
    INVALID_TIME_RANGE("INVALID_TIME_RANGE", "时间范围无效", 400),
    ORDER_CANCELLED("ORDER_CANCELLED", "工单已撤回", 400),
    OPERATOR_NOT_ACTUAL_FINAL_HANDLER("OPERATOR_NOT_ACTUAL_FINAL_HANDLER", "非本工单实际处理人，无权执行归还/收回", 403),
    TRANSFER_TARGET_NOT_IN_GROUP("TRANSFER_TARGET_NOT_IN_GROUP", "转交目标不在本工单最终处理部门", 400),
    TRANSFER_ORDER_STATUS_INVALID("TRANSFER_ORDER_STATUS_INVALID", "当前工单状态不允许转交", 400),
    RATE_LIMITED("RATE_LIMITED", "请求过于频繁，请稍后再试", 429),
    DEVICE_IN_BORROWED("DEVICE_IN_BORROWED", "设备正在借用中，禁止此操作", 400),

    // ---------------- 补充错误码（规范未覆盖，工程必需） ----------------
    UNAUTHORIZED("UNAUTHORIZED", "未登录或登录状态已失效，请重新登录", 401),
    BAD_CREDENTIALS("BAD_CREDENTIALS", "账号或密码错误", 401),
    ACCOUNT_LOCKED("ACCOUNT_LOCKED", "连续登录失败次数过多，账号已临时锁定，请稍后再试", 401),

    // ---------------- 初始化向导（本次新增） ----------------
    /**
     * 初始化向导：系统已完成初始化（库中已有超级管理员）。
     *
     * <p>单独成码而不是复用 {@code FORBIDDEN}：前端要据此把用户从向导页**自动跳走**，
     * 而 403 是「这个人没权限」，语义完全不同 —— 用 403 会让界面提示「无权限」，
     * 让管理员以为是自己账号的问题。
     */
    SETUP_ALREADY_INITIALIZED("SETUP_ALREADY_INITIALIZED", "系统已完成初始化，无需重复设置", 409),
    /** 初始化向导：超管登录名不合法（允许字母 / 数字 / . _ -，3~32 位） */
    SETUP_USERNAME_INVALID("SETUP_USERNAME_INVALID",
            "超管账号名只能包含字母、数字、下划线、点或短横线，长度 3~32 位", 400),
    /** 初始化向导：两次输入的密码不一致 */
    SETUP_PASSWORD_MISMATCH("SETUP_PASSWORD_MISMATCH", "两次输入的密码不一致", 400),

    /**
     * 来源 IP 被封禁。
     *
     * <p>刻意与 {@link #ACCOUNT_LOCKED} 分开：账号锁定是「你这个人被锁了」，
     * IP 封禁是「你这个来源被挡了」—— 后者的受害者可能是同一个出口下的其他人
     * （整栋办公楼共用一个出口 IP）。用同一个码会让被误封的人去反复核对
     * 「是不是我密码错了」，而真正该做的是找管理员把 IP 段加进白名单。
     *
     * <p>HTTP 403 而不是 401：401 会让前端跳登录页（看起来像「没登录」），
     * 而这里用户是「被拒绝服务」，重登多少次都一样。
     */
    IP_BLOCKED("IP_BLOCKED", "该来源 IP 因多次登录失败已被临时封禁，请稍后重试或联系管理员", 403),
    ACCOUNT_DISABLED("ACCOUNT_DISABLED", "账号已被禁用或已离职，请联系管理员", 401),
    LDAP_NOT_AVAILABLE("LDAP_NOT_AVAILABLE", "域控服务暂不可用，请联系管理员", 401),
    CSRF_HEADER_MISSING("CSRF_HEADER_MISSING", "缺少安全校验请求头，请求被拒绝", 403),
    NOT_FOUND("NOT_FOUND", "请求的资源不存在", 404),
    METHOD_NOT_ALLOWED("METHOD_NOT_ALLOWED", "请求方法不被支持", 405),
    PARAM_INVALID("PARAM_INVALID", "请求参数不合法", 400),
    UPLOAD_FILE_TOO_LARGE("UPLOAD_FILE_TOO_LARGE", "上传文件超过服务端允许的大小上限", 400),
    PASSWORD_WEAK("PASSWORD_WEAK", "密码强度不足", 400),
    PASSWORD_SAME_AS_OLD("PASSWORD_SAME_AS_OLD", "新密码不能与当前密码相同", 400),
    USER_NOT_FOUND("USER_NOT_FOUND", "用户不存在", 404),
    USER_ALREADY_EXISTS("USER_ALREADY_EXISTS", "用户已存在", 400),
    CONFIG_MISSING("CONFIG_MISSING", "系统配置缺失", 500),
    INTERNAL_ERROR("INTERNAL_ERROR", "系统内部错误，请联系管理员", 500),

    // ---------------- ：组织与人员（部门树） ----------------
    // 原先这里是一组「部门 / 最终处理部门」的错误码。两个概念在 里
    // 整体退役：部门 → 部门树，最终处理部门 → 「IT运维组」部门 + 「IT执行人」角色。
    // 因此这一组码**全部换名而不是保留别名** —— 留着旧码只会让下一个人以为
    // 「部门」还是一个活着的概念，而它连表都已经被删了。
    DEPARTMENT_NOT_FOUND("DEPARTMENT_NOT_FOUND", "部门不存在", 404),
    DEPARTMENT_NAME_EXISTS("DEPARTMENT_NAME_EXISTS", "同一上级下已存在同名部门，请换一个名称", 400),
    DEPARTMENT_MOVE_INVALID("DEPARTMENT_MOVE_INVALID",
            "不能把部门移动到它自己或它的下级部门", 400),
    DEPARTMENT_ROOT_PROTECTED("DEPARTMENT_ROOT_PROTECTED", "根节点（公司）不可删除、不可移动", 400),
    DEPARTMENT_HAS_CHILDREN("DEPARTMENT_HAS_CHILDREN",
            "该部门下仍有子部门，请先删除或移走子部门", 400),

    /**
     * 部门下仍有成员（P1 安全修复）。
     *
     * <p>删除部门时会**连带删除该部门的「部门主管」配置**（{@code department_manager} 行），
     * 这会让原本走「部门主管」审批的工单**静默改道**为超管兜底 —— 一次误点即改写一批人的
     * 审批路径。因此只要部门下还有成员（主部门 ∪ 兼职），就拒绝删除并提示先调走。
     *
     * <p>单独成码而不是复用 {@code DEPARTMENT_HAS_CHILDREN}：两者提示语与处置动作不同
     * （「先移走子部门」vs「先把成员调到其他部门」），前端要给出不同的操作指引。
     */
    DEPARTMENT_HAS_MEMBERS("DEPARTMENT_HAS_MEMBERS",
            "该部门下仍有成员，请先把成员调到其他部门再删除", 400),
    APPROVER_ILLEGAL("APPROVER_ILLEGAL", "审批人不存在，请重新选择", 400),
    APPROVER_STEP_DUPLICATE("APPROVER_STEP_DUPLICATE", "审批步骤序号重复，同组内步骤序号不可重复", 400),
    USER_NOT_IN_DEPARTMENT("USER_NOT_IN_DEPARTMENT", "该员工不属于此部门", 400),

    // ----------------  设备台账补充（设备分类 / 设备台账 / 设备状态机） ----------------
    DEVICE_NOT_FOUND("DEVICE_NOT_FOUND", "设备不存在", 404),
    DEVICE_ASSET_NO_EXISTS("DEVICE_ASSET_NO_EXISTS", "资产编号已存在（含历史已删除设备），请更换", 400),
    DEVICE_CATEGORY_NOT_FOUND("DEVICE_CATEGORY_NOT_FOUND", "设备分类不存在", 404),
    DEVICE_CATEGORY_NAME_EXISTS("DEVICE_CATEGORY_NAME_EXISTS", "同级分类下已存在同名分类", 400),
    DEVICE_CATEGORY_LEVEL_INVALID("DEVICE_CATEGORY_LEVEL_INVALID", "设备分类仅支持一级、二级两级", 400),
    DEVICE_CATEGORY_HAS_CHILDREN("DEVICE_CATEGORY_HAS_CHILDREN", "该分类下仍有子分类，请先删除子分类", 400),
    DEVICE_CATEGORY_HAS_DEVICE("DEVICE_CATEGORY_HAS_DEVICE", "该分类下仍有设备，请先调整设备分类", 400),
    DEVICE_CATEGORY_MISMATCH("DEVICE_CATEGORY_MISMATCH", "所选二级分类不属于该一级分类", 400),
    DEVICE_STATUS_INVALID("DEVICE_STATUS_INVALID", "设备状态取值不合法", 400),
    DEVICE_STATUS_TRANSITION_INVALID("DEVICE_STATUS_TRANSITION_INVALID", "当前设备状态不允许该操作", 400),

    /**
     * 批量操作条目数超限（P3）
     *
     * <p>单独成码而不是复用 {@code PARAM_INVALID}：前端需要据此提示「请分批处理」，
     * 并且要能区分「你一条都没选」与「你选了太多」这两种完全不同的处置方式。
     */
    BATCH_SIZE_EXCEEDED("BATCH_SIZE_EXCEEDED", "单次批量操作最多 200 条，请分批处理", 400),

    // ----------------  借用申请补充（临时锁 / 借用申请 / 基础审批 / 交付） ----------------
    DEVICE_LOCK_TOKEN_INVALID("DEVICE_LOCK_TOKEN_INVALID", "设备临时锁已失效或已不属于你，请重新选择设备", 400),
    DEVICE_NOT_LOCKED("DEVICE_NOT_LOCKED", "设备当前未被锁定，无法执行该操作", 400),
    USE_TYPE_INVALID("USE_TYPE_INVALID", "借用类型取值不合法", 400),
    EXPECTED_RETURN_DATE_REQUIRED("EXPECTED_RETURN_DATE_REQUIRED", "短期借用必须填写期望归还日期", 400),
    EXPECTED_RETURN_DATE_INVALID("EXPECTED_RETURN_DATE_INVALID", "期望归还日期不能早于今天", 400),
    ORDER_NOT_FOUND("ORDER_NOT_FOUND", "工单不存在", 404),
    ORDER_STATUS_INVALID("ORDER_STATUS_INVALID", "工单状态取值不合法", 400),
    ORDER_STATUS_TRANSITION_INVALID("ORDER_STATUS_TRANSITION_INVALID", "当前工单状态不允许该操作", 400),
    ORDER_NOT_APPLICANT("ORDER_NOT_APPLICANT", "只有申请人本人可以执行该操作", 403),
    APPROVER_NOT_CURRENT_NODE("APPROVER_NOT_CURRENT_NODE", "当前审批节点不需要你处理", 403),
    APPROVAL_NODE_HANDLED("APPROVAL_NODE_HANDLED", "该审批节点已处理，请勿重复提交", 400),
    REJECT_COMMENT_REQUIRED("REJECT_COMMENT_REQUIRED", "驳回必须填写驳回原因", 400),

    // ---------------- 管理端补充（设备批量导入 / 全部工单全局视图） ----------------
    DEVICE_IMPORT_FILE_REQUIRED("DEVICE_IMPORT_FILE_REQUIRED", "请选择要导入的 Excel 文件", 400),
    DEVICE_IMPORT_FILE_TYPE_INVALID("DEVICE_IMPORT_FILE_TYPE_INVALID", "仅支持 .xlsx 格式的 Excel 文件", 400),
    DEVICE_IMPORT_FILE_TOO_LARGE("DEVICE_IMPORT_FILE_TOO_LARGE", "文件超过 5MB 上限，请拆分后重新导入", 400),
    DEVICE_IMPORT_ROW_LIMIT_EXCEEDED("DEVICE_IMPORT_ROW_LIMIT_EXCEEDED", "单次最多导入 500 行，请拆分后重新导入", 400),
    DEVICE_IMPORT_EMPTY("DEVICE_IMPORT_EMPTY", "文件中没有可导入的数据行，请先填写内容", 400),
    DEVICE_IMPORT_PARSE_FAILED("DEVICE_IMPORT_PARSE_FAILED", "文件解析失败，请使用「下载导入模板」的格式重新整理", 400),
    DEVICE_IMPORT_NOTHING_TO_IMPORT("DEVICE_IMPORT_NOTHING_TO_IMPORT", "没有校验通过的数据行可导入", 400),

    // ----------------  归还与顺延（两步归还 / 自动顺延 / 超时告警 / 站内消息 / 离职联动） ----------------
    RETURN_CONDITION_INVALID("RETURN_CONDITION_INVALID", "收回登记的设备状态取值不合法", 400),
    RETURN_NOTE_TOO_LONG("RETURN_NOTE_TOO_LONG", "归还说明过长，请精简后重试", 400),
    MESSAGE_NOT_FOUND("MESSAGE_NOT_FOUND", "消息不存在或不属于当前账号", 404),
    USER_ALREADY_DIMISSION("USER_ALREADY_DIMISSION", "该员工已处于离职状态", 400),
    USER_NOT_DIMISSION("USER_NOT_DIMISSION", "该员工当前并非离职状态，无需恢复在职", 400),
    SUPER_ADMIN_CANNOT_DIMISSION("SUPER_ADMIN_CANNOT_DIMISSION", "超级管理员账号不可标记离职", 400),
    USER_SELF_DIMISSION_FORBIDDEN("USER_SELF_DIMISSION_FORBIDDEN", "不可将自己的账号标记为离职", 400),
    JOB_NOT_FOUND("JOB_NOT_FOUND", "定时任务不存在", 400),

    // ----------------  延期子工单与设备故障上报（ / ） ----------------
    EXTEND_NOT_ALLOWED("EXTEND_NOT_ALLOWED", "当前工单状态不允许申请延期（仅「使用中」可延期）", 400),
    EXTEND_LONG_TERM_NOT_SUPPORTED("EXTEND_LONG_TERM_NOT_SUPPORTED", "长期领用没有固定归还日期，无需延期", 400),
    EXTEND_LIMIT_EXCEEDED("EXTEND_LIMIT_EXCEEDED", "延期次数已达上限，无法再次申请", 400),
    EXTEND_PENDING_EXISTS("EXTEND_PENDING_EXISTS", "已有一条延期申请正在审批中，请等待处理结果", 400),
    EXTEND_TIME_INVALID("EXTEND_TIME_INVALID", "延期后的结束时间必须晚于当前时间", 400),
    EXTEND_NOT_FOUND("EXTEND_NOT_FOUND", "延期申请不存在", 404),
    FAULT_NOT_FOUND("FAULT_NOT_FOUND", "故障记录不存在", 404),
    FAULT_ALREADY_HANDLED("FAULT_ALREADY_HANDLED", "该故障记录已处理完毕，不可重复操作", 400),
    FAULT_OCCURRED_TIME_INVALID("FAULT_OCCURRED_TIME_INVALID", "故障发生时间不能晚于当前时间", 400),
    FAULT_DEVICE_STATE_INVALID("FAULT_DEVICE_STATE_INVALID", "设备当前状态不允许上报故障", 400),
    FAULT_ORDER_MISMATCH("FAULT_ORDER_MISMATCH", "所选工单与故障设备不匹配", 400),
    FAULT_REPORTER_NOT_ALLOWED("FAULT_REPORTER_NOT_ALLOWED", "只有该工单借用人或管理员可以上报故障", 403),
    FAULT_REPAIRER_INVALID("FAULT_REPAIRER_INVALID", "指定的维修人不存在或已停用", 400),

    // ----------------  工单转交与催办（ + 需求方 ） ----------------
    // 注：TRANSFER_ORDER_STATUS_INVALID / TRANSFER_TARGET_NOT_IN_GROUP 已在上方「工单流转」段定义，此处不再重复
    TRANSFER_NOT_HANDLER("TRANSFER_NOT_HANDLER", "只有该工单当前实际执行人（或超级管理员）可以转交", 403),
    TRANSFER_TARGET_INVALID("TRANSFER_TARGET_INVALID", "转交目标无效：必须是在职启用的员工，且不能是申请人本人或当前执行人", 400),
    TRANSFER_CONFLICT("TRANSFER_CONFLICT", "工单执行人已发生变化，请刷新后重试", 409),
    URGE_NOT_ALLOWED("URGE_NOT_ALLOWED", "当前工单状态不允许该类型催办", 400),
    URGE_TARGET_MISSING("URGE_TARGET_MISSING", "暂无可催办的对象，请稍后重试", 400),
    URGE_COOLDOWN("URGE_COOLDOWN", "催办过于频繁，请稍后再试", 429),

    // ---------------- 管理端基础功能增强（员工管理 / 超管强制干预 / 故障报修，2026-09-18 小迭代） ----------------
    // 员工管理（）
    USER_NAME_EXISTS("USER_NAME_EXISTS", "姓名已存在", 400),
    USER_USERNAME_EXISTS("USER_USERNAME_EXISTS", "登录名已存在", 400),
    USER_ROLE_INVALID("USER_ROLE_INVALID", "角色取值不合法，请从角色列表中选择", 400),
    USER_SUPER_ADMIN_PROTECTED("USER_SUPER_ADMIN_PROTECTED", "超级管理员账号不可被编辑、禁用或重置密码", 400),
    /**
     * 2026-09-20 ：管理员可给「其它」超管降级，但不得把自己降级 ——
     * 降级自己会立刻把自己踢出系统，且再也无法自行恢复。
     */
    USER_SELF_DEMOTE_FORBIDDEN("USER_SELF_DEMOTE_FORBIDDEN", "不可将自己的账号降级为其它角色", 400),
    /** 2026-09-20 ：任何一次降级都必须保证系统里至少还剩 1 个超管（兜底审批 / 系统管理的最后入口）。 */
    USER_LAST_SUPER_ADMIN_PROTECTED("USER_LAST_SUPER_ADMIN_PROTECTED",
            "系统至少需保留 1 个超级管理员，无法降级最后一个超管", 400),
    USER_SELF_DISABLE_FORBIDDEN("USER_SELF_DISABLE_FORBIDDEN", "不可禁用当前登录的账号", 400),
    USER_ALREADY_ENABLED("USER_ALREADY_ENABLED", "该账号当前已处于启用状态", 400),
    USER_ALREADY_DISABLED("USER_ALREADY_DISABLED", "该账号当前已处于禁用状态", 400),
    USER_DIMISSION_USE_REINSTATE("USER_DIMISSION_USE_REINSTATE", "该员工处于离职状态，请使用「恢复在职」而非启用账号", 400),
    // 员工批量导入（，与设备导入同模式）
    USER_IMPORT_FILE_REQUIRED("USER_IMPORT_FILE_REQUIRED", "请选择要导入的 Excel 文件", 400),
    USER_IMPORT_FILE_TYPE_INVALID("USER_IMPORT_FILE_TYPE_INVALID", "仅支持 .xlsx 格式的 Excel 文件", 400),
    USER_IMPORT_FILE_TOO_LARGE("USER_IMPORT_FILE_TOO_LARGE", "文件超过 5MB 上限，请拆分后重新导入", 400),
    USER_IMPORT_ROW_LIMIT_EXCEEDED("USER_IMPORT_ROW_LIMIT_EXCEEDED", "单次最多导入 500 行，请拆分后重新导入", 400),
    USER_IMPORT_EMPTY("USER_IMPORT_EMPTY", "文件中没有可导入的数据行，请先填写内容", 400),
    USER_IMPORT_PARSE_FAILED("USER_IMPORT_PARSE_FAILED", "文件解析失败，请使用「下载导入模板」的格式重新整理", 400),
    USER_IMPORT_NOTHING_TO_IMPORT("USER_IMPORT_NOTHING_TO_IMPORT", "没有校验通过的数据行可导入", 400),
    // 超级管理员强制干预（）
    FORCE_OPERATION_NOT_SUPPORTED("FORCE_OPERATION_NOT_SUPPORTED", "该工单当前状态不支持此强制操作", 400),
    FORCE_REASON_REQUIRED("FORCE_REASON_REQUIRED", "强制操作必须填写原因", 400),
    FORCE_OPERATION_INVALID("FORCE_OPERATION_INVALID", "强制操作类型不合法", 400),
    FORCE_TARGET_HANDLER_REQUIRED("FORCE_TARGET_HANDLER_REQUIRED", "强制转交执行人必须指定目标执行人", 400),
    FORCE_TARGET_HANDLER_INVALID("FORCE_TARGET_HANDLER_INVALID", "强制转交目标无效：必须是在职启用的员工", 400),
    FORCE_TARGET_APPROVER_REQUIRED("FORCE_TARGET_APPROVER_REQUIRED", "强制转交审批必须指定目标审批人", 400),
    FORCE_TARGET_APPROVER_INVALID("FORCE_TARGET_APPROVER_INVALID", "强制转交审批目标无效：必须是在职启用的员工", 400),

    // ---------------- ：附件上传通用能力 ----------------
    ATTACHMENT_FILE_REQUIRED("ATTACHMENT_FILE_REQUIRED", "请选择要上传的文件", 400),
    ATTACHMENT_FILE_TOO_LARGE("ATTACHMENT_FILE_TOO_LARGE", "附件超过大小上限，请压缩后重试", 400),
    ATTACHMENT_TYPE_NOT_ALLOWED("ATTACHMENT_TYPE_NOT_ALLOWED", "附件类型不允许（请检查文件扩展名）", 400),
    ATTACHMENT_BIZ_TYPE_INVALID("ATTACHMENT_BIZ_TYPE_INVALID", "附件业务类型不合法", 400),
    ATTACHMENT_BIZ_NOT_FOUND("ATTACHMENT_BIZ_NOT_FOUND", "关联业务不存在或无权访问", 404),
    ATTACHMENT_NOT_FOUND("ATTACHMENT_NOT_FOUND", "附件不存在或已被删除", 404),
    ATTACHMENT_LIMIT_EXCEEDED("ATTACHMENT_LIMIT_EXCEEDED", "附件数量已达上限，请先删除历史附件", 400),
    ATTACHMENT_SAVE_FAILED("ATTACHMENT_SAVE_FAILED", "附件保存失败，请稍后重试", 500),

    // ---------------- 上线前：站点 logo 上传 ----------------
    // 与 ATTACHMENT_* 分开而不是复用：本组错误发生在**系统参数页**（改站点外观），
    // 复用会给出「附件超过大小上限」这类指错对象、看不到修复方向的提示。
    SITE_LOGO_FILE_REQUIRED("SITE_LOGO_FILE_REQUIRED", "请选择要上传的 logo 图片", 400),
    SITE_LOGO_FILE_TOO_LARGE("SITE_LOGO_FILE_TOO_LARGE", "logo 图片超过大小上限，请压缩后重试", 400),
    SITE_LOGO_TYPE_INVALID("SITE_LOGO_TYPE_INVALID", "logo 仅支持 png / jpg 格式的图片", 400),
    SITE_LOGO_NOT_FOUND("SITE_LOGO_NOT_FOUND", "logo 图片不存在或已被清除", 404),
    SITE_LOGO_SAVE_FAILED("SITE_LOGO_SAVE_FAILED", "logo 保存失败，请稍后重试", 500),

    // ---------------- Excel 导出与统计报表 ----------------
    EXPORT_TYPE_INVALID("EXPORT_TYPE_INVALID", "导出类型不合法", 400),
    /**
     * 导出条件不完整或不合逻辑（例如自定义表单导出未指定申请类型）。
     *
     * <p>与 {@link #EXPORT_TYPE_INVALID} 分开：前者是「这个导出类型不存在 / 不匹配数据源」，
     * 后者是「类型没错，但条件不足以确定导出范围」。两者让用户做的动作不同
     * （换类型 vs 补条件），合成一个码会把「怎么改」的提示弄丢。
     */
    EXPORT_QUERY_INVALID("EXPORT_QUERY_INVALID", "导出条件不完整或不合逻辑", 400),
    EXPORT_NOT_FOUND("EXPORT_NOT_FOUND", "导出记录不存在或无权访问", 404),
    EXPORT_NOT_READY("EXPORT_NOT_READY", "导出文件尚未生成完成，请稍后在消息中心查看", 400),
    EXPORT_FAILED("EXPORT_FAILED", "导出生成失败，请调整筛选条件后重试", 400),
    EXPORT_EXPIRED("EXPORT_EXPIRED", "导出文件已过期，请重新导出", 410),
    EXPORT_ROW_LIMIT_EXCEEDED("EXPORT_ROW_LIMIT_EXCEEDED", "导出数据量超出上限，请先缩小筛选范围", 400),
    EXPORT_SAVE_FAILED("EXPORT_SAVE_FAILED", "导出文件保存失败，请稍后重试", 500),

    // ---------------- 三波遗漏补做：角色与权限（） ----------------
    ROLE_NOT_FOUND("ROLE_NOT_FOUND", "角色不存在", 404),
    ROLE_CODE_EXISTS("ROLE_CODE_EXISTS", "角色编码已存在，请换一个", 400),
    ROLE_NOT_ASSIGNABLE("ROLE_NOT_ASSIGNABLE", "该角色不存在或已停用，不能分配给员工", 400),
    ROLE_BUILTIN_PROTECTED("ROLE_BUILTIN_PROTECTED", "内置角色不可删除、不可修改编码", 400),
    ROLE_SUPER_ADMIN_READONLY("ROLE_SUPER_ADMIN_READONLY", "超级管理员角色权限恒为全量，不可修改", 400),
    ROLE_IN_USE("ROLE_IN_USE", "该角色仍被员工使用，请先调整这些员工的角色", 400),
    ROLE_PERMISSION_INVALID("ROLE_PERMISSION_INVALID", "存在无法识别的权限码，请刷新页面后重试", 400),

    // ---------------- 三波遗漏补做：系统参数写入（） ----------------
    CONFIG_KEY_NOT_FOUND("CONFIG_KEY_NOT_FOUND", "配置项不存在", 404),
    CONFIG_NOT_EDITABLE("CONFIG_NOT_EDITABLE", "该配置项不允许在界面修改", 400),
    CONFIG_VALUE_INVALID("CONFIG_VALUE_INVALID", "配置值不合法，请检查取值范围", 400),

    /**
     * 通道未启用时试图修改该通道的参数（ · ）。
     *
     * <p>与 {@link #CONTACT_CHANNEL_DISABLED} 的分工：那个是<b>用户端</b>
     * 「渠道被关，所以不能发码 / 不能绑定」；本码是<b>管理端</b>
     * 「通道被关，所以它的参数不允许改」。两者背后的开关是同一个，
     * 但报给谁看、该去做什么完全不同 —— 混用一个码会让管理员看到
     * 「管理员未开启短信服务」这种明显是给用户看的文案。
     */
    CONFIG_CHANNEL_DISABLED("CONFIG_CHANNEL_DISABLED",
            "该通知通道当前未启用，请先打开对应的启用开关，再修改本通道的参数", 400),

    /**
     * 邮件发送失败（：「发送测试邮件」与邮箱验证码投递）。
     *
     * <p>服务端会把底层异常归类成可读原因（认证失败 / 连不上 / SSL 不匹配）拼在 message 里，
     * 前端<b>直接展示 message</b> 即可，不要用本码的默认文案覆盖它 ——
     * 「发送失败」四个字对管理员毫无价值，而具体原因才是这个功能的全部意义。
     */
    MAIL_SEND_FAILED("MAIL_SEND_FAILED", "邮件发送失败，请检查 SMTP 配置", 400),

    /**
     * 短信发送失败（ · 的「发送测试短信」）。
     *
     * <p>与 {@link #MAIL_SEND_FAILED} 同口径：具体原因（参数缺失 / 格式非法 /
     * 通道未接入所以只写了日志）由服务层拼进 message，前端直接展示。
     */
    SMS_SEND_FAILED("SMS_SEND_FAILED", "短信发送失败，请检查短信通道配置", 400),

    // ---------------- 三波遗漏补做：令牌失效（） ----------------
    TOKEN_REVOKED("TOKEN_REVOKED", "登录凭证已失效，请重新登录", 401),
    LOGIN_NAME_CONFLICT("LOGIN_NAME_CONFLICT", "登录账号已被占用，请更换", 400),

    // ---------------- ：AD 域控对接 ----------------
    /**
     * AD 配置不存在或尚未完成必填项。
     *
     * <p>与 {@link #LDAP_NOT_AVAILABLE} 的区别：那个是「配好了但连不上」（运行期故障，
     * 用户看到的是「请稍后重试」），本码是「根本没配全」（配置缺失，
     * 用户看到的是「请联系管理员」—— 这种情况重试一万次也不会成功）。
     */
    AD_CONFIG_INCOMPLETE("AD_CONFIG_INCOMPLETE", "AD 域控配置不完整，请联系管理员", 400),
    AD_CONFIG_NOT_FOUND("AD_CONFIG_NOT_FOUND", "AD 域控配置不存在，请先完成配置", 404),
    AD_TEST_FAILED("AD_TEST_FAILED", "AD 连接测试失败", 400),
    AD_SYNC_FAILED("AD_SYNC_FAILED", "AD 用户同步失败", 400),
    AD_DISABLED("AD_DISABLED", "AD 域控认证当前未启用", 400),
    /**
     * 账号来源转换不被允许。
     *
     * <p>典型场景：把本地账号转为 AD 账号，但 AD 尚未启用 —— 转过去之后该账号
     * 既不能走 AD（没启用）也没有本地口令（已被清空），会直接变成永远登不进来的死号。
     * 这类「一步就把账号弄废」的操作必须在入口拦住。
     */
    AUTH_TYPE_CONVERT_FORBIDDEN("AUTH_TYPE_CONVERT_FORBIDDEN", "当前状态不允许该账号来源转换", 400),
    AUTH_TYPE_INVALID("AUTH_TYPE_INVALID", "账号来源取值不合法", 400),
    /** 该账号本来就是本地账号，无需再转（重复点击「转为本地用户」） */
    AD_ACCOUNT_ALREADY_LOCAL("AD_ACCOUNT_ALREADY_LOCAL", "该账号已是本地账号，无需转换", 400),
    /** 该账号本来就是域账号，无需再转（重复点击「转为 AD 用户」） */
    AD_ACCOUNT_ALREADY_LDAP("AD_ACCOUNT_ALREADY_LDAP", "该账号已是 AD 域账号，无需转换", 400),
    /**
     * 本地 → AD 转换时，域控中不存在该登录名。
     *
     * <p>必须在写库之前拦下：转换会把本地口令随机化，若域里又没有这个账号，
     * 转完就是「本地登不了、域里也没人」，属于一步造成不可用账号。
     */
    AD_ACCOUNT_NOT_IN_DIRECTORY("AD_ACCOUNT_NOT_IN_DIRECTORY",
            "域控中不存在该登录名的账号，无法转为 AD 用户", 400),
    /** 域账号的密码只能在域控修改（本地改密 / 重置密码一律拒绝） */
    AD_PASSWORD_MANAGED_BY_AD("AD_PASSWORD_MANAGED_BY_AD", "域账号请在 AD 域控中修改密码，本地不支持修改", 400),

    // ---------------- ：自定义申请类型 + 动态表单（第一期） ----------------
    // 表单模板
    FORM_TEMPLATE_NOT_FOUND("FORM_TEMPLATE_NOT_FOUND", "表单模板不存在", 404),
    FORM_TEMPLATE_VERSION_NOT_FOUND("FORM_TEMPLATE_VERSION_NOT_FOUND", "表单模板版本不存在", 404),
    FORM_TEMPLATE_NAME_EXISTS("FORM_TEMPLATE_NAME_EXISTS", "表单模板名称已存在，请换一个", 400),
    /**
     * 表单定义不合法（发布前校验失败）。
     *
     * <p>与 {@link #PARAM_INVALID} 分开，是因为管理端需要一眼区分
     * 「请求参数本身有问题」与「设计出来的表单有问题」——后者要回到设计器里改，
     * 前者是调用方式错了。把两者混成一个码，排查时要先看日志才能判断。
     */
    FORM_SCHEMA_INVALID("FORM_SCHEMA_INVALID", "表单定义不合法", 400),
    /** 无已发布版本：申请类型必须引用「已发布」的版本 */
    FORM_TEMPLATE_NO_PUBLISHED_VERSION("FORM_TEMPLATE_NO_PUBLISHED_VERSION",
            "该表单模板尚无已发布版本，请先发布后再关联", 400),
    /** 模板仍被申请类型引用，只能停用不能删除 */
    FORM_TEMPLATE_IN_USE("FORM_TEMPLATE_IN_USE", "该表单模板已被申请类型引用，只能停用不能删除", 400),
    /** 已发布的版本不可修改（要改请新建版本） */
    FORM_TEMPLATE_VERSION_FROZEN("FORM_TEMPLATE_VERSION_FROZEN", "已发布的版本不可修改，请新建版本", 400),

    // 申请类型
    APPLY_TYPE_NOT_FOUND("APPLY_TYPE_NOT_FOUND", "申请类型不存在", 404),
    APPLY_TYPE_CODE_EXISTS("APPLY_TYPE_CODE_EXISTS", "类型编码已存在，请换一个", 400),
    APPLY_TYPE_CODE_INVALID("APPLY_TYPE_CODE_INVALID", "类型编码不合法（字母开头，仅字母/数字/下划线，2-20 位）", 400),
    APPLY_TYPE_PREFIX_INVALID("APPLY_TYPE_PREFIX_INVALID", "工单编号前缀不合法（字母开头，2-10 位字母/数字）", 400),
    APPLY_TYPE_HAS_ORDER("APPLY_TYPE_HAS_ORDER", "该申请类型已被工单使用，只能停用不能删除", 400),
    APPLY_TYPE_DISABLED("APPLY_TYPE_DISABLED", "该申请类型已停用，无法提交", 400),
    APPLY_TYPE_SUBMIT_FORBIDDEN("APPLY_TYPE_SUBMIT_FORBIDDEN", "你不在该申请类型的可提交范围内", 403),
    APPLY_TYPE_PERMISSION_INVALID("APPLY_TYPE_PERMISSION_INVALID", "提交权限配置不合法", 400),

    // 动态表单数据与自定义工单
    /**
     * 用户提交的表单数据未通过校验。
     *
     * <p>message 里会带上<b>字段级</b>的错误明细（哪个字段、错在哪），
     * 让前端无需二次猜测就能把错误落到具体控件上。
     */
    FORM_DATA_INVALID("FORM_DATA_INVALID", "表单数据校验未通过", 400),
    ORDER_NOT_CUSTOM("ORDER_NOT_CUSTOM", "该工单不是自定义申请，没有表单数据", 400),
    ORDER_FORM_DATA_NOT_FOUND("ORDER_FORM_DATA_NOT_FOUND", "该工单没有表单数据", 404),

    // ---------------- ：动态审批流程（自定义申请第二期） ----------------
    // 流程模板
    FLOW_NOT_FOUND("FLOW_NOT_FOUND", "审批流程不存在", 404),
    FLOW_VERSION_NOT_FOUND("FLOW_VERSION_NOT_FOUND", "审批流程版本不存在", 404),
    FLOW_CODE_EXISTS("FLOW_CODE_EXISTS", "流程编码已存在，请换一个", 400),
    FLOW_CODE_INVALID("FLOW_CODE_INVALID", "流程编码不合法（字母开头，仅字母/数字/下划线，2-32 位）", 400),
    /**
     * 流程定义不合法（发布前结构校验失败：孤岛节点 / 成环 / 分支未穷尽 / 条件字段不存在 …）。
     *
     * <p>与 {@link #FORM_SCHEMA_INVALID} 同理，独立成码是为了让管理端一眼区分
     * 「请求参数错了」与「画出来的流程有问题」——后者要回设计器改。
     */
    FLOW_DEFINITION_INVALID("FLOW_DEFINITION_INVALID", "审批流程定义不合法", 400),
    /** 无已发布版本：申请类型必须引用「已发布」的流程版本 */
    FLOW_NO_PUBLISHED_VERSION("FLOW_NO_PUBLISHED_VERSION", "该审批流程尚无已发布版本，请先发布后再关联", 400),
    /** 流程仍被申请类型引用，只能停用不能删除 */
    FLOW_IN_USE("FLOW_IN_USE", "该审批流程已被申请类型引用，只能停用不能删除", 400),
    /** 已发布的版本不可修改（要改请新建版本） */
    FLOW_VERSION_FROZEN("FLOW_VERSION_FROZEN", "已发布的版本不可修改，请新建版本", 400),
    /** 申请人自选审批人不满足要求（未选 / 超出可选范围 / 人数不在区间） */
    FLOW_APPROVER_SELECTION_INVALID("FLOW_APPROVER_SELECTION_INVALID", "申请人选择的审批人不符合流程要求", 400),
    /** 审批方式为 FLOW 但未绑定流程版本 */
    FLOW_REQUIRED("FLOW_REQUIRED", "审批方式选择「自定义流程」时必须绑定一个已发布的流程版本", 400),

    // ---------------- ：上一节点指派 / 直属领导 ----------------
    /**
     * 「上一节点审批人指定」的指派不合法。
     *
     * <p>覆盖：下一步骤有待指派节点却未带 {@code nextApproverIds}、人数与节点配置不一致、
     * 指派了离职/停用用户、指派了申请人本人、以及在**没有**待指派节点时却传了参数
     * （拒绝静默忽略 —— 静默忽略会让上一节点以为指派成功，实际工单卡住）。
     */
    FLOW_NEXT_ASSIGN_INVALID("FLOW_NEXT_ASSIGN_INVALID", "指定的下一节点审批人不符合要求", 400),

    /**
     * 当前步骤的审批人尚未被上一节点指定。
     *
     * <p>与 {@link #APPROVER_NOT_CURRENT_NODE} 刻意分开：后者是「轮不到你」，
     * 而本码是「这一步还等着上一节点指定人」—— 含糊地回「不需要你处理」会让
     * 上一节点审批人误以为系统出故障，实际只是他上次通过时漏了指派。
     */
    FLOW_APPROVER_PENDING_ASSIGN("FLOW_APPROVER_PENDING_ASSIGN", "该节点审批人待上一节点指定", 403),

    /**
     * 运行时条件引擎总开关关闭时尝试发布含运行期特性的流程。
     *
     * <p>刻意不是"静默降级为可发布"：那样配置者会以为条件生效了，
     * 而实际每条分支都按"提交时定格"的老口径走 —— 这是最难察觉的一类失效。
     */
    FLOW_RUNTIME_CONDITION_DISABLED("FLOW_RUNTIME_CONDITION_DISABLED",
            "运行时条件引擎总开关已关闭，含运行期条件的流程暂不可发布", 400),

    // ---------------- 上线前需求（九）：姓名与登录名格式 ----------------
    /**
     * 登录名格式不合法（）。
     *
     * <p>单独成码而不复用 {@code PARAM_INVALID}：前端要把这条错误直接标在
     * 「登录名」输入框下方，而 {@code PARAM_INVALID} 是几十个字段共用的兜底码，
     * 无法定位到具体控件。默认文案与说明逐字一致。
     */
    USERNAME_FORMAT_INVALID("USERNAME_FORMAT_INVALID", "登录名必须是5位以上纯数字", 400),

    /** 姓名格式不合法（：必须是纯中文） */
    REAL_NAME_FORMAT_INVALID("REAL_NAME_FORMAT_INVALID", "姓名必须是纯中文", 400),

    /**
     * 姓名对应多个账号（）。
     *
     * <p>重名是允许的（公司可能有多个张伟），但用它登录是歧义的。
     * 这里刻意<b>不</b>退化成「账号或密码错误」：那会让用户一遍遍试不同密码，
     * 而问题根本不在密码上。明确告诉他「请用数字登录名登录」才能自救。
     */
    ACCOUNT_NAME_AMBIGUOUS("ACCOUNT_NAME_AMBIGUOUS",
            "该姓名对应多个账号，请使用登录名（数字账号）登录", 400),

    // ---------------- 上线前需求（二 / 三 / 四 / 五 / 六）：找回密码与联系方式 ----------------

    /**
     * 按手机号 / 邮箱找不到账号（）。
     *
     * <p>文案刻意<b>不</b>写「账号不存在」：那等于对外提供了一个「这个手机号有没有注册」
     * 的查询接口，可以被批量枚举来反推员工手机号。这里的话术只陈述
     * 「没有账号绑定了这个号」，不透露任何关于号码本身的信息。
     */
    CONTACT_NOT_BOUND_TO_ACCOUNT("CONTACT_NOT_BOUND_TO_ACCOUNT",
            "未找到绑定该手机号/邮箱的账号", 400),

    /** 账号存在，但既没绑手机也没绑邮箱（）—— 无法投递验证码 */
    ACCOUNT_WITHOUT_CONTACT("ACCOUNT_WITHOUT_CONTACT",
            "该账号未绑定任何联系方式，请联系管理员重置密码", 400),

    /** 找回密码整体不可用（两个验证开关都关，） */
    FORGOT_PASSWORD_DISABLED("FORGOT_PASSWORD_DISABLED",
            "找回密码功能当前不可用，请联系管理员重置密码", 400),

    /** 该验证渠道已被管理员关闭（） */
    CONTACT_CHANNEL_DISABLED("CONTACT_CHANNEL_DISABLED",
            "该验证方式已被管理员关闭，请选择其他方式", 400),

    /** 账号没有绑定所选渠道对应的联系方式 */
    CONTACT_CHANNEL_UNBOUND("CONTACT_CHANNEL_UNBOUND",
            "该账号未绑定所选的验证方式，请选择其他方式", 400),

    /** 验证码错误、已过期或已被作废（） */
    VERIFY_CODE_INVALID("VERIFY_CODE_INVALID", "验证码错误或已过期，请重新获取", 400),

    /** 验证码连续输错达到上限，本次验证码已作废（：错 5 次作废） */
    VERIFY_CODE_LOCKED("VERIFY_CODE_LOCKED",
            "验证码错误次数过多，本次验证码已作废，请重新获取", 400),

    /** 手机号格式不正确（） */
    CONTACT_PHONE_INVALID("CONTACT_PHONE_INVALID", "手机号格式不正确，请输入 11 位手机号", 400),

    /** 邮箱格式不正确（） */
    CONTACT_EMAIL_INVALID("CONTACT_EMAIL_INVALID", "邮箱格式不正确，请检查后重试", 400),

    /** 手机号已被其他账号绑定（：全局唯一） */
    CONTACT_PHONE_EXISTS("CONTACT_PHONE_EXISTS", "该手机号已被其他账号绑定", 400),

    /** 邮箱已被其他账号绑定（：全局唯一） */
    CONTACT_EMAIL_EXISTS("CONTACT_EMAIL_EXISTS", "该邮箱已被其他账号绑定", 400),

    /** 手机号与邮箱都没填（：至少绑定一个） */
    CONTACT_REQUIRED("CONTACT_REQUIRED", "请至少填写手机号或邮箱", 400),

    // ---------------- ：在线一键升级 ----------------
    /**
     * 在线升级功能未启用（{@code app.upgrade.enabled=false}）。
     *
     * <p>生产默认关闭：该模块能替换服务器上的可执行文件，等价于代码执行能力。
     * 这里刻意**不降级成「只校验不应用」** —— 那样管理员会以为升级已经完成，
     * 而实际上什么都没换。宁可明确报错并告诉他去打开哪个开关。
     */
    UPGRADE_DISABLED("UPGRADE_DISABLED", "在线升级功能未启用，请在部署配置中开启 app.upgrade.enabled", 400),
    UPGRADE_PACKAGE_REQUIRED("UPGRADE_PACKAGE_REQUIRED", "请选择要上传的升级包", 400),
    UPGRADE_PACKAGE_TOO_LARGE("UPGRADE_PACKAGE_TOO_LARGE", "升级包超过大小上限", 400),
    /**
     * 升级包不是合法的 zip / 读取失败。
     *
     * <p>与 {@link #UPGRADE_MANIFEST_INVALID} 分开：那个是「zip 没错，但里面的
     * manifest.json 有问题」，用户要改的是打包脚本；这个是「连压都没压对」，
     * 用户要改的是产出流程。两者修的东西不同。
     */
    UPGRADE_PACKAGE_INVALID("UPGRADE_PACKAGE_INVALID", "升级包不是合法的 zip 文件或已损坏", 400),
    /** 包内缺少 manifest.json —— 说明不是本系统产出的升级包 */
    UPGRADE_MANIFEST_MISSING("UPGRADE_MANIFEST_MISSING",
            "升级包内缺少 manifest.json，请使用打包脚本产出升级包", 400),
    UPGRADE_MANIFEST_INVALID("UPGRADE_MANIFEST_INVALID", "manifest.json 格式不正确", 400),
    /**
     * 文件校验和不匹配 —— 包在传输 / 存储过程中损坏，或被篡改。
     *
     * <p>这是整条链路上<b>最不能放过</b>的一条：放它过去，换上去的就是一个起不来的
     * 进程，而这一刻通常发生在深夜、没人盯着的时候。message 会带上具体文件名。
     */
    UPGRADE_CHECKSUM_MISMATCH("UPGRADE_CHECKSUM_MISMATCH", "升级包内文件校验和不匹配，包可能已损坏", 400),
    /**
     * 包内存在不安全的条目路径（`../` 路径穿越 / 绝对路径 / 盘符 / 空字节）。
     *
     * <p>这就是 Zip Slip：不校验时，一个名为 {@code ../../etc/cron.d/x} 的条目
     * 会被解压到预期目录之外，写入任意位置。必须逐条拦截。
     */
    UPGRADE_ENTRY_UNSAFE("UPGRADE_ENTRY_UNSAFE", "升级包内含有不安全的文件路径，已拒绝", 400),
    /** 包内含有白名单之外的条目（只允许 manifest.json / backend.jar / frontend/dist/**） */
    UPGRADE_ENTRY_NOT_ALLOWED("UPGRADE_ENTRY_NOT_ALLOWED",
            "升级包内含有不允许的文件（仅允许 manifest.json、backend.jar 与 frontend/dist/）", 400),
    UPGRADE_ENTRY_TOO_MANY("UPGRADE_ENTRY_TOO_MANY", "升级包内文件数量超出上限", 400),
    /** 解压后体积超出上限（zip 炸弹防护） */
    UPGRADE_EXTRACT_TOO_LARGE("UPGRADE_EXTRACT_TOO_LARGE", "升级包解压后体积超出上限，已拒绝", 400),
    /** manifest 声明的版本号不合法 */
    UPGRADE_VERSION_INVALID("UPGRADE_VERSION_INVALID", "manifest 中的版本号不合法", 400),
    /** 包内缺少 backend.jar —— 只升前端也应显式声明，而不是静默放过 */
    UPGRADE_JAR_MISSING("UPGRADE_JAR_MISSING", "升级包内缺少 backend.jar", 400),

    UPGRADE_TASK_NOT_FOUND("UPGRADE_TASK_NOT_FOUND", "升级任务不存在", 404),
    /**
     * 已有进行中的升级任务（V30 的活跃唯一键兜底）。
     *
     * <p>刻意不做「自动顶掉前一个」：两个升级并行会各自解压到自己的 staging，
     * 再由外部脚本同时替换同一份产物 —— 最终留下的是「谁的最后一个文件」，
     * 即一个 jar 与 dist 来自不同版本的混合产物，而且没有任何日志能说明这件事。
     */
    UPGRADE_TASK_CONFLICT("UPGRADE_TASK_CONFLICT", "已有未完成的升级任务，请先完成或回滚后再发起", 400),
    UPGRADE_TASK_STATUS_INVALID("UPGRADE_TASK_STATUS_INVALID", "当前任务状态不允许该操作", 400),
    /** 外部应用命令启动失败（仅指「起不来」，脚本自身跑失败由它写回执告知） */
    UPGRADE_APPLY_COMMAND_FAILED("UPGRADE_APPLY_COMMAND_FAILED", "外部应用命令启动失败，请检查部署配置", 400),
    UPGRADE_ROLLBACK_DISABLED("UPGRADE_ROLLBACK_DISABLED", "回滚功能已被部署配置关闭", 400),
    /** 文件落盘 / 复制失败（磁盘满、权限不足、被占用） */
    UPGRADE_STORAGE_FAILED("UPGRADE_STORAGE_FAILED", "升级文件读写失败，请检查磁盘空间与目录权限", 500),

    // ---------------- ：主备双机热备配置 ----------------
    /**
     * 主备功能未启用（{@code ha_config.enabled = 0}）。
     *
     * <p>与 {@link #UPGRADE_DISABLED} 同取向：能力型功能默认关闭，要操作先显式打开。
     * 这里刻意<b>不</b>把「未启用」当成「配置为空」处理 —— 后者会让管理员以为
     * 系统默认就在跑主备，只是还没填参数。
     */
    HA_DISABLED("HA_DISABLED", "主备双机热备当前未启用，请先在「主备配置」页打开「启用主备」开关", 400),
    /** 启用主备前配置不完整（缺虚拟 IP 等必填项） */
    HA_CONFIG_INCOMPLETE("HA_CONFIG_INCOMPLETE", "主备配置不完整，无法启用", 400),
    /** 节点 IP 缺失或格式非法（当前仅支持 IPv4） */
    HA_NODE_IP_INVALID("HA_NODE_IP_INVALID", "节点 IP 地址不合法（当前仅支持 IPv4）", 400),
    /**
     * 该 IP 的节点已存在（{@code uk_ha_node_ip} 唯一键）。
     *
     * <p>单独成码而不是复用 PARAM_INVALID：前端要把这条错误直接标在
     * 「备机 IP」输入框下方，而 PARAM_INVALID 是几十个字段共用的兜底码。
     */
    HA_NODE_IP_EXISTS("HA_NODE_IP_EXISTS", "该 IP 的节点已登记，请勿重复添加", 400),
    HA_NODE_NOT_FOUND("HA_NODE_NOT_FOUND", "主备节点不存在", 404),
    /**
     * 不允许对本机节点执行该操作（删除 / 设为主节点等）。
     *
     * <p>把「本机」删掉会让「当前节点角色」失去依据，页面立刻退化为未知态 ——
     * 那不是一次操作失败，而是把一个可持续观察的状态永久破坏掉。
     */
    HA_SELF_NODE_FORBIDDEN("HA_SELF_NODE_FORBIDDEN", "不能对本机节点执行该操作", 400),
    /**
     * 部署资产（{@code deploy/ha}）在服务器上不存在或未配置。
     *
     * <p>沙箱 / 未挂载部署目录的环境必然命中这条。这是<b>刻意的诚实返回</b>：
     * 该能力会调用真实运维脚本（{@code switchover.sh} 等），
     * 脚本不存在时必须明确报错，绝不能静默返回「切换成功」——
     * 运维界面上的假成功比明确的失败危险得多。
     */
    HA_DEPLOY_DIR_MISSING("HA_DEPLOY_DIR_MISSING",
            "服务器上未找到主备部署资产目录（deploy/ha），无法执行真实运维动作；"
                    + "请按 deploy/DEPLOY.md 部署后再试", 400),
    /** 运维脚本不存在（目录在，但具体脚本缺失） */
    HA_SCRIPT_MISSING("HA_SCRIPT_MISSING", "服务器上未找到对应的主备运维脚本", 400),
    /** 运维脚本执行失败（脚本存在但退出码非 0，或启动失败） */
    HA_SCRIPT_FAILED("HA_SCRIPT_FAILED", "主备运维脚本执行失败", 500),
    /** 枚举节点失败 */
    HA_NODE_PERSIST_FAILED("HA_NODE_PERSIST_FAILED", "主备节点保存失败，请重试", 500),

    // ---------------- P0：数据库自动备份 ----------------
    /**
     * 已有备份正在执行。
     *
     * <p>回 400 而不是 409：调用方（备份记录页）只需要一句能直接展示的中文，
     * 而本项目的 {@code ApiResponse} 成功/失败判定统一走 {@code code} 字段，
     * 409 与 400 在这里对前端没有区别，反而多一个要记住的状态码。
     *
     * <p>⚠️ 注意与「备份执行失败」的区别：那条<b>不抛异常</b>——
     * mysqldump 失败是环境问题（目录不可写 / 磁盘满），
     * 应当落一条 FAILED 记录并告警，接口本身仍是「已受理」。
     * 若把环境失败也抛成 400，管理员会以为是自己填错了参数。
     */
    BACKUP_ALREADY_RUNNING("BACKUP_ALREADY_RUNNING", "已有备份正在执行，请等它结束后再试", 400),

    // ------------------------------------------------------------------
    // P2 设备盘点
    // ------------------------------------------------------------------
    INVENTORY_TASK_NOT_FOUND("INVENTORY_TASK_NOT_FOUND", "盘点任务不存在", 404),
    INVENTORY_STATE_INVALID("INVENTORY_STATE_INVALID", "盘点任务当前状态不允许该操作", 400),
    INVENTORY_SCOPE_INVALID("INVENTORY_SCOPE_INVALID", "盘点范围不正确，请重新选择", 400),
    INVENTORY_SCOPE_EMPTY("INVENTORY_SCOPE_EMPTY", "该范围内没有设备，请调整范围后重试", 400),
    INVENTORY_SCOPE_TOO_LARGE("INVENTORY_SCOPE_TOO_LARGE", "该范围内设备过多，请缩小范围后再创建", 400),
    INVENTORY_CHECK_RESULT_INVALID("INVENTORY_CHECK_RESULT_INVALID", "核对结果不正确", 400),
    INVENTORY_ITEM_NOT_FOUND("INVENTORY_ITEM_NOT_FOUND", "该设备不在本次盘点范围内", 404);

    private final String code;
    private final String defaultMessage;
    private final int httpStatus;

    ErrorCode(String code, String defaultMessage, int httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    public String getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
