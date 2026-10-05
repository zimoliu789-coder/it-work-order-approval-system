package com.enterprise.ticket.common.flow;

import com.enterprise.ticket.common.form.FormField;
import com.enterprise.ticket.common.form.FormSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前后端流程校验器「共享金样例」一致性测试（M4a）。
 *
 * <h2>它解决什么问题</h2>
 * 后端 {@link FlowDefinitionValidator}（发布时的唯一严格校验点，首错即抛）与前端
 * {@code validateFlowForPublish}（设计器里的即时预检，返回全部问题）是**同一套规则的两次实现**。
 * 两侧各自演化时，漂移的形态不是编译错误，而是「一端放过、另一端拦下」——
 * 表现为：设计器里看着没问题，点发布却被后端拒掉，或者反过来，
 * 前端提示的问题在服务端毫无反应。这类缺陷只有到生产上才发现。
 *
 * <h2>本测试的做法</h2>
 * 把「同一份流程定义 JSON + 期望被指出的问题关键片段」固化在语言无关的
 * {@code test-fixtures/golden/flow-validation-golden.json} 里，本测试与前端 Vitest
 * （{@code approvalFlow.golden.spec.ts}）**加载同一个文件**，各自调用本侧校验器，
 * 对同一张表断言。任何一端改了规则而另一端没跟上，两端测试中的至少一个立刻变红。
 *
 * <h2>匹配语义的取舍</h2>
 * 期望项是「应当出现的关键子串」（contains），而非逐字文案。理由：两端文案本就允许存在
 * 无害差异（后端 {@code 1..720 小时} vs 前端 {@code 1~720 小时}），强行逐字对齐会带来
 * 纯噪音的改动；真正要钉死的是**「同一个问题两端是否都报了」**。因此本测试断"有/无"，
 * 另由 {@code knownDivergences} 段落把**已识别且刻意保留**的差异显式记录下来。
 */
class FlowGoldenSampleTest {

    /** 与前端 Vitest 共用的金样例文件（相对 backend/ 工作目录） */
    private static final Path GOLDEN_FILE = Path.of("..", "test-fixtures", "golden", "flow-validation-golden.json");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ------------------------------------------------------------------
    // 用例装载
    // ------------------------------------------------------------------

    /** 整个金样例文档（缓存一次，避免每条用例重读文件） */
    private static JsonNode golden() {
        if (!Files.exists(GOLDEN_FILE)) {
            throw new IllegalStateException("找不到金样例文件：" + GOLDEN_FILE.toAbsolutePath()
                    + "（它是前后端校验器一致性的唯一事实源，不能被删除）");
        }
        try (InputStream in = Files.newInputStream(GOLDEN_FILE)) {
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("金样例文件解析失败：" + GOLDEN_FILE, e);
        }
    }

    /** 共享的表单字段（供条件字段存在性/类型匹配校验使用） */
    private static FormSchema goldenSchema() {
        JsonNode fields = golden().path("formFields");
        List<FormField> list = new ArrayList<>();
        for (JsonNode field : fields) {
            FormField f = new FormField();
            f.setKey(field.path("key").asText());
            f.setLabel(field.path("label").asText());
            f.setType(field.path("type").asText());
            list.add(f);
        }
        return FlowTestFixtures.schema(list.toArray(new FormField[0]));
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> goldenSamples() {
        JsonNode samples = golden().path("samples");
        List<org.junit.jupiter.params.provider.Arguments> args = new ArrayList<>();
        for (JsonNode sample : samples) {
            args.add(org.junit.jupiter.params.provider.Arguments.of(
                    sample.path("id").asText(),
                    sample.path("desc").asText(),
                    sample));
        }
        return args.stream();
    }

    // ------------------------------------------------------------------
    // 主断言
    // ------------------------------------------------------------------

    @DisplayName("M4a 金样例：后端校验器对每条共享用例的结论与金样例一致")
    @ParameterizedTest(name = "[{index}] {0} —— {1}")
    @MethodSource("goldenSamples")
    void backendMatchesGoldenSamples(String id, String desc, JsonNode sample) {
        // given：金样例里的定义（可能是 null —— 代表"定义为空"这一边界）
        JsonNode definitionNode = sample.get("definition");
        FlowDefinition definition = definitionNode == null || definitionNode.isNull()
                ? null
                : FlowDefinitionCodec.read(definitionNode.toString());

        // when：收集全部问题（M4a 新入口，与 validate() 同源同序）
        List<String> problems = FlowDefinitionValidator.collectProblems(definition, goldenSchema());

        // then ①：每条期望的关键片段都必须出现在某个问题里
        JsonNode expected = sample.path("expectedProblems");
        for (JsonNode item : expected) {
            String keyword = item.asText();
            assertTrue(problems.stream().anyMatch(p -> p.contains(keyword)),
                    "用例 [" + id + "] 期望出现包含「" + keyword + "」的问题，但实际只有：" + problems);
        }

        // then ②：通过 / 不通过的大方向必须一致（期望为空 ⇔ 无问题）
        boolean expectValid = expected.isEmpty();
        assertEquals(expectValid, problems.isEmpty(),
                "用例 [" + id + "] 通过性判断不一致，实际问题：" + problems);

        // then ③：标记了精确条数的用例，额外对齐"全量收集"语义
        if (sample.path("expectExactCount").asBoolean(false)) {
            assertEquals(expected.size(), problems.size(),
                    "用例 [" + id + "] 问题条数与金样例不符（后端应收集全部问题），实际：" + problems);
        }

        // then ④（Wave 3 · M3-A）：标记了 expectRuntimeDependent 的用例，额外对齐
        // 「嵌套组里的 process.* 有没有被认出来」。
        // 它不是校验问题，而是**提交时的判定口径**：判错的后果是下游节点不入 INACTIVE、
        // 工单直接走错分支，且全程不报任何错。这种"静默失效"必须钉在共享事实源上，
        // 不能只靠后端自己的单测 —— 否则前端镜像漏了递归也没人会发现。
        if (sample.has("expectRuntimeDependent")) {
            assertEquals(sample.path("expectRuntimeDependent").asBoolean(), anyBranchRuntimeDependent(definition),
                    "用例 [" + id + "] 的运行期依赖判定与金样例不符");
        }
    }

    /** 该定义的任一分支条件是否依赖运行期字段（与引擎同源：逐分支交给 ProcessFieldCatalog 判定） */
    private static boolean anyBranchRuntimeDependent(FlowDefinition definition) {
        if (definition == null) {
            return false;
        }
        for (FlowNode node : definition.getNodes()) {
            if (node == null) {
                continue;
            }
            for (FlowBranch branch : node.getBranches()) {
                if (branch != null && ProcessFieldCatalog.isRuntimeDependent(branch.getCondition())) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 金样例自身的健全性
    // ------------------------------------------------------------------

    @Test
    @DisplayName("M4a 金样例：文件结构完整且用例 id 唯一")
    void goldenFileIsWellFormed() {
        JsonNode root = golden();
        JsonNode samples = root.path("samples");
        assertTrue(samples.isArray() && samples.size() >= 25,
                "金样例用例数过少（" + samples.size() + "），M4a 的覆盖意义会被削弱");

        List<String> ids = new ArrayList<>();
        for (JsonNode sample : samples) {
            String id = sample.path("id").asText();
            assertTrue(!id.isBlank(), "存在 id 为空的用例");
            assertTrue(!ids.contains(id), "用例 id 重复：" + id);
            ids.add(id);
            assertTrue(sample.has("expectedProblems"), "用例 " + id + " 缺少 expectedProblems");
            assertTrue(sample.has("definition"), "用例 " + id + " 缺少 definition");
        }

        // 至少要有：一个完全合法的用例（防"全红"）与一个"定义为空"的边界用例
        assertTrue(ids.contains("valid-regression-flow"), "缺少合法基准用例");
        assertTrue(ids.contains("valid-wave2-flow"), "缺少 Wave2 合法用例");
        assertTrue(ids.contains("empty-definition"), "缺少「定义为空」边界用例");
    }

    @Test
    @DisplayName("M4a 金样例：合法用例在发布路径上确实不抛异常")
    void validSamplesPassStrictValidate() {
        JsonNode samples = golden().path("samples");
        int checked = 0;
        for (JsonNode sample : samples) {
            if (!sample.path("expectedProblems").isEmpty()) {
                continue;
            }
            JsonNode definitionNode = sample.get("definition");
            FlowDefinition definition = definitionNode == null || definitionNode.isNull()
                    ? null
                    : FlowDefinitionCodec.read(definitionNode.toString());
            // 与 publish() 完全同源的严格入口
            FlowDefinitionValidator.validate(definition, goldenSchema());
            checked++;
        }
        assertTrue(checked >= 2, "合法用例数量异常（" + checked + "）");
    }

    @Test
    @DisplayName("M4a 金样例：validate() 抛出的首条消息 == collectProblems() 的首项（同源同序）")
    void strictValidateThrowsFirstCollectedProblem() {
        // 这条断言是"前端预检通过但发布失败"这类诡异情况不会出现的根本保证
        JsonNode samples = golden().path("samples");
        int checked = 0;
        for (JsonNode sample : samples) {
            JsonNode definitionNode = sample.get("definition");
            FlowDefinition definition = definitionNode == null || definitionNode.isNull()
                    ? null
                    : FlowDefinitionCodec.read(definitionNode.toString());
            List<String> collected = FlowDefinitionValidator.collectProblems(definition, goldenSchema());
            if (collected.isEmpty()) {
                continue;
            }
            Exception thrown = null;
            try {
                FlowDefinitionValidator.validate(definition, goldenSchema());
            } catch (Exception e) {
                thrown = e;
            }
            assertNotNull(thrown, "用例 " + sample.path("id").asText() + "：collectProblems 有问题但 validate 未抛错");
            assertEquals(collected.get(0), thrown.getMessage(),
                    "用例 " + sample.path("id").asText() + "：抛出的首条消息与收集列表首项不一致");
            checked++;
        }
        assertTrue(checked >= 5, "参与同源同序校验的用例过少（" + checked + "）");
    }

    @Test
    @DisplayName("M4a 金样例：已识别的差异清单存在且每条都写明了后端/前端表现")
    void knownDivergencesAreDocumented() {
        JsonNode divergences = golden().path("knownDivergences").path("items");
        assertTrue(divergences.isArray() && !divergences.isEmpty(),
                "knownDivergences 不应为空 —— 它是「差异已被显式承认」的凭据");
        for (JsonNode item : divergences) {
            assertTrue(!item.path("id").asText().isBlank(), "差异项缺少 id");
            assertTrue(item.has("backend"), "差异项 " + item.path("id").asText() + " 缺少 backend 字段");
            assertTrue(item.has("frontend"), "差异项 " + item.path("id").asText() + " 缺少 frontend 字段");
            assertTrue(item.has("harmless"), "差异项 " + item.path("id").asText() + " 未标注 harmless");
        }
    }
}
