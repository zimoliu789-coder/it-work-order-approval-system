package com.enterprise.ticket.module.applytype.dto.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * 审批流程预览
 *
 * <h2>为什么需要这个接口</h2>
 * <p>需求明确：「若条件分支导致该节点被跳过，则不需要选（审批人）」。
 * 而条件分支是<b>按表单数据求值</b>的 —— 也就是说，<b>在用户填完表单之前，
 * 谁也说不准会走哪条分支</b>，自然也就不知道要让他选哪些节点的审批人。
 *
 * <p>因此提交页在表单变化时（防抖）调用本接口，用当前表单数据算一遍路径：
 * <ul>
 *   <li>拿到「会走到哪些节点」——让用户提交前就能看到完整的审批链路；</li>
 *   <li>拿到「路径上哪些节点需要申请人自选审批人」及其可选范围与人数约束；</li>
 *   <li>据此<b>只渲染命中路径上</b>的选择器：被跳过的节点不出现，用户不必为一个
 *       根本不会执行的节点去挑人。</li>
 * </ul>
 *
 * <h2>它只是 UI 便利，不是安全边界</h2>
 * <p>预览结果完全来自当前表单数据，前端可以随便伪造。真正的约束在提交时：
 * 服务端重新求值路径并校验「所选人在可选范围内」「人数在 [min, max] 内」。
 * 把预览当校验用会是一个典型的「前端过滤等于没过滤」缺陷。
 */
@Data
public class FlowPreviewVO {

    /** 该申请类型是否使用独立审批流程（false 时前端整块隐藏，其余字段无意义） */
    private boolean flowUsed;

    /** 流程可读名（如「采购审批 v2」） */
    private String approvalFlowName;

    /**
     * 全量节点（含被跳过的），按执行序排列。
     *
     * <p>刻意把跳过节点也返回：详情页/预览页要把「没走的那条路」也画出来，
     * 并附上 {@code conditionDesc} 说明原因 —— 只返回命中节点的话，
     * 用户看到的是半张图，反而更困惑。
     */
    private List<Node> nodes;

    /** 命中路径上需要申请人自选审批人的节点（可能为空） */
    private List<ChooseRequirement> chooseRequirements;

    /** 路径上的一个节点 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Node {

        private String nodeKey;

        private String nodeName;

        /** ALL_SIGN / ANY_SIGN */
        private String signType;

        /** 会签 / 或签 */
        private String signTypeLabel;

        private Integer stepOrder;

        /** 是否在命中路径上（false = 条件分支未命中，已被跳过） */
        private boolean onPath;

        /** 分支说明：为何走到 / 为何跳过（如「走『金额大于 5000』分支（金额 = 8000）」） */
        private String conditionDesc;
    }

    /** 一个「申请人自选审批人」节点的完整约束 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ChooseRequirement {

        private String nodeKey;

        private String nodeName;

        private String signType;

        private String signTypeLabel;

        private Integer stepOrder;

        /** 可选范围类型：ROLE 按角色 / GROUP 按部门 / ALL 全体在职员工 */
        private String scope;

        private String scopeValue;

        /** 范围的可读文案（如「角色：admin」「分组：研发组」「全部员工」） */
        private String scopeLabel;

        /** 最少选几人 */
        private Integer minCount;

        /** 最多选几人 */
        private Integer maxCount;

        /**
         * 该范围内的可选人员（已过滤离职/禁用）——**首屏子集**，不是全量（ · W4-D）。
         *
         * <p>随预览一起下发，避免选择器再发一次请求：可选范围本来就是这些人的子集，
         * 而且用户很快就会点到，分两次请求只会让弹窗多一次白屏。
         *
         * <p>但「范围 = 全部员工」时这个子集就是**全员**，响应体会随组织规模线性膨胀。
         * 因此 W4-D 起它被截断为首屏若干条（见 {@code ApplyTypeServiceImpl.CANDIDATE_PREVIEW_LIMIT}），
         * 完整列表走 {@code GET /api/apply-types/{id}/choose-candidates} 分页搜索。
         * 按角色 / 分组限定的常见配置候选人本就很少，通常一次装下 —— 那些场景行为与改造前一致。
         */
        private List<Candidate> candidates;

        /**
         * 该范围内可选人员的**总数**（不受 {@link #candidates} 截断影响）。
         *
         * <p>它的作用不是"显示人数"，而是让前端能区分「下拉里没有某人」的两种原因：
         * 不在范围内（搜也搜不到）还是还没加载（应引导去搜）。没有这个数，
         * 用户会以为选不了，而这正是"截断候选池"最容易造成的功能回归。
         */
        private Integer candidateTotal;

        /**
         * {@link #candidates} 是否被截断（true = 只是首屏子集）。
         *
         * <p>刻意用显式布尔，而不是让前端自己比 {@code candidates.size() < candidateTotal}：
         * 两者相等时既可表示"没截断"也可表示"刚好装满"，语义含糊；
         * 而且这个判断是「要不要显示搜索入口」的依据，不该由算术巧合决定。
         */
        private boolean candidatesTruncated;
    }

    /** 可选人员 */
    @Data
    public static class Candidate {

        private Long id;

        /** 显示名（姓名，缺失时回退用户名） */
        private String name;
    }
}
