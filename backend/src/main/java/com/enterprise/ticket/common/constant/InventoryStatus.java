package com.enterprise.ticket.common.constant;

/**
 * 盘点任务状态（P2）
 *
 * <p>刻意**只有三个值、没有「草稿」**：创建任务时明细就已一次性快照完毕
 * （见 V40 迁移 ③A 的说明），此时任务已经可以直接开始核对。
 * 多一个草稿态只会让人不知道该不该点「开始」。
 */
public enum InventoryStatus {

    IN_PROGRESS("进行中"),

    COMPLETED("已完成"),

    CANCELLED("已取消");

    private final String label;

    InventoryStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 中文名（静态安全版）：非法/空值原样返回，避免展示层出现 null */
    public static String labelOf(String value) {
        InventoryStatus status = of(value);
        return status == null ? value : status.getLabel();
    }

    public static InventoryStatus of(String value) {
        if (value == null) {
            return null;
        }
        for (InventoryStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        return null;
    }
}
