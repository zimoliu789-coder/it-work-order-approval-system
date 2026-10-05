package com.enterprise.ticket.module.device.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 设备台账新增/修改请求
 *
 * <p>注意：本请求<b>不包含 status</b>。新建设备一律从 {@code AVAILABLE} 开始（ 状态机入口），
 * 后续状态变更只能通过「管理员手动状态操作」接口（报废 / 维修完成）或工单流程驱动，
 * 避免在编辑台账时顺手改状态而绕过状态机校验。
 */
@Data
public class DeviceSaveRequest {

    @NotBlank(message = "设备名称不能为空")
    @Size(max = 128, message = "设备名称长度不能超过 128 个字符")
    private String deviceName;

    @NotBlank(message = "资产编号不能为空")
    @Size(max = 64, message = "资产编号长度不能超过 64 个字符")
    private String assetNo;

    @NotNull(message = "请选择一级分类")
    private Long primaryCategoryId;

    /** 二级分类，选填（：支持一级 + 二级分类） */
    private Long secondaryCategoryId;

    @Size(max = 64, message = "品牌长度不能超过 64 个字符")
    private String brand;

    @Size(max = 128, message = "型号长度不能超过 128 个字符")
    private String model;

    @Size(max = 128, message = "序列号长度不能超过 128 个字符")
    private String serialNo;

    @Size(max = 128, message = "存放位置长度不能超过 128 个字符")
    private String storageLocation;

    /** 购置日期，格式 yyyy-MM-dd */
    private LocalDate purchaseDate;

    /**
     * 设备金额（元），选填。
     *
     * <p>用于借用审批的金额分档：超过阈值时加一级「上级部门主管」审批。
     * 选填是刻意的 —— 存量台账没有这一列，且真实资产金额常常暂缺；
     * 未录入时按「未超过阈值」处理（走三级流程），由需求方确认过这个口径。
     *
     * <p>校验上限取 {@code DECIMAL(12,2)} 的前 10 位整数：让越界在入参处就被拒，
     * 而不是撞到数据库列上限才报「Data too long」——后者在导入场景里最难定位到行。
     */
    @DecimalMin(value = "0", message = "设备金额不能为负数")
    @Digits(integer = 10, fraction = 2, message = "设备金额最多 10 位整数、2 位小数")
    private BigDecimal amount;

    @Size(max = 500, message = "备注长度不能超过 500 个字符")
    private String remark;
}
