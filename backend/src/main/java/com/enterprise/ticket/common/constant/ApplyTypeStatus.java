package com.enterprise.ticket.common.constant;

/**
 * 申请类型状态
 *
 * <p>只有两个取值，刻意不引入「草稿」态：申请类型本身没有「未完成」的中间形态 ——
 * 它要么可被员工提交（ENABLED），要么不可（DISABLED）。表单定义的草稿/发布态
 * 属于模板（{@link FormTemplateStatus}），两者是不同层次的关注点，不在这里混用。
 */
public enum ApplyTypeStatus {

    /** 启用：出现在员工「提交申请」页 */
    ENABLED("启用"),

    /** 停用：不再出现在提交页，但历史工单照常展示与流转 */
    DISABLED("停用");

    private final String label;

    ApplyTypeStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static ApplyTypeStatus of(String value) {
        if (value == null) {
            return null;
        }
        for (ApplyTypeStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        return null;
    }

    /** 中文名，非法值原样返回，避免展示层出现 null */
    public static String labelOf(String value) {
        ApplyTypeStatus status = of(value);
        return status == null ? value : status.getLabel();
    }
}
