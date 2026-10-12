package com.enterprise.ticket.module.applytype.support;

import com.enterprise.ticket.common.constant.ApprovalMode;

import java.util.List;

/**
 * 系统内置申请类型目录（V48）
 *
 * <h2>它回答什么</h2>
 * 「提交申请」页有 7 张卡片，其中两张是**代码内置**的业务：借用设备、故障报修。
 * 它们的表单不是通用表单模板（借用要选设备、报修要选故障设备），
 * 后置动作也不是通用的「订单流转」（借用要抢占设备锁、报修要建故障记录），
 * 因此它们从来不是 {@code apply_type} 里的行。
 *
 * <p>代价是两个页面口径不一致：管理页只有 5 类、提交页有 7 类。
 * 本目录把这两类的**元数据**集中到一处（代码即事实源），
 * 由 {@link BuiltInApplyInitializer} 播种进 {@code apply_type} 表（{@code built_in = 1}），
 * 让管理页与提交页终于对得上。
 *
 * <h2>「内置」与「预置」的区别（两者都被播种，但语义不同）</h2>
 * <ul>
 *   <li><b>预置</b>（{@link PresetApplyCatalog}）：加班 / 请假 / 用章 / 采购 / 系统权限。
 *       走**通用机制** —— 表单是表单模板、审批是流程模板，管理员可以完全改掉，
 *       也可以整个删掉。它们是「出厂送的一套样例配置」。</li>
 *   <li><b>内置</b>（本类）：借用设备 / 故障报修。
 *       表单与后置动作在**代码**里，管理员改不了也不该改；能改的只有
 *       「表单说明」与「审批流程」。因此它们**不可删除**（见 {@code built_in} 列）。</li>
 * </ul>
 *
 * <h2>为什么不把它们塞进 {@link PresetApplyCatalog}</h2>
 * 那个目录的每一条都带 {@code FormSchema} 与 {@code FlowDefinition}，
 * 走 {@code createFull} 一次性建齐「表单模板 + 流程模板 + 类型」。
 * 内置类恰好**两者都没有**，塞进去会让播种逻辑长出一堆分支，
 * 也让两个目录的调用方都要写特判。分开更诚实。
 *
 * <h2>V50：{@code hasHandler} —— 「有没有处理流程」也是内置元数据</h2>
 * 两个内置类型都不是「审批流」，但**只有报修有处理流程**（通知 → 接单 → 修完）。
 * 借用单的流程是审批流（几级审批、谁来批），报修的流程是处理流（通知给谁、谁接单）——
 * 两者在页面上的呈现与配置项完全不同。把「这个内置类型有没有处理流程」放进本目录，
 * 前端就**不必靠 typeCode 判断**（那种判断在新增第三个内置类时必然被漏掉，
 * 表现为「新增的内置类型卡片上多出一块莫名其妙的配置区」）。
 */
public final class BuiltInApplyCatalog {

    private BuiltInApplyCatalog() {
    }

    /** 借用设备：对应 {@code OrderType.BORROW} 这条内置工单链路 */
    public static final String CODE_BORROW = "BORROW";

    /** 故障报修：对应 {@code device_fault} 模块（**不产生工单**） */
    public static final String CODE_FAULT_REPORT = "FAULT_REPORT";

    /** 一次性的播种标记前缀；与该项目的 {@code preset_apply_seeded:} 同一套约定 */
    public static final String SEED_FLAG_PREFIX = "builtin_apply_seeded:";

    /**
     * 一个内置申请类型的元数据。
     *
     * @param typeCode          类型编码（即 {@code OrderType} 的取值，两边必须一致）
     * @param typeName          卡片标题
     * @param icon              Element Plus 图标组件名
     * @param description       卡片说明（管理员可在页面上改）
     * @param sortOrder         默认排序号（内置类排在最前，故取 10/11 而预置类从 100 起）
     * @param approvalMode      默认审批方式；{@code flowEditable} 为 true 时管理员可改成 FLOW
     * @param flowEditable      是否允许管理员改审批流程
     * @param hasHandler        是否有**处理流程**（非审批流：通知给谁 → 接单 → 修完）。V50 新增
     * @param formEditableNote  表单不可改的原因（展示在配置面板里，回答「为什么这里动不了」）
     */
    public record BuiltIn(String typeCode, String typeName, String icon, String description,
                          int sortOrder, ApprovalMode approvalMode, boolean flowEditable,
                          boolean hasHandler, String formEditableNote) {
    }

    /**
     * 两类内置，顺序即管理页卡片顺序（借用在前 —— 高频操作）。
     *
     * <h2>为什么故障报修的 {@code flowEditable} 是 false</h2>
     * 报修**不产生工单**，它建的是一条 {@code device_fault} 记录，
     * 后续走「待处理 → 处理中 → 已修复 / 无法修复」的处理流转，域模型里根本没有审批节点这一环。
     * 硬给它接一个审批流程，等于要求报修也走一遍建单 + 快照 + 通知的完整链路 ——
     * 那是另一个功能，不是一次配置开放。
     *
     * <p>V50 之前，这里只能如实标注「无需审批」，页面上就只剩一句干巴巴的说明；
     * 现在有了 {@code hasHandler}，接管它的是**处理流程**配置区：
     * 它没有审批节点，但确实有「通知给谁」这一项需要管理员配置。
     */
    public static List<BuiltIn> all() {
        return List.of(borrow(), faultReport());
    }

    /** 借用设备：默认走「按部门逐级审批 / 系统预置三级流程」，两者都可被管理员换成自定义流程 */
    public static BuiltIn borrow() {
        return new BuiltIn(
                CODE_BORROW,
                "借用设备",
                "Box",
                "选设备、填归还日期即可提交",
                10,
                ApprovalMode.GROUP,
                true,
                false,
                "借用表单由系统提供：必须先选定设备（系统会加临时锁），再填归还日期与用途。");
    }

    /**
     * 故障报修：登记故障记录并派给 IT 处理，**无审批环节、但有处理流程**。
     *
     * <p>{@code formEditableNote} 措辞刻意从「无需审批」改成「不经过审批，处理流程见下」——
     * 前者是一个**否定式**的说法，管理员读完只知道「这里没有东西」，
     * 而实际上有一整条处理链路（通知 → 接单 → 修完填结果）是可以在下面配的。
     */
    public static BuiltIn faultReport() {
        return new BuiltIn(
                CODE_FAULT_REPORT,
                "故障报修",
                "Warning",
                "设备报修，登记故障记录",
                11,
                ApprovalMode.NONE,
                false,
                true,
                "报修表单由系统提供：选定故障设备、填写故障描述即可提交。"
                        + "提交后不经过审批，而是直接进入维修处理 —— 通知谁、谁接单，由下方「处理流程」决定。");
    }

    /** 按编码取内置类型；不是内置类型时返回 {@code null} */
    public static BuiltIn of(String typeCode) {
        if (typeCode == null) {
            return null;
        }
        for (BuiltIn builtIn : all()) {
            if (builtIn.typeCode().equals(typeCode)) {
                return builtIn;
            }
        }
        return null;
    }

    /** 是否为内置类型编码 */
    public static boolean isBuiltIn(String typeCode) {
        return of(typeCode) != null;
    }

    /** 全部内置编码（供清理白名单等同源引用，避免两处各写一份而漂移） */
    public static List<String> codes() {
        return all().stream().map(BuiltIn::typeCode).toList();
    }
}
