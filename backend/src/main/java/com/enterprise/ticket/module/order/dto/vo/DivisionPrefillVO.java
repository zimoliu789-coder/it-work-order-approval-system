package com.enterprise.ticket.module.order.dto.vo;

import com.enterprise.ticket.module.department.dto.vo.DepartmentOptionVO;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 「当前所在分部」的预填结果（V54 / D5.2）。
 *
 * <h2>为什么把「可选分部列表」也塞进来</h2>
 * <p>提交页需要一个「只列**启用的一级分部**」的下拉。若前端自己拿
 * {@code /api/departments/options} 再按 {@code depth === 1} 过滤，会漏掉「启用状态」这一维
 * （options 接口不保证只含启用部门），于是下拉里会出现已停用的分部 ——
 * 选中后提交被后端拒绝，而员工看不出为什么。
 *
 * <p>把列表与预填值一起下发，等于告诉前端：「这就是你能选的全部」。
 * 也顺带省掉一次请求。
 */
@Data
public class DivisionPrefillVO {

    /** 预填的分部 id；null = 取不到（员工未分配部门，或部门树里没有一级分部） */
    private Long divisionId;

    /** 预填的分部名称（用于提示文案） */
    private String divisionName;

    /**
     * 预填来源：{@code HR_PRIMARY} / {@code TRIP_PREFILL}；null = 没有预填值。
     *
     * <p>这是「系统给的默认值」的来源，不是最终落库的来源 ——
     * 员工若手动改了，落库来源是 {@code MANUAL}（由服务端比对判定）。
     */
    private String source;

    /** 来源中文名（前端直接展示，避免前端再维护一份枚举映射） */
    private String sourceLabel;

    /** 出差自动带入时的出差单号；仅 {@code TRIP_PREFILL} 有值（提示「来自哪个出差单」） */
    private String tripOrderNo;

    /**
     * 可选择的一级分部（**仅启用**），供下拉渲染。
     *
     * <p>顺序 = 部门树的 depth/sortOrder/id 顺序，与组织页一致。
     */
    private List<DepartmentOptionVO> options = new ArrayList<>();
}
