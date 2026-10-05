package com.enterprise.ticket.module.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建盘点任务请求（P2）
 *
 * <p>范围由 `scopeType` + `scopeValue` 两个字段共同定义（而不是一句话描述）——
 * 范围写不清，盘亏就没有意义：漏盘一台与真丢一台在报告里长得一模一样。
 */
@Data
public class InventoryTaskCreateRequest {

    @NotBlank(message = "请填写盘点任务名称")
    @Size(max = 100, message = "任务名称不能超过 100 个字符")
    private String taskName;

    /** ALL / CATEGORY / LOCATION（见 {@code InventoryScopeType}） */
    @NotBlank(message = "请选择盘点范围")
    private String scopeType;

    /**
     * 范围取值：CATEGORY 时为分类 id（字符串形式）；LOCATION 时为存放位置名。
     *
     * <p>ALL 时忽略（服务端不据此过滤）。校验放在服务层：范围与取值是否匹配
     * 取决于 scopeType，用注解表达不了（`@NotBlank` 会让 ALL 也被拒）。
     */
    @Size(max = 64, message = "范围取值过长")
    private String scopeValue;

    @Size(max = 500, message = "备注不能超过 500 个字符")
    private String remark;
}
