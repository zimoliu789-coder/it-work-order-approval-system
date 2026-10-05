package com.enterprise.ticket.module.export.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 导出请求体 = 导出类型 + 导出条件
 *
 * <p>用<b>继承</b>而不是把 {@code ExportQuery} 再嵌一层（{@code {type, query:{...}}}）：
 * 前端在列表页点「导出」时，最自然的写法是把当前筛选条件原样带上再加上 {@code type}，
 * 嵌套一层只会让前后端各多写一次解包代码，且没有任何语义收益。
 * 继承后请求体形如：
 * <pre>
 * { "type": "ORDER", "scope": "ALL", "order": { "status": "BORROWED" }, "year": 2026, "month": 9 }
 * </pre>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ExportRequest extends ExportQuery {

    /** 导出类型，取值见 {@link com.enterprise.ticket.common.constant.ExportType} */
    private String type;
}
