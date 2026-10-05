package com.enterprise.ticket.module.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 设备盘点任务（P2）
 *
 * <p>一个任务 = 一次「以某个时点为准的核对」。明细在**创建任务时一次性快照**，
 * 之后不再随台账变化（理由见 {@link InventoryTaskItem}）。
 *
 * <p>计数列（total / checked / inPlace / missing / wrongLocation）是**冗余存储**而不是每次聚合：
 * 列表与报告都要显示它们，而任务一旦完成明细就不再变化 —— 每次点开都 COUNT 一遍全表明细
 * 是纯粹的浪费；此外「计数与明细不一致」本身就是有价值的信号（说明有并发核对正在写入）。
 */
@Data
@TableName("inventory_task")
public class InventoryTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务编号（PC-日期-序号） */
    private String taskNo;

    private String taskName;

    /** 见 {@code InventoryScopeType}：ALL / CATEGORY / LOCATION */
    private String scopeType;

    /** 范围取值：CATEGORY 存分类 id；LOCATION 存位置名；ALL 为空 */
    private String scopeValue;

    /** 范围的中文描述快照（分类/位置后来改名了，报告仍要能复现「当时盘的是哪一片」） */
    private String scopeLabel;

    /** 见 {@code InventoryStatus} */
    private String status;

    private Integer totalCount;

    private Integer checkedCount;

    private Integer inPlaceCount;

    private Integer missingCount;

    private Integer wrongLocationCount;

    private Long createdBy;

    private LocalDateTime createdAt;

    /** 首次核对时间；尚未核对过为 null */
    private LocalDateTime startedAt;

    /** 完成时间；非终态为 null */
    private LocalDateTime completedAt;

    private String remark;
}
