package com.enterprise.ticket.common.flow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 「申请人自选」范围判定的零物化改造（Wave 4 · W4-D）。
 *
 * <h2>本类锁定的缺陷</h2>
 * <p>原实现 {@code choosablePool(rule).contains(userId)} 为了回答「某个人在不在可选范围内」，
 * 把**整个池子**查出来物化进内存。{@code scope=ALL}（全部在职员工）时，</b>提交一笔工单</b>
 * 就要把全表人员拉进 JVM —— 代价与组织规模线性相关，而真正需要的只是一个布尔。
 *
 * <p>改造后分两条通路，职责完全不同：
 * <ul>
 *   <li>{@link ApproverRuleResolver#isChoosable} —— 校验用，COUNT 判定，**零物化**；</li>
 *   <li>{@link ApproverRuleResolver#searchChoosable} —— 展示用，分页 + 关键字，
 *       为「预览只给首屏」提供配套的完整列表通路。</li>
 * </ul>
 *
 * <h2>为什么断言落在 SQL 片段/绑定参数上而不是返回值上</h2>
 * <p>这两个方法的价值就是「把什么下推到数据库」，返回值反而最好构造。因此这里捕获
 * {@code Wrapper} 并检查：① 有没有走 {@code selectList}（物化的唯一入口）；② 范围条件是否
 * 真的绑到了 SQL（而不是在 Java 里过滤）；③ LIKE 的 OR 组有没有被 {@code and(...)} 包住。
 *
 * <h2>等价性怎么证明</h2>
 * <p>单测能证明的是「两条通路读同一张表、绑同一组范围条件」（{@code choosableWrapper} 唯一出处）。
 * 「与原 {@code choosablePool} 逐人等价」需要真库比对，落在实机回归
 * {@code .docs/_w4d-verify.sh}（同一批人分别走 COUNT 与全量池，断言集合一致）。
 */
@ExtendWith(MockitoExtension.class)
class ApproverRuleResolverChoosableTest {

    private static final Long USER_ID = 7L;

    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(User.class);
    }

    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private ApproverRuleResolver resolver;

    // ------------------------------------------------------------------
    // 1. isChoosable：COUNT 判定，绝不物化
    // ------------------------------------------------------------------

    @Test
    @DisplayName("isChoosable 只发 COUNT，从不调用 selectList（零物化的核心断言）")
    void isChoosable_neverMaterializesPool() {
        when(userMapper.selectCount(any())).thenReturn(1L);

        assertTrue(resolver.isChoosable(rule("ALL", null), USER_ID));

        verify(userMapper, times(1)).selectCount(any());
        verify(userMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("命中范围返回 true，未命中返回 false（同一通路两种结果）")
    void isChoosable_mapsCountToBoolean() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        assertFalse(resolver.isChoosable(rule("ALL", null), USER_ID));

        when(userMapper.selectCount(any())).thenReturn(3L);
        assertTrue(resolver.isChoosable(rule("ALL", null), USER_ID));
    }

    @Test
    @DisplayName("指定角色：范围条件真的下推到了 SQL（role 列 + id + 在职条件）")
    void isChoosable_roleScopePushesRolePredicate() {
        when(userMapper.selectCount(any())).thenReturn(1L);

        resolver.isChoosable(rule("ROLE", "admin"), USER_ID);

        String sql = compactSql(capturedCountWrapper().getSqlSegment());
        assertTrue(sql.contains("role ="),
                "角色必须作为 SQL 条件下推，而不是取回全表在 Java 里过滤。实际：" + sql);
        assertTrue(sql.contains("id ="), "user_id 必须下推。实际：" + sql);
        assertTrue(sql.contains("enabled ="), "在职条件必须下推。实际：" + sql);
        assertTrue(sql.contains("is_dimission"), "离职标记必须下推。实际：" + sql);
    }

    @Test
    @DisplayName("指定分组：scopeValue 解析成 id 后下推（而不是在 Java 里过滤）")
    void isChoosable_groupScopePushesGroupPredicate() {
        when(userMapper.selectCount(any())).thenReturn(1L);

        resolver.isChoosable(rule("GROUP", "42"), USER_ID);

        String sql = compactSql(capturedCountWrapper().getSqlSegment());
        assertTrue(sql.contains("department_id ="), "分组条件必须下推。实际：" + sql);
        assertFalse(sql.contains("role ="), "指定分组不该附带角色条件。实际：" + sql);
    }

    @Test
    @DisplayName("全部员工：不附加任何范围条件（只有 user_id + 在职条件）")
    void isChoosable_allScopeAddsNoRangePredicate() {
        when(userMapper.selectCount(any())).thenReturn(1L);

        resolver.isChoosable(rule("ALL", null), USER_ID);

        String sql = compactSql(capturedCountWrapper().getSqlSegment());
        assertTrue(sql.contains("id ="), "实际：" + sql);
        assertFalse(sql.contains("role ="), "ALL 域不该限定角色。实际：" + sql);
        assertFalse(sql.contains("department_id ="), "ALL 域不该限定分组。实际：" + sql);
    }

    @Test
    @DisplayName("分组 id 解析不出来（脏配置）→ 直接 false，一次库都不查")
    void isChoosable_dirtyGroupValue_returnsFalseWithoutQuery() {
        assertFalse(resolver.isChoosable(rule("GROUP", "not-a-number"), USER_ID));

        verifyNoInteractions(userMapper);
    }

    @Test
    @DisplayName("userId 为空 / 范围未知 / 规则为空 → false，且不查库")
    void isChoosable_invalidInputsShortCircuit() {
        assertFalse(resolver.isChoosable(rule("ROLE", "admin"), null));
        assertFalse(resolver.isChoosable(rule("NOT_A_SCOPE", null), USER_ID));
        assertFalse(resolver.isChoosable(rule(null, null), USER_ID));
        assertFalse(resolver.isChoosable(null, USER_ID));

        verifyNoInteractions(userMapper);
    }

    // ------------------------------------------------------------------
    // 2. searchChoosable：分页 + 关键字，同样不物化
    // ------------------------------------------------------------------

    @Test
    @DisplayName("searchChoosable 走 selectPage，不调用 selectList")
    void searchChoosable_usesPageQueryOnly() {
        Page<User> page = new Page<>(1, 3);
        page.setRecords(List.of(activeUser(1L), activeUser(2L)));
        page.setTotal(7);
        doReturn(page).when(userMapper).selectPage(any(), any());

        assertTrue(resolver.searchChoosable(rule("ALL", null), "张", 1, 3).getRecords().size() == 2);

        verify(userMapper, times(1)).selectPage(any(), any());
        verify(userMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("LIKE 关键字的 OR 组必须被 and(...) 包住 —— 否则会把范围条件整条漏掉")
    void searchChoosable_keywordOrGroupIsParenthesised() {
        Page<User> page = new Page<>(1, 20);
        doReturn(page).when(userMapper).selectPage(any(), any());

        resolver.searchChoosable(rule("ROLE", "admin"), "张", 1, 20);

        String sql = compactSql(capturedPageWrapper().getSqlSegment());
        // 这一条是本项目反复踩过的坑：不加 and(...) 包住，OR 会与上层的 AND 同级展开，
        // 「按角色限定的候选人」会变成「全库模糊匹配」——查询结果错得离谱但不报错。
        assertTrue(sql.contains("AND (username LIKE"),
                "关键字 OR 组没有被 and(...) 包住，实际 SQL 片段：" + sql);
        assertTrue(sql.contains(" OR real_name LIKE"), "显示名也要参与匹配，实际：" + sql);
        assertTrue(sql.contains(" OR display_name LIKE"), "实际：" + sql);
        assertTrue(sql.toUpperCase().contains("ORDER BY"),
                "分页必须有确定的排序，否则翻页会重复/漏人，实际：" + sql);
    }

    @Test
    @DisplayName("分页查询同样带在职过滤与范围条件（与 COUNT 通路同源）")
    void searchChoosable_carriesSameScopeAndActivePredicate() {
        Page<User> page = new Page<>(1, 20);
        doReturn(page).when(userMapper).selectPage(any(), any());

        resolver.searchChoosable(rule("GROUP", "42"), null, 1, 20);

        String sql = compactSql(capturedPageWrapper().getSqlSegment());
        assertTrue(sql.contains("enabled ="), "实际：" + sql);
        assertTrue(sql.contains("is_dimission"), "实际：" + sql);
        assertTrue(sql.contains("department_id ="), "实际：" + sql);
    }

    @Test
    @DisplayName("范围非法 → 返回空页且不查库（调用方按「没有候选」渲染）")
    void searchChoosable_illegalScope_returnsEmptyPageWithoutQuery() {
        assertTrue(resolver.searchChoosable(rule("NOT_A_SCOPE", null), null, 1, 20).getRecords().isEmpty());
        assertTrue(resolver.searchChoosable(rule("GROUP", "abc"), null, 1, 20).getRecords().isEmpty());

        verifyNoInteractions(userMapper);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static ApproverRule rule(String scope, String scopeValue) {
        ApproverRule rule = new ApproverRule();
        rule.setType(ApproverRuleType.APPLICANT_CHOOSE.name());
        rule.setScope(scope);
        rule.setScopeValue(scopeValue);
        rule.setMinCount(1);
        rule.setMaxCount(2);
        return rule;
    }

    private static User activeUser(Long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("u" + id);
        user.setEnabled(true);
        user.setDimission(false);
        return user;
    }

    private static String compactSql(String sql) {
        return sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<User> capturedCountWrapper() {
        ArgumentCaptor<LambdaQueryWrapper<User>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(userMapper, atLeastOnce()).selectCount(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<User> capturedPageWrapper() {
        ArgumentCaptor<LambdaQueryWrapper<User>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(userMapper).selectPage(any(), captor.capture());
        return captor.getValue();
    }
}
