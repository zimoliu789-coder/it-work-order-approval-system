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
 * <p><b>临时锁已整体移除（V56 / 第 9 项）</b>：改造后申请人<b>不再挑选具体资产</b>
 * （只选分类与型号），设备绑定延后到 IT 交付扫码那一刻 —— 既然没有「填表期间占住设备」
 * 这件事，{@code lockedBy / lockedAt / lockToken} 三件套与 5 分钟超时释放任务一并删除。
 * 设备状态从 {@code AVAILABLE} 直接进入 {@code IN_USE}（交付扫码成功瞬间），
 * 中间不再经过 {@code LOCKED / IN_APPROVAL}。
 *
 * <p><b>型号双列并存（V56 / R3）</b>：{@code model} 是原始自由文本，保留不动；
 * {@code modelId} 是型号字典的规范化指向，为 {@code null} 表示「这条台账的型号还没整理」。
 * 二者并存是为了让存量脏数据可回溯、迁移可回滚 —— 不要在任何新逻辑里读 {@code model} 做比对，
 * 那会把「ThinkPad X1 / thinkpad x1」重新当成两种型号。
 *
 * <p><b>负责人（V57 / 第 10 项）</b>：{@code ownerId} 是<b>资产归属责任人</b>（长期固定，
 * 由资产管理员维护），与「借用人」（{@code borrow_order.applicant_id}，动态）是两件事。
 * 报修页「我名下的设备」只按 {@code ownerId} 过滤，绝不拿在途借用单反推。
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

    /**
     * 型号原始文本（自由录入，历史数据）。
     *
     * <p>V56 起<b>不再参与任何比对</b>：型号选择与校验一律走 {@link #modelId}。
     * 保留本列的唯一目的是让存量脏数据可回溯（{@code modelId IS NULL} 即「待整理」）。
     */
    private String model;

    /**
     * 型号字典 {@code device_model.id}（V56 / R3）。
     *
     * <p>为 {@code null} 表示「还没整理」—— 台账里能看到原文（{@link #model}），
     * 但新增设备与借用申请的下拉只认字典，因此这类设备在报修/借用校验里会提示先整理。
     */
    private Long modelId;

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
     * 负责人 user_id（V57 / 第 10 项）。
     *
     * <p><b>资产归属责任人，不是借用人</b>：长期固定、由资产管理员维护
     * （权限 {@code device:ledger:manage}），借用人只存在于借用工单里。
     * 报修页「我名下的设备」按本字段过滤；为 {@code null} 表示未指定，
     * 这类设备只能走「公用 / 其他设备」入口填报。
     */
    private Long ownerId;

    /**
     * 设备状态，取值见 {@link com.enterprise.ticket.common.constant.DeviceStatus}
     *
     * <p>V56 起的流转：{@code AVAILABLE} →（IT 交付扫码成功）→ {@code IN_USE}
     * →（归还检查）→ {@code AVAILABLE / MAINTENANCE / LOST}。
     * {@code LOCKED / IN_APPROVAL} 成为<b>历史值</b>（存量数据可能仍是它们，
     * 为此枚举保留），新流程不再产生。
     */
    private String status;

    private String remark;

    /** 软删除标记：0 正常 / 1 已删除（全局 logic-delete 配置，查询自动过滤） */
    @TableLogic
    private Boolean deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
