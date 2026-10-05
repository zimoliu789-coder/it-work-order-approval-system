package com.enterprise.ticket.module.device.dto;

import lombok.Data;

/**
 * 设备批量导入的单行数据
 *
 * <p>字段顺序与「下载导入模板」的列顺序一致，取值一律为<b>原始文本</b>：
 * 分类以<b>名称</b>传递（不传 ID），由后端按名匹配并校验归属 —— 这样前端既不掌握分类 ID，
 * 也无法绕过「二级分类必须属于所选一级分类」的校验。
 *
 * <p>本类同时用作「确认导入」的行载荷：预览阶段返回的行原样回传即可，
 * 后端会<b>重新执行一遍完整校验</b>（不信任客户端），再分批落库。
 */
@Data
public class DeviceImportRow {

    /** Excel 行号（从 1 开始，含表头），用于把失败原因定位回用户的原始文件 */
    private Integer rowNo;

    /** 设备名称（必填） */
    private String deviceName;

    /** 资产编号（必填，全局唯一，含已软删除设备不可复用） */
    private String assetNo;

    /** 一级分类名称（必填，按名称匹配） */
    private String primaryCategoryName;

    /** 二级分类名称（选填；填写时必须归属所选一级分类） */
    private String secondaryCategoryName;

    private String brand;

    private String model;

    private String serialNo;

    private String storageLocation;

    /** 购置日期，格式 yyyy-MM-dd（选填） */
    private String purchaseDate;

    private String remark;

    /**
     * 设备金额（元），选填，原始文本。
     *
     * <p>保持 String 与其它列一致：模板里的数字可能带千分位 / 货币符号 / 中文说明，
     * 在解析阶段统一按文本收、按文本报错（「设备金额格式不正确」），
     * 比让 POI 把 {@code "5,000元"} 静默变成 5000 或 0 更容易解释。
     */
    private String amount;
}
