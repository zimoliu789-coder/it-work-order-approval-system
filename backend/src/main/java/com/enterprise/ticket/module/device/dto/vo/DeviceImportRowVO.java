package com.enterprise.ticket.module.device.dto.vo;

import com.enterprise.ticket.module.device.dto.DeviceImportRow;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 设备批量导入的「行校验结果」视图
 *
 * <p>继承 {@link DeviceImportRow} 是为了让「预览返回的行」能被前端原样回传用于确认导入 ——
 * 前端不必拆字段重组，也就不会出现「预览与提交字段不一致」导致的静默差异。
 *
 * <p>{@code valid=true} 表示该行通过全部校验；{@code valid=false} 时 {@code reason}
 * 必定有值，前端据此标红并展示原因。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DeviceImportRowVO extends DeviceImportRow {

    /** 是否通过校验 */
    private boolean valid;

    /** 校验失败原因（valid=true 时为空；可空字段在全局 non_null 策略下会被省略） */
    private String reason;
}
