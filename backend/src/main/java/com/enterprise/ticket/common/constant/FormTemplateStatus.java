package com.enterprise.ticket.common.constant;

/**
 * 表单模板状态
 *
 * <p><b>与版本状态的区别</b>：模板本身只有一个「大状态」，版本一旦发布就永久冻结，
 * 因此版本没有状态列（见 {@code form_template_version}）。这里的三个取值描述的是
 * 模板在管理侧的可用性：
 * <ul>
 *   <li>{@link #DRAFT} —— 从未发布过，或当前只有草稿改动，尚无任何版本可被申请类型引用；</li>
 *   <li>{@link #PUBLISHED} —— 至少发布过一个版本，可被申请类型引用；</li>
 *   <li>{@link #DISABLED} —— 停用：不再允许被新的申请类型引用，但已引用的照常工作
 *       （历史工单必须能继续按原模板渲染，所以停用<b>不等于</b>删除）。</li>
 * </ul>
 */
public enum FormTemplateStatus {

    /** 草稿（尚无已发布版本） */
    DRAFT("草稿"),

    /** 已发布（至少一个版本可供申请类型引用） */
    PUBLISHED("已发布"),

    /** 已停用（不再可被新申请类型引用；已引用的不受影响） */
    DISABLED("已停用");

    private final String label;

    FormTemplateStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static FormTemplateStatus of(String value) {
        if (value == null) {
            return null;
        }
        for (FormTemplateStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        return null;
    }

    /** 中文名，非法值原样返回，避免展示层出现 null */
    public static String labelOf(String value) {
        FormTemplateStatus status = of(value);
        return status == null ? value : status.getLabel();
    }
}
