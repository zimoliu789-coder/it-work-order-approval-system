package com.enterprise.ticket.module.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 盘点明细（P2，逐台）
 *
 * <h2>为什么字段大多是「快照」</h2>
 * <p>盘点的本质是「以某个时点为准去核对」。若边盘边取设备实时值，盘点期间有人提交借用、
 * 有人搬了位置，报告就会出现「同一台设备前后两次核对状态不同」的自相矛盾 ——
 * 而这份报告恰恰是要拿去追责的。因此 {@code assetNo / deviceName / storageLocation /
 * expectedStatus} 都在**创建任务时快照**，之后不再变。
 *
 * <h2>{@code expectedStatus} 与 {@code checkResult} 是两个维度</h2>
 * <p>前者是「台账上写它是什么状态」，后者是「现场看到它在不在」。
 * 一台「使用中」的设备被盘到「在库」是完全正常的结果（它可能刚被归还），
 * 所以二者不能合并成一个枚举。
 *
 * <p>{@code (taskId, deviceId)} 上有唯一键：没有它，重复点击「开始盘点」或重跑范围解析
 * 会插入重复行，报告里的「总数」立刻虚高 —— 而这类重复不报错、只让数字变大，最难发现。
 */
@Data
@TableName("inventory_task_item")
public class InventoryTaskItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private Long deviceId;

    /** 资产编号快照（扫码核对按它定位） */
    private String assetNo;

    private String deviceName;

    private String storageLocation;

    /** 创建任务时的设备状态快照 */
    private String expectedStatus;

    /** 见 {@code InventoryCheckResult}；<b>null = 尚未核对</b> */
    private String checkResult;

    private Long checkedBy;

    private LocalDateTime checkedAt;

    private String remark;

    private LocalDateTime createdAt;
}
