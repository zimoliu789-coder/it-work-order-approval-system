package com.enterprise.ticket.module.department.dto.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 「分部 → 处理小组」配置总览（V53）。
 *
 * <p>一次返回三样东西，让配置页可以<b>一次渲染完</b>，不必再为「默认组叫什么」
 * 单独发一个请求：
 * <ol>
 *   <li>全部一级分部（含是否已绑定、绑的是谁）—— 页面主体，漏配的一眼可见；</li>
 *   <li>全部可选的的处理小组 —— 下拉数据源；</li>
 *   <li>当前全局默认组 —— 兜底说明文案要显示它的名字。</li>
 * </ol>
 */
@Data
public class DivisionHandlerConfigVO {

    /** 全局默认处理小组 id（可为空 = 未配置兜底） */
    private Long defaultHandlerDepartmentId;

    /** 全局默认处理小组名称 */
    private String defaultHandlerDepartmentName;

    /** 全部一级分部（按部门树顺序），每项带「已绑定的处理小组」 */
    private List<DivisionBinding> divisions = new ArrayList<>();

    /** 一个一级分部的绑定情况 */
    @Data
    public static class DivisionBinding {

        /** 一级分部 id */
        private Long divisionId;

        /** 一级分部名称 */
        private String divisionName;

        /** 当前绑定的处理小组 id；null = 未绑定（会用全局默认兜底） */
        private Long handlerDepartmentId;

        /** 当前绑定的处理小组名称 */
        private String handlerDepartmentName;
    }
}
