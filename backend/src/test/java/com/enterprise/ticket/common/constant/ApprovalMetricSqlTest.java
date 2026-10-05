package com.enterprise.ticket.common.constant;

import com.enterprise.ticket.module.dashboard.mapper.DashboardMapper;
import com.enterprise.ticket.module.flowmonitor.mapper.FlowMonitorMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批效率指标共享 SQL 片段的锁定测试（Phase 16 Wave 3 · M5）
 *
 * <h2>这个类要防的是什么</h2>
 * <p>「平均审批时长 / 超时率」有两个消费方：M7 流程监控与 M5 统计仪表盘。
 * 它们<b>必须给出同一口径</b>，否则同一批数据在两个页面显示两个数 —— 用户会先怀疑数据，
 * 再怀疑整套系统。而这两处 SQL 分别写在两个 Mapper 里，天然存在「改一处忘另一处」的风险。
 *
 * <p>三道防线：
 * <ol>
 *   <li><b>金样例锁定</b>：把规范化后的表达式文本写死在用例里。谁改了公式，这里先红，
 *       改动因此无法「悄悄」发生（改动必须连测试一起改，评审时必然被看到）；</li>
 *   <li><b>反向核对 M7</b>：反射读出 {@code FlowMonitorMapper} 的真实 SQL 文本，
 *       断言它<b>确实包含</b>共享常量 —— 若有人把 M7 的公式改回内联字面量或改了个数字，这里红；</li>
 *   <li><b>正向核对 M5</b>：同样断言仪表盘侧引用了常量，防止仪表盘侧绕过共享定义。</li>
 * </ol>
 *
 * <p>取注解值用反射而不是「把 SQL 复制一份到测试里」，是因为复制会立刻失效 ——
 * 它验证的是我抄得对不对，不是生产代码写得对不对。
 */
class ApprovalMetricSqlTest {

    /** 折叠全部空白后比较：SQL 文本块的换行与缩进不承载语义，比较时才需要归一 */
    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    /** 注解里的 SQL（{@code value()} 是数组，这里按换行拼回一个字符串） */
    private static String selectSqlOf(Class<?> mapper, String method, Class<?>... params) throws Exception {
        Method m = mapper.getMethod(method, params);
        Select select = m.getAnnotation(Select.class);
        assertTrue(select != null, method + " 上应当有 @Select");
        return String.join("\n", select.value());
    }

    // ------------------------------------------------------------------
    // 一、金样例：口径文本被显式钉住
    // ------------------------------------------------------------------

    @Test
    @DisplayName("金样例：超时率表达式（分子/分母/CASE 条件/NULLIF 全部锁定）")
    void overdueRateGolden() {
        String expected = "ROUND(100 * SUM(CASE WHEN n.deadline_at IS NOT NULL AND n.action_time > n.deadline_at"
                + " THEN 1 ELSE 0 END)"
                + " / NULLIF(SUM(CASE WHEN n.deadline_at IS NOT NULL THEN 1 ELSE 0 END), 0), 2)";
        assertEquals(expected, normalize(ApprovalMetricSql.OVERDUE_RATE));
    }

    @Test
    @DisplayName("金样例：「每单最后一个终态审批节点」子查询（终态含 REJECTED、排除 CC）")
    void finishedApprovalSubqueryGolden() {
        String expected = "(SELECT n.order_id, MAX(n.action_time) AS finished_at"
                + " FROM order_approval_nodes n"
                + " WHERE n.status IN ('APPROVED', 'REJECTED')"
                + " AND (n.node_type IS NULL OR n.node_type = 'APPROVAL')"
                + " GROUP BY n.order_id)";
        assertEquals(expected, normalize(ApprovalMetricSql.FINISHED_APPROVAL_SUBQUERY));
    }

    // ------------------------------------------------------------------
    // 二、M7（流程监控）确实使用同一口径
    // ------------------------------------------------------------------

    /**
     * M7 的节点聚合 SQL 里必须出现与共享常量同形的超时率表达式。
     *
     * <p>M7 是既有代码、本期<b>刻意不改动</b>（零回归）。因此这条用例不要求它「引用常量」，
     * 只要求它「文本同形」—— 效果一样：口径漂移会立刻报红。
     */
    @Test
    @DisplayName("M7 nodeSummary 的超时率与共享常量同形（改任一侧都会红）")
    void flowMonitorNodeSummarySharesOverdueRate() throws Exception {
        String sql = normalize(selectSqlOf(FlowMonitorMapper.class, "nodeSummary", String.class, int.class));
        assertTrue(sql.contains(normalize(ApprovalMetricSql.OVERDUE_RATE)),
                "流程监控的节点聚合 SQL 与共享口径不一致 —— 两处超时率会给出不同的数");
    }

    /** M7 的模板聚合 SQL 里必须出现与共享常量同形的「最后一个终态审批节点」子查询 */
    @Test
    @DisplayName("M7 flowSummary 的终点子查询与共享常量同形")
    void flowMonitorFlowSummarySharesFinishedSubquery() throws Exception {
        String sql = normalize(selectSqlOf(FlowMonitorMapper.class, "flowSummary"));
        assertTrue(sql.contains(normalize(ApprovalMetricSql.FINISHED_APPROVAL_SUBQUERY)),
                "流程监控的模板聚合 SQL 与共享口径不一致 —— 平均审批时长会给出不同的数");
    }

    // ------------------------------------------------------------------
    // 三、M5（统计仪表盘）确实引用同一常量
    // ------------------------------------------------------------------

    @Test
    @DisplayName("M5 overdueStats 直接引用共享超时率常量（不是内联字面量）")
    void dashboardOverdueStatsReferencesSharedConstant() throws Exception {
        String sql = selectSqlOf(DashboardMapper.class, "overdueStats", LocalDateTime.class, LocalDateTime.class);
        assertTrue(sql.contains(ApprovalMetricSql.OVERDUE_RATE),
                "仪表盘的超时率应当直接引用共享常量，避免将来两处各改一次");
    }

    @Test
    @DisplayName("M5 approvalStats 直接引用共享终点子查询常量")
    void dashboardApprovalStatsReferencesSharedSubquery() throws Exception {
        String sql = selectSqlOf(DashboardMapper.class, "approvalStats", LocalDateTime.class, LocalDateTime.class);
        assertTrue(sql.contains(ApprovalMetricSql.FINISHED_APPROVAL_SUBQUERY),
                "仪表盘的平均审批时长应当直接引用共享常量");
    }

    /**
     * 拼出来的 SQL 必须是「有空格分隔」的完整语句 —— 防的是文本块尾随空白被裁掉后
     * 出现 {@code JOINap} 这类静默的语法错误（编译期发现不了，只在真库执行时炸）。
     */
    @Test
    @DisplayName("M5 拼接 SQL 的空格正确：JOIN 与子查询之间、子查询与别名之间都有分隔")
    void dashboardSqlIsProperlySpaced() throws Exception {
        String sql = normalize(selectSqlOf(DashboardMapper.class, "approvalStats",
                LocalDateTime.class, LocalDateTime.class));
        assertTrue(sql.contains("JOIN " + normalize(ApprovalMetricSql.FINISHED_APPROVAL_SUBQUERY) + " ap ON"),
                "拼接处缺空格会拼出 JOIN( 或 )ap 这类畸形 SQL");
    }
}
