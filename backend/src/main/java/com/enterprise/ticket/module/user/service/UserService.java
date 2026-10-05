package com.enterprise.ticket.module.user.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.enterprise.ticket.common.api.BatchResultVO;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.module.user.dto.UserBatchDepartmentRequest;
import com.enterprise.ticket.module.user.dto.UserCreateRequest;
import com.enterprise.ticket.module.user.dto.UserOptionVO;
import com.enterprise.ticket.module.user.dto.UserPageQuery;
import com.enterprise.ticket.module.user.dto.UserUpdateRequest;
import com.enterprise.ticket.module.user.dto.vo.DimissionResultVO;
import com.enterprise.ticket.module.user.dto.vo.UserAccountVO;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.security.LoginUser;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 员工服务
 */
public interface UserService extends IService<User> {

    /**
     * 按登录名（员工姓名）查询
     */
    User getByUsername(String username);

    /**
     * 按主键查询，不存在抛 USER_NOT_FOUND
     */
    User getByIdRequired(Long userId);

    /**
     * 加载登录主体；用户不存在 / 已禁用 / 已离职 返回 null
     */
    LoginUser loadLoginUser(Long userId);

    /**
     * 生成全局唯一的登录名（ ：5 位以上纯数字，10001 起顺序编号）。
     *
     * <p>不再是「姓名 + _2 后缀」—— 姓名允许重复，登录名必须唯一且不可含中文。
     */
    String generateUniqueUsername();

    /**
     * 更新最后登录时间
     */
    void touchLastLoginAt(Long userId);

    /**
     * 写入密码（Argon2id 哈希）并可选清除强制改密标记
     */
    void updatePassword(Long userId, String rawPassword, boolean clearForceChangeFlag);

    /**
     * 判断原始密码是否与库中哈希匹配
     */
    boolean matchesPassword(User user, String rawPassword);

    // ------------------------------------------------------------------
    // 令牌版本号（需求方三波·第一波·）
    // ------------------------------------------------------------------

    /**
     * 递增用户的令牌版本号，使其全部已签发 Token 立即失效。
     *
     * <p>触发时机：修改密码、管理员重置密码、必要时的人工强制下线。
     *
     * <p>用原子 SQL（{@code token_version = token_version + 1}）而不是「读出来 +1 再写回」：
     * 后者在并发下会丢失更新，导致两次作废只生效一次（被作废的会话悄悄复活）。
     *
     * @return 递增后的新版本号（供调用方签发新 Token）
     */
    int bumpTokenVersion(Long userId);

    /**
     * 读取用户当前令牌版本号（不存在按 0 处理）。
     *
     * <p>单独开一个只读方法，是为了让登录路径不必把整个 {@code User} 传进来 ——
     * 登录时手上只有用户实体，而版本号可能刚被并发改过，多读一次更稳妥。
     */
    int currentTokenVersion(Long userId);

    /**
     * 查询某部门下的全部员工（ 分组成员）
     */
    List<User> listByDepartment(Long departmentId);

    /**
     * 员工下拉选项（含所属分组名称），供审批人选择 / 分组成员 / 最终小组成员维护复用。
     *
     * @param keyword    姓名或登录名模糊匹配，可为空（为空返回全部，上限 {@value #MAX_OPTION_SIZE} 条）
     * @param departmentId 仅返回该分组的成员，可为空
     */
    List<UserOptionVO> listOptions(String keyword, Long departmentId);

    /**
     * 在职启用员工的选项列表，可选按 id 收窄。
     *
     * <p>与 {@link #listOptions(String, Long)} 的差别有两点，都是刻意的：
     * <ol>
     *   <li><b>只返回在职启用的人</b>。调用方（审批人指派候选）需要一个「选了就能用」的集合 ——
     *       给离职账号会让上一节点审批人指派成功、工单随后卡在无法处理的节点上；</li>
     *   <li><b>不接受 keyword / departmentId</b>，只接受 id 白名单。因为本方法服务于
     *       「按流程定义算出来的候选池」这一种用法，池子由 {@code ApproverRuleResolver} 决定，
     *       再叠加一层关键词过滤只会让"池子里的这个人为什么没出现"变得难以解释。</li>
     * </ol>
     *
     * <p><b>参数 null 与空集合语义不同，调用方必须区分</b>：
     * {@code ids == null} 表示"不限定范围"（返回全部在职启用员工）；
     * {@code ids} 为空集合表示"在这个范围内没有人"，返回空列表。
     * 二者不能在实现里合并 —— 把空集合当成 null 正是"配了 IT执行人却没人，
     * 于是候选列表突然变成全员"这类静默越权的来源。
     *
     * @param ids 限定的员工 id 集合；{@code null} 表示不限定
     */
    List<UserOptionVO> listActiveOptions(Collection<Long> ids);

    /**
     * 批量设置员工的归属分组（：员工仅能属于一个分组，添加时自动从原分组移出）
     *
     * @param userIds    目标员工
     * @param departmentId 目标分组；传 null 表示移出分组
     */
    void assignDepartment(Collection<Long> userIds, Long departmentId);

    /**
     * 统计某分组下「启用且在职」的成员数
     */
    long countActiveByDepartment(Long departmentId);

    /**
     * 按部门聚合成员数量（一次查询，避免逐个分组统计造成 N+1）
     *
     * @return 分组ID → 成员数；未分配分组的员工不出现在结果中
     */
    Map<Long, Long> countGroupedByDepartment();

    /** 员工下拉选项单次返回上限，避免误触发全量导出 */
    int MAX_OPTION_SIZE = 500;

    // ------------------------------------------------------------------
    // ：轻量版员工管理 + 离职联动
    // ------------------------------------------------------------------

    /**
     * 员工账号分页（仅 super_admin 使用）
     *
     * <p>同时返回「名下使用中设备数」，供「标记离职」的二次确认提示直接引用。
     */
    PageResult<UserAccountVO> pageAccounts(UserPageQuery query);

    /**
     * 标记员工离职（ + 需求方  ）
     *
     * <p>事务内完成三件事：
     * <ol>
     *   <li>账号禁用（{@code enabled = false}）并置 {@code is_dimission = 1}、记录 {@code dimission_at}
     *       —— 离职后无法再登录、不允许再提交/审批/交付/归还；</li>
     *   <li>把该员工名下所有「使用中」的工单<b>推进为「待收回」</b>并标记
     *       {@code return_trigger = DIMISSION}，向对应实际执行人推送回收通知；</li>
     *   <li>不新建归还单 —— 需求方已确认：归还只是借用流程的一个状态阶段，
     *       复用原借用单才能保持「一台设备只有一笔在办工单」的不变量。</li>
     * </ol>
     * 名下没有使用中工单时只停用账号、不产生任何归还单。
     */
    DimissionResultVO markDimission(Long userId);

    /**
     * 恢复在职（需求方  轻量版员工管理）
     *
     * <p>仅恢复账号状态（{@code enabled = true}、{@code is_dimission = 0}、清空离职时间），
     * <b>不自动恢复设备</b>：已回收的设备需重新走借用流程，未完成的归还流程也不会回退。
     */
    DimissionResultVO reinstate(Long userId);

    // ------------------------------------------------------------------
    // 员工管理增强（需求方 2026-09-18 小迭代 · ）
    //
    // 全部写操作仅 super_admin 可调用（Controller 层 @PreAuthorize 强制），
    // admin 只能查看列表 —— 与需求「仅 super_admin 能写操作，admin 只查看」一致。
    // ------------------------------------------------------------------

    /**
     * 新增员工（）
     *
     * <p>校验链（顺序即失败原因的优先级）：<b>姓名唯一</b> → <b>登录名唯一</b> → 角色合法
     * → 部门存在 → 密码策略。
     *
     * <p>新建账号一律 {@code force_change_password = true}：管理员设置的只是初始密码，
     * 员工首次登录必须自行修改。
     *
     * @return 新建后的账号视图（供前端直接刷新列表或提示）
     */
    UserAccountVO createUser(UserCreateRequest request);

    /**
     * 新增员工（只写入，返回新 {@code user_id}）。
     *
     * <p>{@link #createUser(UserCreateRequest)} 是本方法 + 账号视图装配。
     * 单独暴露本方法是为了让<b>批量导入</b>逐行调用时不必为每一行都去统计
     * 「名下使用中设备数 / 在途审批数 / 分组名称」——那会让 500 行的导入凭空多出上千次查询。
     * 两条入口共用同一个写入实现，规则不会漂移。
     */
    Long insertUser(UserCreateRequest request);

    /**
     * 编辑员工（）
     *
     * <p>可改：姓名、角色、部门、显示名称。<b>登录名不可改</b> ——
     * {@link UserUpdateRequest} 里根本没有该字段，属于结构性限制而非运行时判断。
     *
     * <p>姓名同样要过唯一性校验（排除自己）。
     */
    UserAccountVO updateUser(Long userId, UserUpdateRequest request);

    /**
     * 重置员工密码（；2026-09-20  改造）
     *
     * <p><b>临时口令由服务端生成，并通过返回值交回调用方</b>：管理员无从知晓系统
     * 生成的串，若不回传就等于把该员工锁在门外（旧口令已失效、新口令无人知晓）。
     * 该口令只在本次返回中出现，事后任何接口都查不到。
     *
     * <p>副作用：
     * <ul>
     *   <li>置 {@code force_change_password = true} —— 员工下次登录必须改密；</li>
     *   <li>{@code token_version + 1} —— 该员工全部既有会话<b>立即失效</b>。
     *       被重置的人往往正处于「账号可能已泄露」的场景，只改口令而不作废旧 Token，
     *       等于给入侵者留了一把仍在有效期内的备用钥匙；</li>
     *   <li>发送一条含临时口令的站内消息。</li>
     * </ul>
     *
     * <p>超管的密码不可被重置（项目约定：超管登录名固定、口令由本人设定）；
     * AD 域账号的密码只能在域控修改，本地重置一律拒绝（{@code AD_PASSWORD_MANAGED_BY_AD}）。
     *
     * @param userId 目标员工
     * @return 系统生成的临时口令（仅本次返回，调用方负责展示与转交）
     */
    String resetPassword(Long userId);

    /**
     * 启用 / 禁用账号（）
     *
     * <p>与「离职」是两件事：本方法只动 {@code enabled}，不碰 {@code is_dimission}。
     * 因此「禁用」不等于「离职」（不触发工单回收与自动转交），
     * 而「启用」也不等于「恢复在职」（离职账号走 {@link #reinstate(Long)}）。
     *
     * <p>自我保护：不可禁用当前登录账号；超管账号不可被禁用。
     */
    UserAccountVO setEnabled(Long userId, boolean enabled);

    /**
     * 登录名唯一性预检（ / 一.2 共用）
     *
     * @throws com.enterprise.ticket.common.exception.BusinessException 登录名已存在（{@code USER_USERNAME_EXISTS}）
     */
    void assertUsernameAvailable(String username);

    /** 校验角色取值合法（super_admin / admin / user），非法抛 USER_ROLE_INVALID */
    void assertRoleValid(String role);

    /**
     * 校验部门存在并返回其 ID；为空抛 {@code USER_WITHOUT_DEPARTMENT}，不存在抛 {@code DEPARTMENT_NOT_FOUND}
     */
    Long requireDepartmentId(Long departmentId);

    /** 分组名 → 分组ID（批量导入按名称匹配分组用；不存在返回 null） */
    Long findDepartmentIdByName(String groupName);

    /** 按主键查询账号视图（不存在抛 USER_NOT_FOUND） */
    UserAccountVO getAccount(Long userId);

    // ------------------------------------------------------------------
    // 上线前需求（九）：姓名与登录名的格式规则
    // ------------------------------------------------------------------

    /**
     * 校验登录名格式：5 位以上纯数字（）。
     *
     * <p>失败抛 {@code USERNAME_FORMAT_INVALID}，默认文案「登录名必须是5位以上纯数字」。
     * 与 {@link #assertUsernameAvailable(String)} 是两件事、且顺序有讲究：
     * <b>先格式、后唯一</b> —— 否则用户输入「abc」时会先被告知「登录名已存在」，
     * 而真正的问题是他填的东西根本不能当登录名。
     */
    void assertUsernameFormat(String username);

    /**
     * 校验姓名格式：纯中文（）。
     *
     * <p>失败抛 {@code REAL_NAME_FORMAT_INVALID}。
     * 姓名允许重复（公司可能有多个张伟），因此这里只判格式，不判唯一 ——
     * 历史上曾有一条 {@code assertRealNameAvailable}（姓名唯一预检），
     *  明确「姓名可以重复」后已整体移除：唯一性从「姓名的属性」
     * 变成了「登录名的属性」。
     */
    void assertRealNameFormat(String realName);

    // ------------------------------------------------------------------
    // 上线前需求（九）：账号识别（登录 / 找回密码共用）
    // ------------------------------------------------------------------

    /**
     * 按「登录标识」查账号 —— 供<b>登录</b>使用（）。
     *
     * <p>接受的形态只有两种：纯数字（按 {@code username} 查）与其它（按 {@code real_name} 查）。
     * 刻意不接受手机号 / 邮箱：登录页的文案只承诺这两种方式，
     * 悄悄多支持两种只会让「为什么这里能登进去、那里不能」变成需要解释的问题。
     *
     * @return 账号；不存在返回 {@code null}（由调用方统一走「时序对齐 + 含糊错误码」的处置）
     * @throws com.enterprise.ticket.common.exception.BusinessException
     *         姓名对应多个账号（{@code ACCOUNT_NAME_AMBIGUOUS}）
     */
    User findByLoginAccount(String account);

    /**
     * 按「账号标识」查账号 —— 供<b>找回密码 / 首次绑定</b>使用（）。
     *
     * <p>自动识别四种形态：11 位手机号 → {@code phone} 列；含 {@code @} → {@code email} 列；
     * 5 位以上纯数字 → {@code username} 列；其余 → {@code real_name} 列。
     * 识别顺序见 {@code AccountFormats#shapeOf}。
     *
     * <p><b>找不到时的报错分两种，这是刻意的</b>：
     * <ul>
     *   <li>按手机号 / 邮箱查不到 → {@code CONTACT_NOT_BOUND_TO_ACCOUNT}（「未找到绑定该
     *       手机号/邮箱的账号」）。不能写「账号不存在」—— 那会把这个接口变成
     *       「某手机号有没有注册」的枚举工具；</li>
     *   <li>按登录名 / 姓名查不到 → {@code USER_NOT_FOUND}。登录名与姓名本来就
     *       不是秘密（姓名是要被人叫的），含糊其辞只会让用户困惑。</li>
     * </ul>
     */
    User findByAccount(String account);

    /** 按姓名精确查询（可能多条；重名是允许的）。供测试与重名提示文案使用 */
    List<User> listByRealName(String realName);

    // ------------------------------------------------------------------
    // 上线前需求（二 / 三 / 四）：联系方式绑定与唯一性
    // ------------------------------------------------------------------

    /**
     * 绑定 / 修改联系方式（：首次绑定向导；：个人中心自助改绑）。
     *
     * <p>语义：<b>传入的非空字段才更新，未传的字段保持原值</b>。
     * 刻意不提供「清空某一项」的能力 —— 清空手机 / 邮箱会让该账号失去找回密码的能力，
     * 那是「超管重置密码」这类管理动作的副作用，不该是个自助页面上的一个删除按钮。
     *
     * @param phone 手机号；{@code null} 或空白表示「不改这一项」
     * @param email 邮箱；同上
     * @return 更新后的账号
     * @throws com.enterprise.ticket.common.exception.BusinessException
     *         两者都没传（{@code CONTACT_REQUIRED}）、格式非法、或已被他人绑定
     */
    User bindContact(Long userId, String phone, String email);

    /**
     * 清空手机号与邮箱（：超管重置密码时必须一并清空）。
     *
     * <p>为什么重置密码要清空联系方式：重置的语义是「这个账号可能已经泄露，
     * 由管理员重新掌握」。此时保留原手机号 / 邮箱，等于让可能已失控的旧联系方式
     * 继续持有「自助找回密码」的能力 —— 那重置就只是把口令换了一把，
     * 恢复通道还开在原来的地方。清空后员工下次登录会被引导重新绑定（）。
     */
    void clearContacts(Long userId);

    /** 手机号唯一性预检（排除自己）；为空则跳过。失败抛 {@code CONTACT_PHONE_EXISTS} */
    void assertPhoneAvailable(String phone, Long excludeUserId);

    /** 邮箱唯一性预检（排除自己）；为空则跳过。失败抛 {@code CONTACT_EMAIL_EXISTS} */
    /** 邮箱唯一性预检（排除自己）；为空则跳过。失败抛 {@code CONTACT_EMAIL_EXISTS} */
    void assertEmailAvailable(String email, Long excludeUserId);

    /**
     * 批量调整员工所属部门（P3）
     *
     * <p>只改归属，不重算在途工单的审批人快照（对**新提交**的工单生效）；
     * 逐条独立处理，结果里给出失败明细。
     */
    BatchResultVO batchChangeDepartment(UserBatchDepartmentRequest request);
}
