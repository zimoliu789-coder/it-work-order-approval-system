package com.enterprise.ticket.common.constant;

import java.util.Arrays;
import java.util.List;

/**
 * 附件关联业务类型（ 附件上传通用能力）
 *
 * <p> 原文：「关联业务类型：申请附件、驳回附件、归还照片、故障照片」——
 * 本枚举即这四类的单点定义，同时承载三项与类型绑定的规则，避免这些规则散落在
 * 控制器 / 前端各处：
 * <ol>
 *   <li><b>归属主体（target）</b>：决定 {@code biz_id} 指向哪张表
 *       （{@link BizTarget#ORDER} → {@code orders.id}；{@link BizTarget#FAULT} → {@code device_fault.id}），
 *       也决定下载鉴权时用哪套可见性口径；</li>
 *   <li><b>是否仅允许图片（imageOnly）</b>：照片类附件只收图片；
 *       申请/驳回附件允许文档（pdf/doc/xls 等），由 {@code app.attachment.allowed-extensions} 约束；</li>
 *   <li><b>中文名</b>：前端展示与操作日志描述统一取此值。</li>
 * </ol>
 */
public enum AttachmentBizType {

    /** 申请附件：借用申请时随单提交的材料（biz_id = orders.id） */
    APPLY_ATTACHMENT("申请附件", BizTarget.ORDER, false),

    /** 驳回附件：审批驳回时补充的说明材料（biz_id = orders.id） */
    REJECT_ATTACHMENT("驳回附件", BizTarget.ORDER, false),

    /** 归还照片：申请人发起归还、执行人确认收回时上传的实物照片（biz_id = orders.id） */
    RETURN_PHOTO("归还照片", BizTarget.ORDER, true),

    /** 故障照片：设备故障上报时的现场照片（biz_id = device_fault.id） */
    FAULT_PHOTO("故障照片", BizTarget.FAULT, true),

    /**
     * 自定义工单附件
     *
     * <p>自定义申请单里「附件上传 / 图片上传」字段落库时的业务类型（biz_id = orders.id）。
     *
     * <p><b>为什么单独一个类型而不是复用 {@link #APPLY_ATTACHMENT}</b>：
     * 「申请附件」在本系统里是<b>借用申请固定表单的一个位置</b>，而自定义表单的附件
     * 是<b>用户在设计器里拖出来的任意多个字段</b> —— 同一个工单可能有三处附件字段
     * （如「营业执照」「授权书」「现场照片」）。混用同一 biz_type 后，
     * 详情页无法区分「这是哪个字段传的」，工单详情的只读回显只能把所有附件堆在一起。
     * 分开后，每个字段用「biz_type + biz_id + 字段 key」三元组定位自己的附件。
     *
     * <p>归属主体仍是 {@link BizTarget#ORDER}，因此下载鉴权口径与其它工单附件完全一致
     * （申请人 / 审批人 / 实际执行人 / admin 以上，见 {@code AttachmentServiceImpl}）——
     * 新增类型不需要新写一套鉴权。
     */
    CUSTOM_ORDER("自定义工单附件", BizTarget.ORDER, false);

    /** 附件归属主体：决定 biz_id 指向哪张业务表，进而决定下载鉴权口径 */
    public enum BizTarget {
        /** 工单：可见性＝申请人 / 审批人 / 实际执行人 / admin 以上 */
        ORDER,
        /** 设备故障记录：可见性＝上报人 / 当前执行人 / admin 以上 */
        FAULT
    }

    private final String label;
    private final BizTarget target;
    private final boolean imageOnly;

    AttachmentBizType(String label, BizTarget target, boolean imageOnly) {
        this.label = label;
        this.target = target;
        this.imageOnly = imageOnly;
    }

    public String getLabel() {
        return label;
    }

    public BizTarget getTarget() {
        return target;
    }

    /** 照片类附件仅允许图片扩展名 */
    public boolean isImageOnly() {
        return imageOnly;
    }

    public static AttachmentBizType of(String value) {
        if (value == null) {
            return null;
        }
        for (AttachmentBizType type : values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        return null;
    }

    /** 中文名，非法值原样返回，避免展示层出现 null */
    public static String labelOf(String value) {
        AttachmentBizType type = of(value);
        return type == null ? value : type.getLabel();
    }

    /** 全部业务类型名（供测试与前端字典校验「判定与枚举同源」） */
    public static List<String> names() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }
}
