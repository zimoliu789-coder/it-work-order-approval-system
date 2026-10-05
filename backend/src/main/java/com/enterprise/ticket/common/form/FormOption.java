package com.enterprise.ticket.common.form;

import lombok.Data;

/**
 * 表单选项
 *
 * <p>{@code value} 是<b>落库值</b>，{@code label} 是<b>展示值</b>，两者刻意分开：
 * 展示名（如「研发部笔记本电脑」）常因措辞调整而变，若直接存展示名，
 * 已提交的历史工单会跟着一起「改名」—— 而这正是快照语义要避免的事。
 * 用稳定的 value 落库后，改展示名只影响未来的选择，历史数据保持原样。
 */
@Data
public class FormOption {

    /** 选项值（落库值，同一字段内唯一） */
    private String value;

    /** 显示名 */
    private String label;
}
