package com.enterprise.ticket.common.flow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.module.department.entity.DepartmentManager;
import com.enterprise.ticket.module.department.entity.UserDepartment;
import com.enterprise.ticket.module.department.mapper.DepartmentManagerMapper;
import com.enterprise.ticket.module.department.mapper.UserDepartmentMapper;
import com.enterprise.ticket.module.department.service.DepartmentService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 审批人规则解析器——把"规则"解析成"具体的人"。
 *
 * <p>这是一期 {@code OrderServiceImpl#buildSnapshot} 的推广：一期把
 * {@code biz_group_approver.approver_id} 直接快照，这里则支持多种人员来源
 * （指定人员 / 角色 / 直属领导 / 部门审批人 / 处理小组 / 表单人员字段），
 * 解析后仍然**快照落库**，保证审批过程中的审批人不会因为组织变动而漂移。
 *
 * <h2>三条贯穿始终的收敛规则</h2>
 * <ol>
 *   <li><b>只认在职可用的人</b>：解析结果统一过滤 enabled + 非离职。
 *       解析为空时由调用方走超管兜底，而不是把"没人可审批"留给运行时；</li>
 *   <li><b>结果有序去重</b>：顺序稳定（按规则声明顺序 + 库内顺序），
 *       既方便测试断言，也保证同一份配置每次解析出完全相同的快照；</li>
 *   <li><b>自审回避</b>：{@code LEADER} 解析出的领导若就是申请人本人，视为解析失败
 *       （交给调用方走超管兜底），而不是让申请人自己审自己。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApproverRuleResolver {

    private final UserMapper userMapper;
    private final DepartmentManagerMapper departmentManagerMapper;
    private final UserDepartmentMapper userDepartmentMapper;
    private final DepartmentService departmentService;

    /**
     * 解析一批规则为具体审批人。
     *
     * <p>两类规则**不在此处理**：
     * <ul>
     *   <li>{@code APPLICANT_CHOOSE}：人由申请人在提交请求里给出，服务端只做范围与人数校验
     *       （见 {@code OrderServiceImpl}）；</li>
     *   <li>{@code PREV_ASSIGN}：人由**上一节点审批人**在通过时给出，提交时尚不可知。</li>
     * </ul>
     * 显式跳过它们，避免出现"规则说是自选 / 待指派、解析器却按池子全量塞进去"的错误行为。
     *
     * @param rules     节点上的规则列表
     * @param applicant 申请人（用于按部门取审批人、解析直属领导）
     * @param formData  表单数据（用于表单人员字段规则）
     */
    public List<Long> resolve(List<ApproverRule> rules, User applicant, Map<String, Object> formData) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        Set<Long> candidates = new LinkedHashSet<>();
        for (ApproverRule rule : rules) {
            ApproverRuleType type = ApproverRuleType.of(rule == null ? null : rule.getType());
            if (type == null
                    || type == ApproverRuleType.APPLICANT_CHOOSE
                    || type == ApproverRuleType.PREV_ASSIGN) {
                continue;
            }
            candidates.addAll(candidatesOf(rule, type, applicant, formData));
        }
        return activeUserIds(candidates);
    }

    // ------------------------------------------------------------------
    // 「上一节点指定审批人」的可选范围
    // ------------------------------------------------------------------

    /**
     * 该规则是否**限定了**指派范围。
     *
     * <p>{@code assignScope} 缺省（null / 空串）或配成 {@code ALL} 都表示"不限制"，
     * 此时服务端不额外收紧 —— 这正是存量流程的行为，不能因为新增了参数而改变。
     */
    public boolean assignScopeRestricted(ApproverRule rule) {
        ApproverRuleType.AssignScope scope =
                ApproverRuleType.AssignScope.of(rule == null ? null : rule.getAssignScope());
        return scope != null && scope != ApproverRuleType.AssignScope.ALL;
    }

    /**
     * 受限范围下的**候选池**（仅 {@code IT_EXECUTOR} 有意义）。
     *
     * <p>不限制时返回空集合，而不是"全部在职用户" —— 后者要在内存里物化全表人员，
     * 而调用方（候选接口 / 指派校验）在不受限时根本不需要枚举，只需要知道"不拦"。
     * 让返回值在"不受限"与"无人可选"两种情形下都为空的代价是调用方必须先问
     * {@link #assignScopeRestricted}；这是刻意的：把两种语义不同的空区分开，
     * 比让一个空集合同时表示两件事要安全。
     */
    public Set<Long> assignablePool(ApproverRule rule) {
        if (!assignScopeRestricted(rule)) {
            return Set.of();
        }
        // IT执行人候选 = IT执行人角色 ∪ 最终处理部门（IT运维组）成员。
        // 两者取并集：组织里常见"岗位挂在 IT运维组、但角色还没逐个改成 IT执行人"，
        // 只认一边会让候选列表突然空掉，而那是一种"配好了却没人可派"的静默故障。
        Set<Long> candidates = new LinkedHashSet<>();
        userMapper.selectList(Wrappers.<User>lambdaQuery()
                        .eq(User::getRole, RoleCode.IT_EXECUTOR)).stream()
                .map(User::getId)
                .forEach(candidates::add);
        candidates.addAll(departmentService.handlerDepartmentMemberIds());
        return new LinkedHashSet<>(activeUserIds(candidates));
    }

    /**
     * 某个用户是否落在该规则的指派范围内。
     *
     * <p><b>不限制时恒为 true</b>：范围校验器只负责回答"范围允许不允许"，
     * "是否在职 / 是否是申请人本人"由调用方按既有口径另行校验 ——
     * 两件事分开，才不会出现"配了 IT_EXECUTOR 就顺带丢了在职校验"这种漏洞。
     */
    public boolean isAssignable(ApproverRule rule, Long userId) {
        if (userId == null) {
            return false;
        }
        if (!assignScopeRestricted(rule)) {
            return true;
        }
        return assignablePool(rule).contains(userId);
    }

    /**
     * 申请人自选的可选池（校验"所选是否在范围内"时用）。
     *
     * <p>范围由规则参数决定：指定角色 / 指定部门 / 全部员工。
     * 池子与 {@link #resolve} 一样只含在职可用的人——否则申请人可以选到一个已离职账号，
     * 提交成功但工单永远批不动。
     */
    /*
     *  · W4-D 起，本方法在生产路径上**已无调用方**：范围校验改走 {@link #isChoosable}
     * （COUNT、零物化），预览下发改走 {@link #searchChoosable}（分页 + 首屏截断）。
     *
     * 保留它的理由只有一个：它是「全量物化」的**参照实现**，供单测与实机回归做等价对照。
     * 一个只被测试使用的生产方法必须写明这一点，否则下一个人会以为它是死代码而删掉，
     * 又或者误以为新代码该继续调它（那会把 W4-D 刚消掉的全表物化重新引入）。
     */
    public Set<Long> choosablePool(ApproverRule rule) {
        ApproverRuleType.ChooseScope scope = ApproverRuleType.ChooseScope.of(rule == null ? null : rule.getScope());
        if (scope == null) {
            return Set.of();
        }
        List<User> users = switch (scope) {
            case ROLE -> userMapper.selectList(Wrappers.<User>lambdaQuery()
                    .eq(User::getRole, rule.getScopeValue()));
            case GROUP -> {
                if (rule.getScopeValue() == null) {
                    yield List.of();
                }
                yield userMapper.selectList(Wrappers.<User>lambdaQuery()
                        .eq(User::getDepartmentId, parseId(rule.getScopeValue())));
            }
            case ALL -> userMapper.selectList(Wrappers.<User>lambdaQuery());
        };
        Set<Long> ids = new LinkedHashSet<>();
        for (User user : users) {
            if (active(user)) {
                ids.add(user.getId());
            }
        }
        return ids;
    }

    /**
     * 判断某个用户是否落在「申请人自选」的可选范围内（ · W4-D）——**COUNT 判定、零物化**。
     *
     * <h2>它取代了什么</h2>
     * <p>原先的写法是 {@code choosablePool(rule).contains(userId)}：为了一次「某人在不在范围内」
     * 的判定，把**整个池子**查出来物化进内存。{@code scope=ALL} 时那是**全部在职用户** ——
     * 提交一笔工单就要把全表人员拉进 JVM，代价与组织规模线性相关，而真正需要的只是一个布尔。
     *
     * <h2>为什么可以与 {@code choosablePool} 同源</h2>
     * <p>两者读同一张表、同一组范围条件（{@link #choosableWrapper} 是唯一出处），
     * 在职过滤的口径也一致：{@code active()} 为「enabled 为真且 dimission 不为真」，
     * 这里下推成 {@code enabled = 1 AND (is_dimission IS NULL OR is_dimission = 0)}。
     * 差别只在「取集合」还是「数一行」。
     *
     * @return 该用户可被选为审批人时为 {@code true}；用户为空 / 范围非法 / 不在范围内均为 {@code false}
     */
    public boolean isChoosable(ApproverRule rule, Long userId) {
        if (userId == null) {
            return false;
        }
        ApproverRuleType.ChooseScope scope = ApproverRuleType.ChooseScope.of(rule == null ? null : rule.getScope());
        if (scope == null || unresolvableGroupScope(rule, scope)) {
            return false;
        }
        return userMapper.selectCount(choosableWrapper(rule, scope, null)
                .eq(User::getId, userId)) > 0;
    }

    /**
     * 分页搜索「申请人自选」可选范围内的用户（ · W4-D）。
     *
     * <p>预览响应里只给首屏若干条（见 {@code ApplyTypeServiceImpl} 的候选人截断），
     * 因此必须有一条「按关键字翻页」的通路 —— **只截断不给搜索通路会真的选不到人**，
     * 那是功能回归而不是优化。这个判断是 W4-D 方案里被专门纠正过一次的落点。
     *
     * <p>范围条件与 {@link #isChoosable} 共用 {@link #choosableWrapper}，因此
     * 「搜索搜得到的人」恒等于「服务端肯收的人」的子集 —— 不会出现"能搜到却提交被拒"。
     *
     * @param keyword 姓名 / 登录名模糊匹配；空白表示不过滤
     * @param page    页码（从 1 起）
     * @param size    每页条数
     */
    public IPage<User> searchChoosable(ApproverRule rule, String keyword, long page, long size) {
        ApproverRuleType.ChooseScope scope = ApproverRuleType.ChooseScope.of(rule == null ? null : rule.getScope());
        if (scope == null || unresolvableGroupScope(rule, scope)) {
            // 范围非法 = 这个选择器本来就不该被渲染（发布校验会拦下这种配置）。
            // 返回空页而不是抛错：调用方把它当「没有候选」处理即可，UI 显示空列表，无需弹错。
            return new Page<>(page, size, 0);
        }
        return userMapper.selectPage(new Page<>(page, size),
                choosableWrapper(rule, scope, keyword).orderByAsc(User::getId));
    }

    /**
     * 可选范围的查询条件（{@link #isChoosable} 与 {@link #searchChoosable} 唯一共用出处）。
     *
     * <p>把范围条件与在职条件集中在一处，是「两条通路不会各自漂移」的结构保证：
     * 分开写两份 {@code switch} 迟早会出现「校验肯收的人」与「搜索给出的人」不一致 ——
     * 那正是本项目在 M4a / C8 反复治理的那类双份事实源。
     */
    private LambdaQueryWrapper<User> choosableWrapper(ApproverRule rule, ApproverRuleType.ChooseScope scope,
                                                     String keyword) {
        LambdaQueryWrapper<User> query = Wrappers.<User>lambdaQuery()
                .eq(User::getEnabled, true)
                .and(w -> w.isNull(User::getDimission).or().eq(User::getDimission, false));
        switch (scope) {
            case ROLE -> query.eq(User::getRole, rule.getScopeValue());
            case GROUP -> query.eq(User::getDepartmentId, parseId(rule.getScopeValue()));
            case ALL -> {
                // 全部在职员工：不加范围条件
            }
        }
        if (StringUtils.hasText(keyword)) {
            String trimmed = keyword.trim();
            // LIKE 的 OR 组必须用 and(...) 包住，否则 OR 会把上面的范围 / 在职条件整条「漏」掉 ——
            // 那会把「按角色限定的候选人」变成「全库模糊匹配」，是最危险的一类查询缺陷。
            query.and(w -> w.like(User::getUsername, trimmed)
                    .or().like(User::getRealName, trimmed)
                    .or().like(User::getDisplayName, trimmed));
        }
        return query;
    }

    /** 范围=指定分组但分组 id 解析不出来（脏配置）：无可选人，两条通路都直接短路 */
    private static boolean unresolvableGroupScope(ApproverRule rule, ApproverRuleType.ChooseScope scope) {
        return scope == ApproverRuleType.ChooseScope.GROUP && parseId(rule.getScopeValue()) == null;
    }

    // ------------------------------------------------------------------
    // 各来源的具体取数
    // ------------------------------------------------------------------

    private Collection<Long> candidatesOf(ApproverRule rule, ApproverRuleType type,
                                          User applicant, Map<String, Object> formData) {
        return switch (type) {
            case SPECIFIC_USER -> rule.getUserIds() == null ? List.of() : rule.getUserIds();
            case ROLE -> userMapper.selectList(Wrappers.<User>lambdaQuery()
                            .eq(User::getRole, rule.getRoleCode())).stream()
                    .map(User::getId).toList();
            case LEADER -> leaderCandidates(applicant);
            case BIZ_GROUP_APPROVERS -> departmentManagers(applicant);
            case PARENT_DEPT_APPROVERS -> parentDepartmentManagers(applicant);
            case HANDLER_GROUP -> handlerDepartmentMembers(rule.getHandlerGroupId());
            case FORM_USER_FIELD -> formUserField(rule.getFieldKey(), formData);
            case APPLICANT_CHOOSE, PREV_ASSIGN -> List.of();
        };
    }

    /**
     * 申请人直属领导。
     *
     * <p>返回空集合即代表"解析不出领导"，由调用方走超管兜底。返回空的三种情形：
     * <ul>
     *   <li>申请人未配置 {@code leader_id}；</li>
     *   <li><b>领导就是申请人本人</b>（自审回避）—— 这里直接剔除，不让它进入候选；</li>
     *   <li>领导已离职 / 账号停用 —— 由 {@link #activeUserIds} 在后续统一过滤。</li>
     * </ul>
     */
    private List<Long> leaderCandidates(User applicant) {
        if (applicant == null || applicant.getLeaderId() == null) {
            return List.of();
        }
        if (Objects.equals(applicant.getLeaderId(), applicant.getId())) {
            log.info("申请人 {} 的直属领导配置为本人，按「未配置」处理（自审回避）", applicant.getId());
            return List.of();
        }
        return List.of(applicant.getLeaderId());
    }

    /**
     * 申请人所在**部门**的部门主管。
     *
     * <p><b>数据源换过、语义没换</b>：改造前读 {@code biz_group_approver}
     * （由管理员在「业务分组」里手工排一列审批人），改造后读 {@code department_manager}
     * （由「组织与人员」里把某人设为部门主管而来）。两者都回答同一个问题：
     * 「这个部门的一级审批人是谁」。
     *
     * <p>返回多人时按 {@code user_id} 升序，保证同一份配置每次解析出相同的顺序 ——
     * 快照要落库，顺序不稳定会让同一笔单在不同环境产生不同的审批链。
     */
    private List<Long> departmentManagers(User applicant) {
        if (applicant == null || applicant.getDepartmentId() == null) {
            return List.of();
        }
        return departmentManagerMapper.selectList(Wrappers.<DepartmentManager>lambdaQuery()
                        .eq(DepartmentManager::getDepartmentId, applicant.getDepartmentId())
                        .orderByAsc(DepartmentManager::getUserId)).stream()
                .map(DepartmentManager::getUserId)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 申请人所在部门的**上级**部门的部门主管。
     *
     * <p>的金额分档用它作第四级：「设备金额 &gt; 5000 元时加一级上级部门主管审批」。
     * 只比 {@link #departmentManagers} 多走一层 {@code parent_id}，但语义主体不同 ——
     * 前者答"我部门的负责人"，后者答"管我部门的那一层负责人"。
     *
     * <p>三种"解析不出"都返回空集合，交由调用方走超管兜底（与其它规则同一契约）：
     * <ul>
     *   <li>申请人没有部门；</li>
     *   <li><b>申请人已在根部门</b>（没有上级部门）—— 根部门之上无人可批，
     *       硬造一级等于凭空指向一个不存在的组织；</li>
     *   <li>上级部门没有配主管 —— 由 {@link #activeUserIds} 之后的空结果触发兜底。</li>
     * </ul>
     */
    private List<Long> parentDepartmentManagers(User applicant) {
        if (applicant == null || applicant.getDepartmentId() == null) {
            return List.of();
        }
        Long parentId = departmentService.parentIdOf(applicant.getDepartmentId());
        if (parentId == null) {
            log.info("申请人 {} 所在部门 {} 已是根部门，无上级部门主管，「上级部门主管」规则按解析为空处理",
                    applicant.getId(), applicant.getDepartmentId());
            return List.of();
        }
        return departmentService.managerIdsOf(parentId);
    }

    /**
     * 最终处理部门（IT运维组）的成员。
     *
     * <p>参数名仍是 {@code handlerGroupId}（规则 JSON 里就叫这个，改名要动存量流程定义），
     * 但语义已经是**部门 id** —— 指向 {@code user_department} 里挂在该部门下的人。
     */
    private List<Long> handlerDepartmentMembers(Long handlerGroupId) {
        if (handlerGroupId == null) {
            return List.of();
        }
        return userDepartmentMapper.selectList(Wrappers.<UserDepartment>lambdaQuery()
                        .eq(UserDepartment::getDepartmentId, handlerGroupId)).stream()
                .map(UserDepartment::getUserId)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 表单「人员选择」字段的值。
     *
     * <p>兼容三种存法：单个数字 id、数字字符串、id 数组（多选人员）。
     * 之所以要兼容，是因为表单数据来自用户填写，同一字段在不同场景下
     * 可能被前端写成不同形态；这里统一归一，比在下游到处 {@code instanceof} 更可靠。
     */
    private List<Long> formUserField(String fieldKey, Map<String, Object> formData) {
        if (fieldKey == null || formData == null) {
            return List.of();
        }
        Object raw = formData.get(fieldKey);
        if (raw == null) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        if (raw instanceof Collection<?> collection) {
            collection.forEach(item -> addId(ids, item));
        } else {
            addId(ids, raw);
        }
        return ids;
    }

    private void addId(List<Long> target, Object value) {
        if (value == null) {
            return;
        }
        Long id = parseId(String.valueOf(value));
        if (id != null) {
            target.add(id);
        }
    }

    private static Long parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 在职过滤
    // ------------------------------------------------------------------

    /** 过滤出"在职且启用"的 user_id，保持输入顺序 */
    private List<Long> activeUserIds(Collection<Long> candidates) {
        List<Long> ids = candidates.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, User> users = userMapper.selectBatchIds(ids).stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, user -> user, (a, b) -> a));
        List<Long> result = new ArrayList<>();
        for (Long id : ids) {
            User user = users.get(id);
            if (user == null) {
                log.warn("审批人规则引用的用户 {} 不存在，已忽略", id);
                continue;
            }
            if (!active(user)) {
                log.info("审批人规则引用的用户 {}（{}）已离职/禁用，已忽略（由调用方决定是否走超管兜底）",
                        id, user.getUsername());
                continue;
            }
            result.add(id);
        }
        return result;
    }

    /**
     * 判断用户是否"在职可用"（供本类与外部兜底逻辑共用同一口径）。
     *
     * <p>公开是为了让 Order 侧在处理 PREV_ASSIGN 的「指派后离职」兜底时，
     * 不必复制一份判断条件 —— 一处口径，两处使用。
     */
    public static boolean isActive(User user) {
        return active(user);
    }

    private static boolean active(User user) {
        return user != null
                && Boolean.TRUE.equals(user.getEnabled())
                && !Boolean.TRUE.equals(user.getDimission());
    }
}
