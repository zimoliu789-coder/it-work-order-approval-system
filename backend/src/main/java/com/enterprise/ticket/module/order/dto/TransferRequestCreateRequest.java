package com.enterprise.ticket.module.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发起转单申请（V55 / D5.7）。
 *
 * <h2>为什么同组与跨组共用一个请求体，而不是两个接口</h2>
 * <p>两者的差别只在「填哪几个字段」：同组填目标执行人，跨组填目标小组。
 * 拆成两个接口会让前端必须先判定自己有没有跨组权限才知道该调哪个 ——
 * 而那个判定本来就在服务端。这里用一个 {@code scope} 显式表态，
 * 服务端各自校验，前端只管"我选的是哪一种"。
 */
@Data
public class TransferRequestCreateRequest {

    /**
     * 转单范围：`SAME_GROUP` 同组转单 / `CROSS_GROUP` 跨组转单。
     *
     * <p>必填且由前端显式选择 —— 不让服务端「按目标推断」：
     * 否则组员选了外组的人才会被拒绝，而他在选之前根本不知道自己没这个权限。
     */
    @NotBlank(message = "请选择转单类型（同组 / 跨组）")
    private String scope;

    /** 同组转单：本组内的目标执行人 user_id */
    private Long targetHandlerId;

    /** 跨组转单：目标处理小组（部门）id */
    private Long targetDepartmentId;

    /** 转单原因（必填，审计要素） */
    @NotBlank(message = "请填写转单原因")
    @Size(max = 500, message = "转单原因不能超过 500 字")
    private String reason;
}
