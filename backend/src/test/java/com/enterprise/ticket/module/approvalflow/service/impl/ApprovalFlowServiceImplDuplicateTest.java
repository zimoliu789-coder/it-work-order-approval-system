package com.enterprise.ticket.module.approvalflow.service.impl;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.ApprovalFlowStatus;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.flow.FlowDefinitionCodec;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.module.approvalflow.dto.ApprovalFlowDuplicateRequest;
import com.enterprise.ticket.module.approvalflow.entity.ApprovalFlow;
import com.enterprise.ticket.module.approvalflow.entity.ApprovalFlowVersion;
import com.enterprise.ticket.module.approvalflow.mapper.ApprovalFlowMapper;
import com.enterprise.ticket.module.approvalflow.mapper.ApprovalFlowVersionMapper;
import com.enterprise.ticket.module.applytype.mapper.ApplyTypeMapper;
import com.enterprise.ticket.module.system.service.SystemConfigService;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 流程模板「另存为 / 复制」单元测试（Phase 16 Wave 4 · W4-B）。
 *
 * <h2>本类锁定的三条语义（方案里"要钉死"的那三条）</h2>
 * <ol>
 *   <li><b>复制的内容取源模板的「当前可编辑定义」</b>：草稿优先、无草稿退到最新已发布版本。
 *       与 {@code getDetail()} 同源 —— 设计器里看到什么，复制出来就是什么。</li>
 *   <li><b>复制出来一律 DRAFT</b>：源模板是 PUBLISHED 也必须降为草稿，
 *       否则"复制即生效"，一份没人审过的新流程会直接成为可被绑定的版本。</li>
 *   <li><b>引用不复制</b>：申请类型 / 部门的绑定关系不跟随，
 *       且整个复制过程<b>只读源模板、不改动它任何一个字节</b>。</li>
 * </ol>
 *
 * <h2>为什么还要单独测「派生编码」</h2>
 * <p>派生逻辑里藏着两个不显眼的坑，都是"看起来能用、极端命名下出错"的类型：
 * <ul>
 *   <li><b>必须截断</b>：源编码本身就可能是 32 字符（库列上限），加后缀必然溢出；</li>
 *   <li><b>截断后必须循环查重</b>：截断会让不同的候选值坍缩到同一个字符串，
 *       甚至可能派生出一个**等于源编码自身**的值（源编码恰好是 {@code ...__COPY} 形状时）。
 *       只试一次的实现会在这种命名下把唯一索引撞成 500。</li>
 * </ul>
 *
 * <p>纯 Mockito，不启动 Spring / 数据库；{@code SecurityUtils} 用 {@link MockedStatic} 打桩
 * （与 {@code FormTemplateServiceImplTest} 同一套 harness）。
 */
@ExtendWith(MockitoExtension.class)
class ApprovalFlowServiceImplDuplicateTest {

    private static final Long SOURCE_ID = 7L;
    private static final Long NEW_ID = 99L;
    private static final Long USER_ID = 1L;

    private static final String SOURCE_CODE = "PURCHASE_FLOW";

    /** 已发布版本（v1）里的节点名，用于断言"取的到底是不是这一版" */
    private static final String PUBLISHED_JSON = """
            {"start":"n1","nodes":[
              {"key":"n1","type":"APPROVAL","name":"主管审批","signType":"ANY_SIGN","next":"end",
               "approverRules":[{"type":"ROLE","roleCode":"admin"}]},
              {"key":"end","type":"END","name":"结束"}]}
            """;

    /** 草稿版本里的节点名与已发布版本刻意不同 —— 否则"取错了哪一版"根本断言不出来 */
    private static final String DRAFT_JSON = """
            {"start":"n1","nodes":[
              {"key":"n1","type":"APPROVAL","name":"草稿专用节点","signType":"ANY_SIGN","next":"end",
               "approverRules":[{"type":"ROLE","roleCode":"admin"}]},
              {"key":"end","type":"END","name":"结束"}]}
            """;

    /** 更早的已发布版本（v1）—— 用于验证"无草稿时取的是最新已发布版本，不是第一版" */
    private static final String OLDER_PUBLISHED_JSON = """
            {"start":"n1","nodes":[
              {"key":"n1","type":"APPROVAL","name":"老版本节点","signType":"ANY_SIGN","next":"end",
               "approverRules":[{"type":"ROLE","roleCode":"admin"}]},
              {"key":"end","type":"END","name":"结束"}]}
            """;

    /** 与 {@code CODE_PATTERN} 同形的编码格式（本类要用它断言派生结果一定合法） */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{1,31}$");

    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(ApprovalFlow.class, ApprovalFlowVersion.class);
    }

    @Mock private ApprovalFlowMapper flowMapper;
    @Mock private ApprovalFlowVersionMapper versionMapper;
    @Mock private ApplyTypeMapper applyTypeMapper;
    @Mock private SystemConfigService systemConfigService;

    @InjectMocks private ApprovalFlowServiceImpl service;

    private MockedStatic<SecurityUtils> securityUtils;

    @BeforeEach
    void stubCurrentUser() {
        securityUtils = Mockito.mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void closeStatic() {
        securityUtils.close();
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private ApprovalFlow sourceFlow(String status) {
        ApprovalFlow flow = new ApprovalFlow();
        flow.setId(SOURCE_ID);
        flow.setFlowCode(SOURCE_CODE);
        flow.setFlowName("采购审批流程");
        flow.setDescription("源模板说明");
        flow.setStatus(status);
        return flow;
    }

    private ApprovalFlowVersion version(Long id, int no, String json, boolean draft) {
        ApprovalFlowVersion version = new ApprovalFlowVersion();
        version.setId(id);
        version.setFlowId(SOURCE_ID);
        version.setVersionNo(no);
        version.setDefinitionJson(json);
        version.setNodeCount(draft ? 0 : 3);
        version.setPublishedAt(draft ? null : LocalDateTime.now());
        return version;
    }

    private static ApprovalFlowDuplicateRequest request(String flowName) {
        ApprovalFlowDuplicateRequest request = new ApprovalFlowDuplicateRequest();
        request.setFlowName(flowName);
        return request;
    }

    /** 桩：源模板存在 + 其版本行（按 version_no 倒序，与真实 mapper 的排序一致）。 */
    private void stubSource(String status, ApprovalFlowVersion... versions) {
        when(flowMapper.selectById(SOURCE_ID)).thenReturn(sourceFlow(status));
        when(versionMapper.selectList(any())).thenReturn(List.of(versions));
    }

    /** 桩：新模板插入成功并回填自增 id。 */
    private void stubInsert() {
        when(flowMapper.insert(any(ApprovalFlow.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ApprovalFlow.class).setId(NEW_ID);
            return 1;
        });
        when(versionMapper.insert(any(ApprovalFlowVersion.class))).thenReturn(1);
    }

    private static ErrorCode codeOf(ThrowingRunnable runnable) {
        return assertThrows(BusinessException.class, runnable::run).getErrorCode();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }

    /** 抓取两次 insert 的入参：新模板 + 新草稿版本。 */
    private ArgumentCaptor<ApprovalFlow> capturedFlow() {
        ArgumentCaptor<ApprovalFlow> captor = ArgumentCaptor.forClass(ApprovalFlow.class);
        verify(flowMapper).insert(captor.capture());
        return captor;
    }

    private ArgumentCaptor<ApprovalFlowVersion> capturedVersion() {
        ArgumentCaptor<ApprovalFlowVersion> captor = ArgumentCaptor.forClass(ApprovalFlowVersion.class);
        verify(versionMapper).insert(captor.capture());
        return captor;
    }

    /** 把 JSON 归一化后比对语义（不依赖源文本的空白 / 字段顺序）。 */
    private static String canonical(String json) {
        return FlowDefinitionCodec.write(FlowDefinitionCodec.read(json));
    }

    // ------------------------------------------------------------------
    // 语义 1：取「当前可编辑定义」（草稿优先 → 最新已发布）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("有草稿 → 复制草稿内容（而不是已发布版本），与 getDetail 的口径同源")
    void duplicate_prefersDraftOverPublished() {
        stubSource(ApprovalFlowStatus.PUBLISHED.name(),
                version(72L, 3, DRAFT_JSON, true),
                version(71L, 2, PUBLISHED_JSON, false));
        stubInsert();

        assertEquals(NEW_ID, service.duplicateFlow(SOURCE_ID, request("采购审批流程 副本")));

        String copiedJson = capturedVersion().getValue().getDefinitionJson();
        assertTrue(copiedJson.contains("草稿专用节点"),
                "复制的必须是草稿那一份 —— 设计器里看到什么，复制出来就该是什么");
        assertEquals(canonical(DRAFT_JSON), canonical(copiedJson), "复制内容与源草稿语义完全一致");
    }

    @Test
    @DisplayName("无草稿 → 退回**最新**已发布版本（多版已发布时取版本号最大的，不是第一版）")
    void duplicate_withoutDraft_fallsBackToLatestPublished() {
        stubSource(ApprovalFlowStatus.PUBLISHED.name(),
                version(71L, 2, PUBLISHED_JSON, false),
                version(70L, 1, OLDER_PUBLISHED_JSON, false));
        stubInsert();

        service.duplicateFlow(SOURCE_ID, request("采购审批流程 副本"));

        String copiedJson = capturedVersion().getValue().getDefinitionJson();
        assertTrue(copiedJson.contains("主管审批"), "应取 v2 的内容");
        assertTrue(!copiedJson.contains("老版本节点"), "不应取回 v1 的旧内容");
    }

    // ------------------------------------------------------------------
    // 语义 2：一律 DRAFT
    // ------------------------------------------------------------------

    @Test
    @DisplayName("源模板是 PUBLISHED，复制出来仍必须是 DRAFT（绝不「复制即生效」）")
    void duplicate_alwaysDraftEvenIfSourcePublished() {
        stubSource(ApprovalFlowStatus.PUBLISHED.name(), version(71L, 1, PUBLISHED_JSON, false));
        stubInsert();

        service.duplicateFlow(SOURCE_ID, request("采购审批流程 副本"));

        ArgumentCaptor<ApprovalFlow> captor = capturedFlow();
        assertEquals(ApprovalFlowStatus.DRAFT.name(), captor.getValue().getStatus(),
                "复制产物若带出 PUBLISHED，一份没人审过的新流程立刻就能被申请类型绑定");
        assertEquals(USER_ID, captor.getValue().getCreatedBy(), "创建人 = 当前登录用户（复制者）");
    }

    @Test
    @DisplayName("新模板的版本从 v1 起、nodeCount 记 0（与「新建流程」落到同一形态）")
    void duplicate_newVersionLooksLikeFreshDraft() {
        stubSource(ApprovalFlowStatus.DRAFT.name(), version(71L, 3, DRAFT_JSON, true));
        stubInsert();

        service.duplicateFlow(SOURCE_ID, request("采购审批流程 副本"));

        ApprovalFlowVersion copied = capturedVersion().getValue();
        assertEquals(1, copied.getVersionNo(), "新模板自己从 v1 开始，不继承源模板的版本号");
        assertEquals(NEW_ID, copied.getFlowId(), "新版本挂在新模板上，而不是源模板");
        assertEquals(0, copied.getNodeCount(), "node_count 是发布校验产物，草稿阶段记 0（同 createFlow）");
        assertTrue(copied.isDraft(), "新版本必须是草稿");
    }

    // ------------------------------------------------------------------
    // 语义 3：引用不复制 + 源模板只读
    // ------------------------------------------------------------------

    @Test
    @DisplayName("引用不复制：不读也不写申请类型（绑定关系不跟随新模板）")
    void duplicate_doesNotCopyReferences() {
        stubSource(ApprovalFlowStatus.PUBLISHED.name(), version(71L, 1, PUBLISHED_JSON, false));
        stubInsert();

        service.duplicateFlow(SOURCE_ID, request("采购审批流程 副本"));

        verifyNoInteractions(applyTypeMapper);
    }

    @Test
    @DisplayName("源模板只读：复制过程不更新、不删除源模板与它的任何版本")
    void duplicate_neverMutatesSource() {
        stubSource(ApprovalFlowStatus.PUBLISHED.name(), version(71L, 1, PUBLISHED_JSON, false));
        stubInsert();

        service.duplicateFlow(SOURCE_ID, request("采购审批流程 副本"));

        verify(flowMapper, never()).update(any(), any());
        verify(versionMapper, never()).update(any(), any());
        verify(flowMapper, never()).deleteById(anyLong());
        verify(versionMapper, never()).delete(any());
    }

    @Test
    @DisplayName("深拷贝：新版本用的是一份独立数据（不是源实体的同一引用）")
    void duplicate_definitionIsIndependentCopy() {
        ApprovalFlowVersion sourceVersion = version(71L, 1, PUBLISHED_JSON, false);
        stubSource(ApprovalFlowStatus.PUBLISHED.name(), sourceVersion);
        stubInsert();

        service.duplicateFlow(SOURCE_ID, request("采购审批流程 副本"));

        ApprovalFlowVersion copied = capturedVersion().getValue();
        assertNotSame(sourceVersion, copied);
        assertEquals(canonical(PUBLISHED_JSON), canonical(copied.getDefinitionJson()));
    }

    // ------------------------------------------------------------------
    // 编码：显式指定 / 派生 / 截断 / 冲突
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未指定编码 → 派生 {源编码}_COPY，且格式合法")
    void duplicate_derivesCopyCode_whenBlank() {
        stubSource(ApprovalFlowStatus.PUBLISHED.name(), version(71L, 1, PUBLISHED_JSON, false));
        when(flowMapper.selectCount(any())).thenReturn(0L);
        stubInsert();

        service.duplicateFlow(SOURCE_ID, request("采购审批流程 副本"));

        String code = capturedFlow().getValue().getFlowCode();
        assertEquals(SOURCE_CODE + "_COPY", code);
        assertTrue(CODE_PATTERN.matcher(code).matches(), "派生结果必须满足库列约束的编码格式");
    }

    @Test
    @DisplayName("派生编码已占用 → 追加序号继续试（_COPY → _COPY2）")
    void duplicate_derivesCopyCode_appendsSequenceOnCollision() {
        stubSource(ApprovalFlowStatus.PUBLISHED.name(), version(71L, 1, PUBLISHED_JSON, false));
        // 第一次问 {源}_COPY 已占用，第二次问 {源}_COPY2 可用
        when(flowMapper.selectCount(any())).thenReturn(1L, 0L);
        stubInsert();

        service.duplicateFlow(SOURCE_ID, request("采购审批流程 副本"));

        assertEquals(SOURCE_CODE + "_COPY2", capturedFlow().getValue().getFlowCode());
    }

    @Test
    @DisplayName("源编码已是最长 32 位 → 派生结果按库列上限截断，绝不超长")
    void duplicate_truncatesDerivedCodeToColumnLimit() {
        String longCode = "ABCDEFGHIJKLMNOPQRSTUVWXYZ123456"; // 32 位，正是最大合法长度
        assertEquals(32, longCode.length());
        ApprovalFlow source = sourceFlow(ApprovalFlowStatus.PUBLISHED.name());
        source.setFlowCode(longCode);
        when(flowMapper.selectById(SOURCE_ID)).thenReturn(source);
        when(versionMapper.selectList(any())).thenReturn(List.of(version(71L, 1, PUBLISHED_JSON, false)));
        when(flowMapper.selectCount(any())).thenReturn(0L);
        stubInsert();

        service.duplicateFlow(SOURCE_ID, request("采购审批流程 副本"));

        String code = capturedFlow().getValue().getFlowCode();
        assertEquals(32, code.length(), "超长会直接撞库列长度（VARCHAR(32)）");
        assertTrue(code.endsWith("_COPY"));
        assertTrue(CODE_PATTERN.matcher(code).matches());
    }

    @Test
    @DisplayName("截断可能派生出**等于源编码**的值 → 循环查重必须跳过它（只试一次的实现会 500）")
    void duplicate_truncationMayEqualSourceCode_loopSkipsIt() {
        // 源编码本身就是「27 位前缀 + _COPY」的形状：截断后拼出来的候选与源编码完全相同。
        // 这正是"截断 + 只查一次"会漏掉的极端命名。
        String tricky = "ABCDEFGHIJKLMNOPQRSTUVWXYZ1_COPY";
        assertEquals(32, tricky.length());
        ApprovalFlow source = sourceFlow(ApprovalFlowStatus.PUBLISHED.name());
        source.setFlowCode(tricky);
        when(flowMapper.selectById(SOURCE_ID)).thenReturn(source);
        when(versionMapper.selectList(any())).thenReturn(List.of(version(71L, 1, PUBLISHED_JSON, false)));
        when(flowMapper.selectCount(any())).thenReturn(1L, 0L);
        stubInsert();

        service.duplicateFlow(SOURCE_ID, request("采购审批流程 副本"));

        String code = capturedFlow().getValue().getFlowCode();
        assertEquals("ABCDEFGHIJKLMNOPQRSTUVWXYZ_COPY2", code);
        assertTrue(CODE_PATTERN.matcher(code).matches());
        assertTrue(code.length() <= 32);
    }

    @Test
    @DisplayName("显式指定编码 → 用指定值（去掉首尾空白），不做派生")
    void duplicate_usesExplicitCode_whenProvided() {
        stubSource(ApprovalFlowStatus.PUBLISHED.name(), version(71L, 1, PUBLISHED_JSON, false));
        when(flowMapper.selectCount(any())).thenReturn(0L);
        stubInsert();

        ApprovalFlowDuplicateRequest request = request("采购审批流程 副本");
        request.setFlowCode("  MY_COPY_1  ");

        service.duplicateFlow(SOURCE_ID, request);

        assertEquals("MY_COPY_1", capturedFlow().getValue().getFlowCode());
        verify(flowMapper).selectCount(any()); // 唯一性预检确实跑了（只此一次，未进派生循环）
    }

    @Test
    @DisplayName("显式编码格式非法 → FLOW_CODE_INVALID（沿用新建流程的错误码，不写库）")
    void duplicate_rejectsMalformedExplicitCode() {
        when(flowMapper.selectById(SOURCE_ID)).thenReturn(sourceFlow(ApprovalFlowStatus.PUBLISHED.name()));

        ApprovalFlowDuplicateRequest request = request("采购审批流程 副本");
        request.setFlowCode("1bad");

        assertEquals(ErrorCode.FLOW_CODE_INVALID, codeOf(() -> service.duplicateFlow(SOURCE_ID, request)));
        verify(flowMapper, never()).insert(any(ApprovalFlow.class));
        verify(versionMapper, never()).insert(any(ApprovalFlowVersion.class));
    }

    @Test
    @DisplayName("显式编码已被占用 → FLOW_CODE_EXISTS（沿用新建流程的错误码，不写库）")
    void duplicate_rejectsTakenExplicitCode() {
        when(flowMapper.selectById(SOURCE_ID)).thenReturn(sourceFlow(ApprovalFlowStatus.PUBLISHED.name()));
        when(flowMapper.selectCount(any())).thenReturn(1L);

        ApprovalFlowDuplicateRequest request = request("采购审批流程 副本");
        request.setFlowCode("PURCHASE_FLOW");

        assertEquals(ErrorCode.FLOW_CODE_EXISTS, codeOf(() -> service.duplicateFlow(SOURCE_ID, request)));
        verify(flowMapper, never()).insert(any(ApprovalFlow.class));
    }

    @Test
    @DisplayName("派生编码的并发竞态：唯一索引报重 → 转成 FLOW_CODE_EXISTS，而不是看不懂的 500")
    void duplicate_convertsDuplicateKeyRaceToFlowCodeExists() {
        stubSource(ApprovalFlowStatus.PUBLISHED.name(), version(71L, 1, PUBLISHED_JSON, false));
        when(flowMapper.selectCount(any())).thenReturn(0L);
        // 预检说可用，插入时被别人抢先 —— 这是"先查后写"无法消除的窗口
        when(flowMapper.insert(any(ApprovalFlow.class)))
                .thenThrow(new DuplicateKeyException("uk_approval_flow_code"));

        assertEquals(ErrorCode.FLOW_CODE_EXISTS,
                codeOf(() -> service.duplicateFlow(SOURCE_ID, request("采购审批流程 副本"))));
        verify(versionMapper, never()).insert(any(ApprovalFlowVersion.class));
    }

    // ------------------------------------------------------------------
    // 说明继承
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未传说明 → 沿用源模板说明（说明属于模板内容，随内容一起复制）")
    void duplicate_inheritsDescription_whenBlank() {
        stubSource(ApprovalFlowStatus.PUBLISHED.name(), version(71L, 1, PUBLISHED_JSON, false));
        stubInsert();

        service.duplicateFlow(SOURCE_ID, request("采购审批流程 副本"));

        assertEquals("源模板说明", capturedFlow().getValue().getDescription());
    }

    @Test
    @DisplayName("传了说明 → 以传入值为准（去掉首尾空白）")
    void duplicate_overridesDescription_whenProvided() {
        stubSource(ApprovalFlowStatus.PUBLISHED.name(), version(71L, 1, PUBLISHED_JSON, false));
        stubInsert();

        ApprovalFlowDuplicateRequest request = request("采购审批流程 副本");
        request.setDescription("  这是副本  ");
        service.duplicateFlow(SOURCE_ID, request);

        assertEquals("这是副本", capturedFlow().getValue().getDescription());
    }

    // ------------------------------------------------------------------
    // 源不可用
    // ------------------------------------------------------------------

    @Test
    @DisplayName("源流程不存在 → FLOW_NOT_FOUND")
    void duplicate_missingSource() {
        when(flowMapper.selectById(SOURCE_ID)).thenReturn(null);

        assertEquals(ErrorCode.FLOW_NOT_FOUND, codeOf(() -> service.duplicateFlow(SOURCE_ID, request("副本"))));
    }

    @Test
    @DisplayName("源流程一个版本都没有（脏数据）→ FLOW_VERSION_NOT_FOUND，不静默复制出空流程")
    void duplicate_sourceWithoutVersions() {
        when(flowMapper.selectById(SOURCE_ID)).thenReturn(sourceFlow(ApprovalFlowStatus.DRAFT.name()));
        when(versionMapper.selectList(any())).thenReturn(List.of());

        assertEquals(ErrorCode.FLOW_VERSION_NOT_FOUND,
                codeOf(() -> service.duplicateFlow(SOURCE_ID, request("副本"))));
        verify(flowMapper, never()).insert(any(ApprovalFlow.class));
    }

    @Test
    @DisplayName("源定义 JSON 读不出来 → FLOW_DEFINITION_INVALID（复制时就暴露，不留到下次打开设计器）")
    void duplicate_corruptSourceDefinitionFailsFast() {
        stubSource(ApprovalFlowStatus.PUBLISHED.name(), version(71L, 1, "{不是合法 JSON", false));

        assertEquals(ErrorCode.FLOW_DEFINITION_INVALID,
                codeOf(() -> service.duplicateFlow(SOURCE_ID, request("副本"))));
        verify(flowMapper, never()).insert(any(ApprovalFlow.class));
    }

    @Test
    @DisplayName("新模板的 description 传空白串 → 归一化为 null 而不是空串")
    void duplicate_blankDescriptionOnSourceStaysNull() {
        ApprovalFlow source = sourceFlow(ApprovalFlowStatus.PUBLISHED.name());
        source.setDescription(null);
        when(flowMapper.selectById(SOURCE_ID)).thenReturn(source);
        when(versionMapper.selectList(any())).thenReturn(List.of(version(71L, 1, PUBLISHED_JSON, false)));
        stubInsert();

        ApprovalFlowDuplicateRequest request = request("采购审批流程 副本");
        request.setDescription("   ");
        service.duplicateFlow(SOURCE_ID, request);

        assertNull(capturedFlow().getValue().getDescription());
    }
}
