package com.enterprise.ticket.module.user.dto.vo;

import com.enterprise.ticket.module.user.dto.UserImportRow;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 员工批量导入的「行校验结果」视图（需求方 2026-09-18 小迭代 · ）
 *
 * <p>继承 {@link UserImportRow} 是为了让「预览返回的行」能被前端原样回传用于确认导入
 * （与设备导入完全同构），避免前端拆字段重组造成静默差异。
 *
 * <p><b>密码字段的处理</b>：{@link UserImportRow#getPassword()} 会被回传给后端，
 * 因此必须留在响应体里（否则确认导入时拿不到初始密码）。它只出现在
 * 「上传预览」与「确认导入」的响应中，属于导入人本人可见的中间态数据；
 * 列表 / 详情接口绝不返回任何密码字段。
 *
 * <p>{@code valid=false} 时 {@code reason} 必定有值，前端据此标红并展示原因
 * （例如「姓名已存在：张三」「登录名已存在：zhangsan」「文件内第 3 行与第 7 行姓名重复」）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserImportRowVO extends UserImportRow {

    /** 是否通过校验 */
    private boolean valid;

    /** 校验失败原因（valid=true 时为空；可空字段在全局 non_null 策略下会被省略） */
    private String reason;
}
