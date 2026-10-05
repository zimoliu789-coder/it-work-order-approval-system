package com.enterprise.ticket.common.constant;

/**
 * 审批流程模板状态——与一期 {@link FormTemplateStatus} 同构。
 */
public enum ApprovalFlowStatus {

    /** 草稿：可自由修改，不可被申请类型引用 */
    DRAFT("草稿"),
    /** 已发布：至少有一个已发布版本，可被申请类型引用 */
    PUBLISHED("已发布"),
    /** 已停用：不再允许被新引用（存量引用继续可用） */
    DISABLED("已停用");

    private final String label;

    ApprovalFlowStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static ApprovalFlowStatus of(String value) {
        if (value == null) {
            return null;
        }
        for (ApprovalFlowStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        return null;
    }

    public static String labelOf(String value) {
        ApprovalFlowStatus status = of(value);
        return status == null ? value : status.getLabel();
    }
}
