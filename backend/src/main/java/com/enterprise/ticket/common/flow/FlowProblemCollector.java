package com.enterprise.ticket.common.flow;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 校验问题收集器（ ·  · W4-A1）。
 *
 * <h2>两条语义，缺一不可</h2>
 * <ol>
 *   <li><b>去重</b>：同一 message 只保留一次。同一段逻辑可能被不同节点以完全相同的文案触发
 *       （例如两个节点都"未设置名称"的消息因 where 前缀不同而不同，但全流程级的消息可能重复），
 *       重复项对配置者没有增量信息，只会让列表变长；</li>
 *   <li><b>保序</b>：按发现顺序记录。发布路径 {@link FlowDefinitionValidator#validate} 只抛第一条，
 *       所以"谁先被记下"直接决定用户在界面上看到哪句话。</li>
 * </ol>
 *
 * <p>去重靠 {@code seen} 集合，但**顺序靠 {@code problems} 列表** —— 两者必须同时维护：
 * 只维护集合会丢失顺序，只维护列表会退化成 O(n²) 去重。
 *
 * <p>包级可见：它是校验器内部的问题总线，各子校验器共享同一个实例，不属于对外契约。
 */
final class FlowProblemCollector {

    private final List<String> problems = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();

    void add(String message) {
        if (seen.add(message)) {
            problems.add(message);
        }
    }

    /** 返回一份快照（调用方拿到后不会被后续 add 影响） */
    List<String> problems() {
        return new ArrayList<>(problems);
    }
}
