package com.enterprise.ticket.module.approvalflow.service;

import com.enterprise.ticket.common.form.FormSchema;
import com.enterprise.ticket.common.flow.FlowDefinition;
import com.enterprise.ticket.common.flow.FlowScope;
import com.enterprise.ticket.module.approvalflow.dto.ApprovalFlowDuplicateRequest;
import com.enterprise.ticket.module.approvalflow.dto.ApprovalFlowSaveRequest;
import com.enterprise.ticket.module.approvalflow.dto.vo.ApprovalFlowDetailVO;
import com.enterprise.ticket.module.approvalflow.dto.vo.ApprovalFlowVO;
import com.enterprise.ticket.module.approvalflow.dto.vo.ApprovalFlowVersionVO;
import com.enterprise.ticket.module.approvalflow.dto.vo.FlowDesignMetaVO;

import java.util.List;

/**
 * 审批流程模板服务。
 *
 * <p>语义与一期 {@code FormTemplateService} 严格对齐：
 * <b>草稿宽松、发布严格、发布即冻结、被引用不可删</b>。
 * 沿用同一套语义不是图省事——管理员在"表单模板"和"审批流程"两个页签下
 * 面对的是同一种配置对象（可改的模板 + 冻结的版本），行为不一致会直接造成误操作。
 */
public interface ApprovalFlowService {

    /** 流程列表（含最新已发布版本号、是否有草稿、节点数、被引用次数） */
    List<ApprovalFlowVO> listFlows();

    /** 流程详情（含定义：有草稿给草稿，无草稿给最新已发布版本） */
    ApprovalFlowDetailVO getDetail(Long flowId);

    /** 新建流程（同时创建 v1 草稿） */
    Long createFlow(ApprovalFlowSaveRequest request);

    /**
     * 「另存为 / 复制」流程（ · W4-B）：从源模板复制出一份**新的**流程模板。
     *
     * <h2>三条必须钉死的语义</h2>
     * <ol>
     *   <li><b>复制的内容取源模板的「当前可编辑定义」</b>：有草稿取草稿（那是用户正在改的），
     *       无草稿取最新已发布版本。这个选择与 {@link #getDetail} 的口径**同源** ——
     *       用户在设计器里看到什么，复制出来的就是什么，不会出现"看到的和复制到的不一样"。</li>
     *   <li><b>复制出来一律 DRAFT，绝不复制「已发布」状态</b>。否则「复制即生效」：
     *       一份没人审过的新流程会直接成为可选版本，被误绑到申请类型上。</li>
     *   <li><b>引用不复制</b>：申请类型 / 部门指向源模板的绑定关系**不跟随**
     *       （与设计文档「引用不复制」一致）。绑定是"谁在用这个模板"的事实，属于被引用方，
     *       复制方无权替它新增一条绑定。</li>
     * </ol>
     *
     * <p>新模板的版本号为 1、{@code flow_code} 唯一（调用方未指定时按源编码派生）、
     * 版本列表只有这一份草稿 —— 即"复制"产出的形态与「新建流程」完全一致，
     * 前端因此可以复用同一套后续交互（进设计器 → 存草稿 → 发布）。
     *
     * @param sourceFlowId 源流程模板 id
     * @return 新流程模板 id
     */
    Long duplicateFlow(Long sourceFlowId, ApprovalFlowDuplicateRequest request);

    /** 保存草稿：已有草稿则覆盖，无草稿则自动新建一版草稿 */
    void updateFlow(Long flowId, ApprovalFlowSaveRequest request);

    /** 发布当前草稿为新版本（严格校验；此后该版本可被申请类型引用） */
    Long publish(Long flowId);

    /** 版本列表（倒序，含草稿） */
    List<ApprovalFlowVersionVO> listVersions(Long flowId);

    /** 某版本详情（含完整定义），用于版本历史预览 */
    ApprovalFlowVersionVO getVersion(Long versionId);

    /** 删除流程（被申请类型引用时拒绝，只能停用） */
    void deleteFlow(Long flowId);

    /** 状态切换：DRAFT / PUBLISHED / DISABLED（同值幂等不写库） */
    void updateStatus(Long flowId, String status);

    // ------------------------------------------------------------------
    // 跨模块契约
    // ------------------------------------------------------------------

    /** 取「已发布版本」的流程定义（未发布 / 不存在时抛业务异常） */
    FlowDefinition requirePublishedDefinition(Long versionId);

    /** 该版本是否为已发布版本（不抛异常，供界面渲染"能否选择"） */
    boolean isPublishedVersion(Long versionId);

    /** 取版本的展示名（如「采购审批 v2」），供申请类型装配 */
    String describeVersion(Long versionId);

    /**
     * 取版本所属的**流程模板名**（不含版本号；版本 / 流程不存在时为 {@code null}）。
     *
     * <p>与 {@link #describeVersion} 的区别是刻意保留的：后者带版本号，适合在配置界面
     * 让人分清「绑的是哪一版」；而这里的调用方是**工单提交**（ · M7），
     * 它要把模板名冻结进 {@code orders.approval_flow_name} 作为归属快照。
     * 归属是**模板级**的，若把「v1 / v2」一起冻进去，一旦模板行被删除、
     * 监控页回落到名字快照，同一个模板的两个版本就会显示成两组，凭空多出一个模板。
     */
    String flowNameOfVersion(Long versionId);

    /**
     * 用具体表单 schema 校验流程定义。
     *
     * <p>为什么单独有这个方法：流程模板是**独立于表单**存在的，发布时并不知道将来会被哪个表单引用，
     * 因此发布只做**结构**校验（可达 / 无环 / 穷尽分支）。条件字段是否真实存在于表单里，
     * 要等**申请类型把表单版本与流程版本绑在一起**时才能判定 —— 那正是调用此方法的位置。
     */
    void validateAgainstForm(Long versionId, FormSchema schema);

    // ------------------------------------------------------------------
    // M4a：前后端校验器一致性
    // ------------------------------------------------------------------

    /**
     * 校验**任意一份**流程定义并返回**全部**问题（不落库、不抛错）。
     *
     * <p>这是 M4a 的核心契约：原先前端持有一份规则镜像（{@code validateFlowForPublish}），
     * 与后端 {@code FlowDefinitionValidator} 各自演化；且后端首错即抛、前端返回全部问题，
     * 两侧口径天然不一致。有了这个方法，前端发布**以后端结果为准**，
     * 本地镜像只做「即时预检」，漂移被根治。
     *
     * <p>返回空列表 = 校验通过。列表首项与发布路径 {@code publish()} 抛出的 message 一致，
     * 因此「预检通过但发布失败」这类诡异情况不可能再出现。
     *
     * @param definition 待校验定义（草稿亦可）
     * @param schema     绑定表单的 schema；{@code null} 表示无表单上下文（如借用单流程），
     *                   此时跳过条件字段存在性校验——与 {@code publish()} 的校验口径完全同源
     */
    List<String> validateDefinition(FlowDefinition definition, FormSchema schema);

    /**
     * 校验一份流程定义（M4a + M1），**指定业务域**。
     *
     * <p>与 {@link #validateDefinition(FlowDefinition, FormSchema)} 的区别只有字段域来源与
     * 禁用规则集（见 {@code FlowScope}）。这条重载是 M1 加的：借用流程必须在发布前
     * 就能被查出「用了借用域不支持的审批人来源」，否则预检通过、发布失败。
     *
     * @param scope 业务域；{@code null} 按 {@code CUSTOM} 处理（既有调用点行为不变）
     */
    List<String> validateDefinition(FlowDefinition definition, FormSchema schema, FlowScope scope);

    // ------------------------------------------------------------------
    // W4-D：设计器元数据（C8 单一事实源）
    // ------------------------------------------------------------------

    /**
     * 设计器所需的**权威约束元数据**（ · W4-D / C8）。
     *
     * <p>下发三件事：条件树深度上限、各业务域的禁用规则集、各审批人来源的参数槽位。
     * 三者都取自后端枚举本身（{@code FlowCondition.MAX_DEPTH} / {@code FlowScope} /
     * {@code ApproverRuleType}），因此**不存在第二份事实源** —— 前端只保留
     * "接口不可用时的兜底默认"，其正确性由共享金样例
     * {@code test-fixtures/golden/flow-design-meta.json} 在两端各钉一次。
     *
     * <p>为什么值得为一个"纯静态数据"的接口单开一个方法：它是**契约**而非实现细节 ——
     * 前端启动时拉一次并覆盖本地默认值，因此它的响应形状（字段名 / 数组顺序）是被依赖的。
     * 放在 service 层是为了让它可被单测直接断言，而不必起 Web 容器。
     *
     * <p>为什么不需要落库 / 不需要审计：它只读取编译期常量，既不改状态也不含敏感数据。
     */
    FlowDesignMetaVO designMeta();
}
