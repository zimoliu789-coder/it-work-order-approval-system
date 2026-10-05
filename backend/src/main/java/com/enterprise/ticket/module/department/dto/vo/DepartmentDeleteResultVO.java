package com.enterprise.ticket.module.department.dto.vo;

import lombok.Data;

/**
 * 删除部门的结果。
 *
 * <p>为什么要返回它而不是一个空响应：需求文档要求删除前提示
 * 「该部门下有 X 人，确认删除后人员移到上一级部门」。X 必须由**同一个数据源**
 * 给出（前端自己数一遍会与服务端实际挪动的人数出现偏差），
 * 因此这里把「挪了多少人、挪到哪个部门」如实回给调用方展示。
 */
@Data
public class DepartmentDeleteResultVO {

    /** 被删除的部门名称（用于成功提示文案） */
    private String deptName;

    /** 被上移到父部门的人数 */
    private long movedMemberCount;

    /** 人员被上移到的部门（父部门）ID；删除的是根下的一级部门时为根节点 id */
    private Long movedToDepartmentId;

    /** 人员被上移到的部门名称 */
    private String movedToDepartmentName;
}
