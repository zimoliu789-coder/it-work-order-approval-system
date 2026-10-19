package com.enterprise.ticket.module.device.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 设备型号字典（R3 / 批次 3，表 {@code device_model}）。
 *
 * <h2>为什么需要字典表</h2>
 * <p>借用单改造后，申请人选的是「一级分类 + 二级分类 + 型号」，IT 交付扫码时
 * 要按型号做校验。若型号仍是 {@code device.model} 的自由文本，校验只能是字符串比对，
 * 「ThinkPad X1 / thinkpad x1 / X1 Carbon」会被判成三种型号 —— 校验形同虚设。
 * 建字典后比对的是 {@code model_id}，写法差异不再影响结果。
 *
 * <h2>与 {@code device.model} 的关系</h2>
 * <p>原文本列<b>保留不动</b>：{@code model_id} 是新增的规范化指向，
 * {@code model_id IS NULL} 即表示「这条台账的型号还没整理」——
 * 原始文本在 {@code model} 里可回溯，迁移可回滚，脏数据不会丢。
 * 整理入口见 {@code DeviceModelService#syncFromLedger}。
 */
@Data
@TableName("device_model")
public class DeviceModel {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 型号名称，全局唯一。
     *
     * <p>表使用 {@code utf8mb4_general_ci} 排序规则 ⇒ 唯一索引<b>大小写不敏感</b>，
     * 「ThinkPad X1」与「thinkpad x1」不可能同时存在。服务层的查重也依赖同一语义，
     * 无需额外做 {@code LOWER()} 归一（两边口径必须一致，否则会出现「服务层放过、
     * 数据库拦住」的 500）。
     */
    private String modelName;

    /** 品牌（可选，便于下拉里按品牌分组） */
    private String brand;

    /** 状态，取值见 {@link com.enterprise.ticket.common.constant.DeviceModelStatus} */
    private String status;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
