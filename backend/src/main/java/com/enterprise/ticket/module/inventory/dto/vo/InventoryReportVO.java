package com.enterprise.ticket.module.inventory.dto.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 盘点报告（P2）
 *
 * <h2>报告里为什么要把「缺失 / 位置不符」的明细整段带回</h2>
 * <p>盘点报告的价值几乎全在这两份清单上：计数只说明「有几台对不上」，
 * 而报告要能回答「是哪些台」。让使用者再点回明细页去筛一遍，
 * 等于把「刚算出来的结论」又拆散了。
 *
 * <p>清单规模可控：一份报告里对不上的设备通常是少数（真的上千台对不上，
 * 那问题也不在报告页能不能显示完）。
 *
 * <h2>刻意不自动改设备状态</h2>
 * <p>盘到「缺失」的设备**不会**被自动改成「已丢失」：缺失可能是放错了地方、
 * 被临时拿走了、或被登记到了别的库位 —— 直接置 LOST 太激进（要找不回时再手动标记，
 * 与 P0 的 LOST 语义一致：丢失是「暂时找不到」，报废才是「确定不要了」）。
 * 报告只负责把事实摆清楚。
 */
@Data
public class InventoryReportVO {

    private Long taskId;

    private String taskNo;

    private String taskName;

    private String scopeLabel;

    private String status;

    private String statusLabel;

    private Integer totalCount;

    private Integer checkedCount;

    /** 尚未核对台数（总数 - 已核对） */
    private Integer uncheckedCount;

    private Integer inPlaceCount;

    private Integer missingCount;

    private Integer wrongLocationCount;

    private Integer progressPercent;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime completedAt;

    /** 缺失设备清单（盘亏） */
    private List<InventoryItemVO> missingItems;

    /** 位置不符清单 */
    private List<InventoryItemVO> wrongLocationItems;
}
