package com.enterprise.ticket.common.flow;

import lombok.Data;
import org.springframework.util.StringUtils;

/**
 * 驳回处理动作。
 *
 * <h2>为什么需要它</h2>
 * <p>第二期的驳回口径是**固定**的：任一节点驳回 → 该节点 REJECTED、其余未完成节点 CANCELLED、
 * 整单 REJECTED、释放设备。真实业务里还有另一种自然诉求：
 * 「驳回后转复核节点」「驳回后回到申请人修改再走一次」—— 即驳回不等于整单终结。
 *
 * <h2>默认值为什么是 TERMINATE</h2>
 * <p>{@code action} 默认 {@link #TERMINATE}，与第二期完全一致。
 * 存量流程定义里 {@code onReject} 为 null → 走这条默认 → <b>零回归</b>。
 * 把"不配置就等于终止"作为默认，也符合"最小惊讶"：
 * 配错了改道比配错了终止危险得多（工单会继续走，没人发现异常）。
 *
 * <h2>GOTO 与工单状态</h2>
 * <p>改道时工单<b>保持 {@code PENDING_APPROVAL}、不释放设备</b>（需求方 Q3 已确认）：
 * 驳回只是"这一支不走了"，整单仍在审批中，此时释放设备会造成
 * "设备已回可用池、但审批还没结束"的并发冲突。
 */
@Data
public class RejectAction {

    /** 终止整单（默认，与第二期一致） */
    public static final String TERMINATE = "TERMINATE";
    /** 改道：本节点 REJECTED，转而激活 target 指向的节点 */
    public static final String GOTO = "GOTO";

    private String action = TERMINATE;

    /** 改道目标节点 key（仅 {@link #GOTO} 时有意义；发布期校验必须真实存在且可达 END） */
    private String target;

    public boolean isGoto() {
        return GOTO.equalsIgnoreCase(action);
    }

    public boolean isTerminate() {
        return !isGoto();
    }

    /**
     * 配置是否完整。
     *
     * <p>刻意把「action 不认识」也算不合法：把 {@code action} 写成 {@code "GOT"} 这类拼写错误
     * 若被静默当作 TERMINATE，配置者会以为改道生效了 —— 而实际每笔驳回都在终止整单。
     * 这类"看起来在工作"的错配比直接报错危险得多。
     */
    public boolean isValid() {
        if (StringUtils.hasText(action)
                && !TERMINATE.equalsIgnoreCase(action)
                && !GOTO.equalsIgnoreCase(action)) {
            return false;
        }
        return !isGoto() || StringUtils.hasText(target);
    }
}
