package com.enterprise.ticket.module.log.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.module.log.entity.OperationLog;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 操作日志查询条件构造（列表页与导出共用，P2）
 *
 * <h2>为什么要把这段从 Controller 里抽出来</h2>
 * <p>原本这段条件写在 {@code OperationLogController#page} 内部。给日志加导出时，
 * 最省事的做法是「再写一份同样的条件」—— 那是本项目反复出过问题的地方：
 * 两处条件只要有一处漏了某个筛选（比如导出忘了带 {@code result}），
 * 表现就是「导出的行数比列表多」，而用户不会想到是两处条件写得不一样，
 * 只会怀疑数据本身出了问题。
 *
 * <p>本项目的既定处置是**把判定提到一处、两边共用**（同 {@code canViewOrder} 的抽取）。
 * 因此这里把条件构造抽成静态方法：Controller 与 {@code ExportDataLoader} 都调它。
 *
 * <h2>条件语义（与列表页逐条一致）</h2>
 * <ul>
 *   <li>模块 / 动作 / 结果 —— 等值匹配，空白视为「不筛」；</li>
 *   <li>操作人 —— 模糊匹配；</li>
 *   <li>时间范围 —— <b>闭区间</b>（ge / le），传 {@code yyyy-MM-dd HH:mm:ss} 解析后的值；</li>
 *   <li>固定按操作时间倒序（列表与导出同序，导出的第一行也是最新一条）。</li>
 * </ul>
 */
public final class OperationLogQuerySupport {

    private OperationLogQuerySupport() {
    }

    /**
     * 构造日志查询条件。
     *
     * @param module       模块编码（可空）
     * @param action       动作编码（可空）
     * @param result       结果（SUCCESS / FAILED，可空）
     * @param operatorName 操作人（可空，模糊匹配）
     * @param startTime    起始时间（含，可空）
     * @param endTime      结束时间（含，可空）
     */
    public static LambdaQueryWrapper<OperationLog> wrapper(String module,
                                                           String action,
                                                           String result,
                                                           String operatorName,
                                                           LocalDateTime startTime,
                                                           LocalDateTime endTime) {
        return Wrappers.<OperationLog>lambdaQuery()
                .eq(StringUtils.hasText(module), OperationLog::getModule, module)
                .eq(StringUtils.hasText(action), OperationLog::getAction, action)
                .eq(StringUtils.hasText(result), OperationLog::getResult, result)
                .like(StringUtils.hasText(operatorName), OperationLog::getOperatorName, operatorName)
                .ge(startTime != null, OperationLog::getOperationTime, startTime)
                .le(endTime != null, OperationLog::getOperationTime, endTime)
                .orderByDesc(OperationLog::getOperationTime);
    }
}
