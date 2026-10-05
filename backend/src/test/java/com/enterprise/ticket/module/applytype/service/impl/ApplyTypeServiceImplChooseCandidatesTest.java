package com.enterprise.ticket.module.applytype.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.common.constant.ApplyTypeStatus;
import com.enterprise.ticket.common.constant.ApprovalMode;
import com.enterprise.ticket.common.constant.SubmitPermissionType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.flow.ApproverRuleResolver;
import com.enterprise.ticket.common.flow.FlowDefinitionCodec;
import com.enterprise.ticket.common.form.FormSchema;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.module.approvalflow.service.ApprovalFlowService;
import com.enterprise.ticket.module.applytype.dto.vo.FlowPreviewVO;
import com.enterprise.ticket.module.applytype.entity.ApplyType;
import com.enterprise.ticket.module.applytype.mapper.ApplyTypeMapper;
import com.enterprise.ticket.module.form.service.FormTemplateService;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「申请人自选」候选人**截断 + 分页搜索**（Wave 4 · W4-D）。
 *
 * <h2>本类存在的理由（它是一个被纠正过一次的落点）</h2>
 * <p>W4-D 最初把第 7 项工程债描述成「把候选池分页」。但候选池有两个消费方、需求完全不同：
 * 校验方只需要一个「在不在范围内」的布尔（→ {@code isChoosable}），
 * 下发方需要的是「首屏 + 可搜索的完整列表」。<b>只给池子加 LIMIT 是功能回归</b> ——
 * 提交页的选择器只用后端下发的 {@code candidates} 构建，截断之后排在后面的人<b>真的选不到</b>。
 *
 * <p>因此本类钉住三条：
 * <ol>
 *   <li><b>截断要如实上报</b>：{@code candidateTotal} 必须是范围内总数，
 *       {@code candidatesTruncated} 必须准确 —— 前端据此决定要不要给搜索入口；</li>
 *   <li><b>必须配套搜索通路</b>：{@code chooseCandidates} 存在、能翻页、并把 size 钳住；</li>
 *   <li><b>不再物化</b>：预览路径不得再走 {@code selectBatchIds} 类的全量装配。</li>
 * </ol>
 *
 * <p>纯 Mockito，不启动 Spring / 数据库；{@code SecurityUtils} 用 {@link MockedStatic} 打桩。
 */
@ExtendWith(MockitoExtension.class)
class ApplyTypeServiceImplChooseCandidatesTest {

    private static final Long TYPE_ID = 100L;
    private static final Long FORM_VERSION_ID = 9L;
    private static final Long FLOW_VERSION_ID = 77L;
    private static final Long USER_ID = 1L;

    /** 一个「申请人自选」审批节点（命中路径上的唯一节点） */
    private static final String FLOW_JSON = """
            {"start":"a1","nodes":[
              {"key":"a1","type":"APPROVAL","name":"主管审批","signType":"ANY_SIGN","next":"end",
               "approverRules":[{"type":"APPLICANT_CHOOSE","scope":"ALL","minCount":1,"maxCount":2}]},
              {"key":"end","type":"END","name":"结束"}]}
            """;

    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(ApplyType.class, User.class);
    }

    @Mock
    private ApplyTypeMapper applyTypeMapper;
    @Mock
    private FormTemplateService formTemplateService;
    @Mock
    private ApprovalFlowService approvalFlowService;
    @Mock
    private ApproverRuleResolver approverRuleResolver;
    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private ApplyTypeServiceImpl service;

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
    // 1. 预览：首屏截断 + 如实上报总数
    // ------------------------------------------------------------------

    @Test
    @DisplayName("候选池装不满首屏上限 → 不标记截断（按角色/分组限定的常见配置行为与改造前一致）")
    void flowPreview_smallPoolIsNotMarkedTruncated() {
        stubSubmittableType();
        stubPreviewFlow();
        doReturn(page(1, 50, List.of(activeUser(1L), activeUser(2L)), 2))
                .when(approverRuleResolver).searchChoosable(any(), isNull(), anyLong(), anyLong());

        FlowPreviewVO.ChooseRequirement requirement = chooseRequirement();

        assertEquals(2, requirement.getCandidates().size());
        assertEquals(2, requirement.getCandidateTotal());
        assertFalse(requirement.isCandidatesTruncated());
    }

    @Test
    @DisplayName("候选池被截断 → 只下发首屏条数，但总数如实上报、截断标记为 true")
    void flowPreview_truncatesCandidatesButReportsRealTotal() {
        stubSubmittableType();
        stubPreviewFlow();
        // 范围内共 7 人，预览只取到 2 条 —— 代表"被截断后的首屏"
        doReturn(page(1, 50, List.of(activeUser(1L), activeUser(2L)), 7))
                .when(approverRuleResolver).searchChoosable(any(), isNull(), anyLong(), anyLong());

        FlowPreviewVO.ChooseRequirement requirement = chooseRequirement();

        assertEquals(2, requirement.getCandidates().size(), "只下发首屏子集");
        assertEquals(7, requirement.getCandidateTotal(), "总数必须是范围内真实人数，而不是下发的条数");
        assertTrue(requirement.isCandidatesTruncated(), "被截断必须如实标记 —— 前端据此显示搜索入口");
        // 关键性能断言：旧实现会把整个池子物化后再逐人装配，现在只走一次分页查询
        verify(userMapper, never()).selectBatchIds(any());
    }

    @Test
    @DisplayName("候选人的显示名口径沿用原逻辑（displayName 优先，回退 realName / username）")
    void flowPreview_candidateDisplayNameFallback() {
        stubSubmittableType();
        stubPreviewFlow();
        User withRealName = activeUser(1L);
        User onlyUsername = new User();
        onlyUsername.setId(2L);
        onlyUsername.setUsername("liuyang");
        onlyUsername.setEnabled(true);
        onlyUsername.setDimission(false);
        doReturn(page(1, 50, List.of(withRealName, onlyUsername), 2))
                .when(approverRuleResolver).searchChoosable(any(), isNull(), anyLong(), anyLong());

        List<FlowPreviewVO.Candidate> candidates = chooseRequirement().getCandidates();

        assertEquals("张三1", candidates.get(0).getName());
        assertEquals("liuyang", candidates.get(1).getName(), "无姓名时回退用户名");
    }

    // ------------------------------------------------------------------
    // 2. 分页搜索：截断的配套通路
    // ------------------------------------------------------------------

    @Test
    @DisplayName("分页搜索命中：把用户映射成候选人并带上分页元信息")
    void chooseCandidates_returnsPagedCandidates() {
        stubSubmittableType();
        stubDefinition();
        doReturn(page(2, 20, List.of(activeUser(5L)), 1))
                .when(approverRuleResolver).searchChoosable(any(), eq("张"), eq(2L), eq(20L));

        PageResult<FlowPreviewVO.Candidate> result =
                service.chooseCandidates(TYPE_ID, "a1", "张", 2, 20);

        assertEquals(1, result.getRecords().size());
        assertEquals(5L, result.getRecords().get(0).getId());
        assertEquals(1, result.getTotal());
        assertEquals(2, result.getCurrent());
    }

    @Test
    @DisplayName("size 被钳到 100（防前端传 size=100000 拖库），page 被钳到 1")
    void chooseCandidates_clampsPageParams() {
        stubSubmittableType();
        stubDefinition();
        doReturn(page(1, 100, List.of(), 0))
                .when(approverRuleResolver).searchChoosable(any(), isNull(), eq(1L), eq(100L));

        service.chooseCandidates(TYPE_ID, "a1", null, 0, 100000);

        verify(approverRuleResolver).searchChoosable(any(), isNull(), eq(1L), eq(100L));
    }

    @Test
    @DisplayName("nodeKey 缺失 → PARAM_INVALID，不去读流程定义")
    void chooseCandidates_blankNodeKeyRejected() {
        stubSubmittableType();

        assertEquals(ErrorCode.PARAM_INVALID, assertThrows(BusinessException.class, () ->
                service.chooseCandidates(TYPE_ID, "  ", null, 1, 20)).getErrorCode());

        verify(approvalFlowService, never()).requirePublishedDefinition(any());
        verify(approverRuleResolver, never()).searchChoosable(any(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("nodeKey 定位不到节点（流程刚被改过）→ 空页，不报错")
    void chooseCandidates_unknownNodeKeyReturnsEmptyPage() {
        stubSubmittableType();
        stubDefinition();

        PageResult<FlowPreviewVO.Candidate> result =
                service.chooseCandidates(TYPE_ID, "no-such-node", null, 1, 20);

        assertTrue(result.getRecords().isEmpty());
        assertEquals(0, result.getTotal());
        verify(approverRuleResolver, never()).searchChoosable(any(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("节点存在但不是「申请人自选」→ 同样空页（现在不需要你选人）")
    void chooseCandidates_nodeWithoutChooseRuleReturnsEmptyPage() {
        stubSubmittableType();
        when(approvalFlowService.requirePublishedDefinition(FLOW_VERSION_ID)).thenReturn(
                FlowDefinitionCodec.read("""
                        {"start":"a1","nodes":[
                          {"key":"a1","type":"APPROVAL","name":"主管审批","signType":"ANY_SIGN","next":"end",
                           "approverRules":[{"type":"ROLE","roleCode":"admin"}]},
                          {"key":"end","type":"END","name":"结束"}]}
                        """));

        assertTrue(service.chooseCandidates(TYPE_ID, "a1", null, 1, 20).getRecords().isEmpty());
        verify(approverRuleResolver, never()).searchChoosable(any(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("申请类型不可提交 → 与预览同口径被拒（候选人不成为枚举在职员工的旁路）")
    void chooseCandidates_requiresSubmittable() {
        ApplyType disabled = type();
        disabled.setStatus(ApplyTypeStatus.DISABLED.name());
        when(applyTypeMapper.selectById(TYPE_ID)).thenReturn(disabled);

        assertEquals(ErrorCode.APPLY_TYPE_DISABLED, assertThrows(BusinessException.class, () ->
                service.chooseCandidates(TYPE_ID, "a1", null, 1, 20)).getErrorCode());
        verify(approverRuleResolver, never()).searchChoosable(any(), any(), anyLong(), anyLong());
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 走一次预览并取出唯一那条自选要求 */
    private FlowPreviewVO.ChooseRequirement chooseRequirement() {
        FlowPreviewVO vo = service.flowPreview(TYPE_ID, Map.of());
        assertEquals(1, vo.getChooseRequirements().size(), "本例流程只有一个自选节点");
        return vo.getChooseRequirements().get(0);
    }

    private void stubSubmittableType() {
        when(applyTypeMapper.selectById(TYPE_ID)).thenReturn(type());
        User applicant = new User();
        applicant.setId(USER_ID);
        applicant.setRole("user");
        applicant.setEnabled(true);
        applicant.setDimission(false);
        when(userMapper.selectById(USER_ID)).thenReturn(applicant);
    }

    /** 只打桩"取流程定义"——分页搜索用例需要，预览用例还要额外的 schema */
    private void stubDefinition() {
        when(approvalFlowService.requirePublishedDefinition(FLOW_VERSION_ID))
                .thenReturn(FlowDefinitionCodec.read(FLOW_JSON));
    }

    /** 预览路径的额外桩：schema + 流程名（不打桩会返回 null，读起来不直观） */
    private void stubPreviewFlow() {
        stubDefinition();
        when(formTemplateService.requirePublishedSchema(FORM_VERSION_ID)).thenReturn(new FormSchema());
        when(approvalFlowService.describeVersion(FLOW_VERSION_ID)).thenReturn("主管审批流 v1");
    }

    private static ApplyType type() {
        ApplyType type = new ApplyType();
        type.setId(TYPE_ID);
        type.setTypeCode("purchase");
        type.setTypeName("采购申请");
        type.setStatus(ApplyTypeStatus.ENABLED.name());
        type.setFormTemplateVersionId(FORM_VERSION_ID);
        type.setApprovalMode(ApprovalMode.FLOW.name());
        type.setApprovalFlowVersionId(FLOW_VERSION_ID);
        type.setSubmitPermissionType(SubmitPermissionType.ALL.name());
        return type;
    }

    private static User activeUser(Long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("u" + id);
        user.setRealName("张三" + id);
        user.setEnabled(true);
        user.setDimission(false);
        return user;
    }

    /**
     * 构造一个「数据库返回的分页结果」。
     *
     * <p>current/size 与请求保持一致（而不是写死 1/50）：`PageResult` 会把它们原样回显给前端，
     * 写死就让「翻页元信息是否正确回显」这条断言失去了辨别力。
     */
    private static Page<User> page(long current, long size, List<User> records, long total) {
        Page<User> page = new Page<>(current, size);
        page.setRecords(records);
        page.setTotal(total);
        return page;
    }
}
