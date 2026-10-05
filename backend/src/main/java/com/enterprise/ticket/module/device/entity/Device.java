package com.enterprise.ticket.module.device.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 设备台账（ / ， 追加临时锁字段）
 *
 * <p>字段范围：规范要求「至少包含设备名称、资产编号、一级分类、二级分类、状态、备注、创建时间、更新时间」，
 * 在此基础上按需求方确认补充品牌、型号、序列号、存放位置、购置日期等常用台账字段。
 *
 * <p>两点容易混淆的语义（ 特别强调）：
 * <ul>
 *   <li><b>报废（status = SCRAPPED）</b>：资产生命周期终结，不可再被新建借用申请，历史工单可查；</li>
 *   <li><b>软删除（deleted = 1）</b>：台账记录逻辑隐藏，一般极少使用；历史设备不允许物理删除。</li>
 * </ul>
 *
 * <p><b>临时锁三件套（， 落地）</b>：{@code lockedBy} / {@code lockedAt} / {@code lockToken}
 * 仅在 {@code status = LOCKED} 期间有值，释放时一并置空。
 * {@code lockToken} 的用途是防止「旧页面释放了后来属于其他用户的新锁」——
 * 释放时必须比对令牌，不匹配则拒绝。
 */
@Data
@TableName("device")
public class Device {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 设备名称 */
    private String deviceName;

    /** 资产编号，全局唯一（含已软删除记录，历史编号不可复用） */
    private String assetNo;

    /** 一级分类ID（level=1 的分类） */
    private Long primaryCategoryId;

    /** 二级分类ID，可空；非空时必须归属所选一级分类 */
    private Long secondaryCategoryId;

    private String brand;

    private String model;

    private String serialNo;

    /** 存放位置 */
    private String storageLocation;

    /** 购置日期 */
    private LocalDate purchaseDate;

    /**
     * 设备金额（元），选填。
     *
     * <h2>它是「审批金额分档」的唯一数据来源</h2>
     * <p>需求文档：「设备金额：从资产信息自动带出来（用于审批条件判断）」——
     * 员工提交界面**不填金额**（已把「金额」列为自动带出、从表单砍掉），
     * 因此资产台账上的这一列就是借用流程条件分支能取到的全部依据。
     *
     * <h2>为什么可空、为什么不给默认值</h2>
     * <p>加列时存量设备（本机 1091 台）一律为 NULL，强行 NOT NULL DEFAULT 0 会把
     * 「还没录金额」伪装成「金额为 0」—— 那样所有存量设备都会被判成"小额"，
     * 而管理员永远看不出是没录还是真的是 0。留 NULL 才能让「未录入」这件事可被查询、
     * 可被逐步补齐，也让流程条件走默认（三级）分支而不是被一个假的 0 牵着走。
     */
    private BigDecimal amount;

    /**
     * 设备状态，取值见 {@link com.enterprise.ticket.common.constant.DeviceStatus}
     *
     * <p> 起由工单流程驱动 LOCKED → IN_APPROVAL → IN_USE → AVAILABLE 的自动流转。
     */
    private String status;

    /** 临时锁定人 user_id */
    private Long lockedBy;

    /** 临时锁定开始时间（；超时判断以本字段为准，MySQL 是最终事实来源） */
    private LocalDateTime lockedAt;

    /** 临时锁令牌（；释放时比对，防止跨用户误释放） */
    private String lockToken;

    private String remark;

    /** 软删除标记：0 正常 / 1 已删除（全局 logic-delete 配置，查询自动过滤） */
    @TableLogic
    private Boolean deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
