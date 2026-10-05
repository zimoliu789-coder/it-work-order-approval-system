package com.enterprise.ticket.common.flow;

import java.util.regex.Pattern;

/**
 * 流程定义校验的格式与数值上限（ ·  · W4-A1）。
 *
 * <h2>为什么单独成类</h2>
 * <p>这些上限原先散在 {@link FlowDefinitionValidator} 的私有常量里。拆分后
 * {@code MAX_NODES} 被结构段与图段（计数）同时使用，{@code MAX_TIME_LIMIT_HOURS}
 * 被节点段与动作段同时使用 —— 若各自复制一份，调整上限时必然漏改其中一处，
 * 于是出现"节点数按 50 拦、审批节点数按 60 拦"这类**同一规则两个口径**的裂缝。
 * 集中在一处，是让"上限"只有一个事实源。
 *
 * <p>包级可见：这是校验器的内部实现细节，不属于对外契约。
 */
final class FlowValidationLimits {

    /** 节点标识：字母开头，仅字母 / 数字 / 下划线，最长 64 */
    static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");

    /** 单个流程的节点总数上限 */
    static final int MAX_NODES = 50;

    /** 节点名称长度上限 */
    static final int MAX_NAME_LENGTH = 64;

    /** 审批时限上限（小时）：30 天。再长基本等于没设，同时挡住手滑输入 */
    static final int MAX_TIME_LIMIT_HOURS = 720;

    private FlowValidationLimits() {
    }
}
