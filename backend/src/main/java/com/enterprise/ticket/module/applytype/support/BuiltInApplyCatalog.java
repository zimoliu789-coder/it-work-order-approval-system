package com.enterprise.ticket.module.applytype.support;

import com.enterprise.ticket.common.constant.ApprovalMode;
import com.enterprise.ticket.common.constant.FormFieldType;
import com.enterprise.ticket.common.form.FormField;
import com.enterprise.ticket.common.form.FormSchema;
import com.enterprise.ticket.common.trip.BusinessTripRules;

import java.util.ArrayList;
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
 *   <li><b>内置</b>（本类）：借用设备 / 故障报修 / 出差申请。
 *       <b>V59 之前</b>它们连表单模板都没有（表单是提交页硬编码），能改的只有说明与流程；
 *       <b>V59 起</b>（D3「表单、审批流程完全放开可自定义」）两者也播下了真实表单模板，
 *       表单面与预置类**完全同权**：可增删字段、改必填、调顺序。
 *       剩下的唯一差别是<b>不可删除</b>（{@code built_in} 列）——
 *       删掉它，后端链路（{@code OrderType.BORROW} / {@code device_fault}）照旧在跑，
 *       结果是「功能还在、入口没了」，比直接报错更难发现。</li>
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

    /**
     * 出差申请（V53）：对应 {@code OrderType.TRIP}。
     *
     * <p>它是第三个内置类型，与前两个的差异已从「有没有表单」变成「走不走设备闭环」：
     * V59 起三者都有播下来的真实表单模板版本，提交都走通用动态表单链路；
     * 差别在于出差单**没有设备**（{@code OrderType#isFormDriven}），
     * 而借用单仍承载交付 / 归还生命周期、报修单则根本不产生工单。
     */
    public static final String CODE_TRIP = "TRIP";

    /** 一次性的播种标记前缀；与该项目的 {@code preset_apply_seeded:} 同一套约定 */
    public static final String SEED_FLAG_PREFIX = "builtin_apply_seeded:";

    /**
     * 一个内置申请类型的元数据。
     *
     * @param typeCode          类型编码（即 {@code OrderType} 的取值，两边必须一致）
     * @param typeName          卡片标题
     * @param icon              Element Plus 图标组件名
     * @param description       卡片说明（管理员可在页面上改）
     * @param sortOrder         默认排序号（取 10/11，预置类从 100 起，给自建类留出插队空间）
     *                          —— 这只是**初始**顺序，管理员可在配置页拖拽调整（含内置类型）
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
        return List.of(borrow(), faultReport(), trip());
    }

    /** 借用设备：默认走「按部门逐级审批 / 系统预置三级流程」，两者都可被管理员换成自定义流程 */
    public static BuiltIn borrow() {
        return new BuiltIn(
                CODE_BORROW,
                "借用设备",
                "Box",
                "选设备类别与型号、填归还日期即可提交",
                10,
                ApprovalMode.GROUP,
                true,
                false,
                "表单字段可自由增删（类别 / 二级分类 / 型号 / 所在分部 / 归还日期是系统字段，删了会让交付校验失效，故不可删）；"
                        + "审批流程可在下方调整。");
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
                "表单字段可自由增删（故障设备 / 发生时间 / 描述 / 所在分部是系统字段，删了维修处理就缺依据，故不可删）；"
                        + "报修不经过审批，通知谁、谁接单由下方「处理流程」决定。");
    }

    /**
     * 出差申请（V53）：走分组审批（与借用单同款，{@code flowEditable} 允许换成自定义流程）。
     *
     * <p>排序取 12：仍居内置区（10/11/12），排在借用与报修之后 —— 出差频次低于前两者，
     * 且它是唯一带动态表单的内置类，放最后与自定义区的视觉过渡更自然。
     */
    public static BuiltIn trip() {
        return new BuiltIn(
                CODE_TRIP,
                "出差申请",
                "Suitcase",
                "登记出差起止日期与目标一级分部",
                12,
                ApprovalMode.GROUP,
                true,
                false,
                "出差表单的三个字段（开始 / 结束日期、目标一级分部）是重叠校验与"
                        + "「当前所在分部」预填的数据依据，由系统提供、不可删除；"
                        + "审批流程可在下方调整。");
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

    /**
     * 内置类型自带的表单 schema；没有表单模板的内置类型返回 {@code null}。
     *
     * <h2>V59 / 批次 3.5：借用与报修从此也有 schema</h2>
     * 改造前只有出差申请有 schema（V53），借用 / 报修的「表单」是提交页里的硬编码页面，
     * {@code form_template_version_id} 恒为 NULL。D3 裁定「表单、审批流程完全放开可自定义」，
     * 因此这里给两者各补一份种子 schema：播种器据此建<b>真实的表单模板 + 首发版本</b>，
     * 管理员随后就能在设计器里增删字段、改必填、调顺序 —— 与改预置类完全同一条路。
     *
     * <h2>哪些字段是 systemField，为什么</h2>
     * <ul>
     *   <li><b>借用</b>：一级分类 / 二级分类 / 型号（R3：IT 扫码三层校验的比对依据）、
     *       当前所在分部（D5：处理小组匹配的起点）、归还日期（订单必列 + 条件求值依据）。
     *       删掉任何一个，对应的业务逻辑都会<b>静默失效</b>；</li>
     *   <li><b>报修</b>：故障设备（记录的存在意义）、故障发生时间与描述
     *       （处理流的必填项）、当前所在分部（分部报修量的聚合依据）。</li>
     *   <li><b>用途 / 其它</b>：不标记 —— 它们是纯信息字段，删了不影响任何链路。</li>
     * </ul>
     * systemField 的语义是「不可删、不可改 key 与类型，可改显示名 / 必填 / 排序」，
     * 由 {@code FormSchemaValidator#assertSystemFieldsKept} 在发布时强制。
     *
     * <h2>key 为什么是这些字面值</h2>
     * 提交页按 key 把 schema 值映射回既有入参（{@code OrderCreateRequest} /
     * {@code DeviceFaultRequest} 的固定字段），后端按固定 key 读业务数据。
     * key 与 {@link #fieldKeysOf} 的清单逐字对应 —— 改一个字母，
     * 提交时就会出现「字段在、值不进去」且不报错。
     */
    public static FormSchema formSchemaOf(String typeCode) {
        if (CODE_BORROW.equals(typeCode)) {
            return borrowFormSchema();
        }
        if (CODE_FAULT_REPORT.equals(typeCode)) {
            return faultReportFormSchema();
        }
        if (CODE_TRIP.equals(typeCode)) {
            return tripFormSchema();
        }
        return null;
    }

    // ---------------- 借用设备（V59 / 第 9 项 + R3） ----------------

    /** 借用单「一级分类」字段 key（提交页映射到 {@code OrderCreateRequest.primaryCategoryId}） */
    public static final String BORROW_FIELD_PRIMARY_CATEGORY = "primary_category_id";
    /** 借用单「二级分类」字段 key */
    public static final String BORROW_FIELD_SECONDARY_CATEGORY = "secondary_category_id";
    /** 借用单「型号」字段 key */
    public static final String BORROW_FIELD_MODEL = "model_id";
    /** 借用单「当前所在分部」字段 key */
    public static final String BORROW_FIELD_DIVISION = "current_division_id";
    /** 借用单「归还日期」字段 key */
    public static final String BORROW_FIELD_RETURN_DATE = "expected_return_date";
    /** 借用单「用途」字段 key（非系统字段，可自由删改） */
    public static final String BORROW_FIELD_REASON = "reason";

    /**
     * 借用表单：分类三级 + 分部 + 归还日期 + 用途。
     *
     * <p>「用途」之后是**自定义字段区**：管理员加的字段全部排在这些系统字段之后，
     * 值随表单数据一起落 {@code order_form_data}（见 {@code OrderServiceImpl#create}）。
     */
    private static FormSchema borrowFormSchema() {
        FormField primary = field(BORROW_FIELD_PRIMARY_CATEGORY, "设备类别",
                FormFieldType.DEVICE_CATEGORY, true, 2);
        primary.setHelp("交付时这台设备的类别必须与所选类别一致");
        FormField secondary = field(BORROW_FIELD_SECONDARY_CATEGORY, "二级分类",
                FormFieldType.DEVICE_SUBCATEGORY, false, 2);
        secondary.setHelp("选填，留空表示该一级类别下哪种子类都行");
        FormField model = field(BORROW_FIELD_MODEL, "型号",
                FormFieldType.DEVICE_MODEL, false, 2);
        model.setHelp("选填，留空表示同类别内哪台都行");
        FormField division = field(BORROW_FIELD_DIVISION, "当前所在分部",
                FormFieldType.ROOT_DEPT, false, 2);
        division.setHelp("默认按你当前所在分部预填，可手动修改");
        FormField returnDate = field(BORROW_FIELD_RETURN_DATE, "归还日期",
                FormFieldType.DATE, true, 2);
        returnDate.setHelp("不得早于今天");
        FormField reason = field(BORROW_FIELD_REASON, "用途",
                FormFieldType.TEXTAREA, false, 1, false);
        reason.setHelp("选填，一句话说明用途即可");

        FormSchema schema = new FormSchema();
        schema.setFields(new ArrayList<>(List.of(primary, secondary, model, division, returnDate, reason)));
        return schema;
    }

    // ---------------- 故障报修（V59 / 第 10 项 + V54） ----------------

    /** 报修单「故障设备」字段 key（提交页两种填报方式最终都落到设备 id） */
    public static final String FAULT_FIELD_DEVICE = "device_id";
    /** 报修单「故障发生时间」字段 key */
    public static final String FAULT_FIELD_OCCURRED_AT = "occurred_at";
    /** 报修单「故障描述」字段 key */
    public static final String FAULT_FIELD_DESCRIPTION = "fault_description";
    /** 报修单「当前所在分部」字段 key */
    public static final String FAULT_FIELD_DIVISION = "current_division_id";

    /**
     * 报修表单：设备 + 时间 + 描述 + 分部。
     *
     * <p>「故障照片」**不在** schema 里：附件按业务类型单独走上传通道
     * （{@code FAULT_PHOTO} 绑定故障记录 id），与改造前完全一致 ——
     * 把它塞进表单字段只会让「先传图还是先建单」的顺序问题重新出现。
     *
     * <p>「我名下的设备 / 公共设备」两种填报方式也不在 schema 里：
     * 那是**同一个设备字段的两种填法**（下拉选择 / 扫码查编号），
     * 由提交页的切换器控制，最终都写 {@link #FAULT_FIELD_DEVICE}。
     */
    private static FormSchema faultReportFormSchema() {
        FormField device = field(FAULT_FIELD_DEVICE, "故障设备",
                FormFieldType.DEVICE, true, 1);
        device.setHelp("可从我名下的设备里选，也可扫资产编号填报公共设备");
        FormField occurredAt = field(FAULT_FIELD_OCCURRED_AT, "故障发生时间",
                FormFieldType.DATETIME, true, 2);
        occurredAt.setHelp("不得晚于当前时间");
        FormField description = field(FAULT_FIELD_DESCRIPTION, "故障描述",
                FormFieldType.TEXTAREA, true, 1);
        description.setHelp("请描述故障现象，便于处理人判断");
        FormField division = field(FAULT_FIELD_DIVISION, "当前所在分部",
                FormFieldType.ROOT_DEPT, false, 2);
        division.setHelp("用于统计各分部报修量，默认按你当前所在分部预填");

        FormSchema schema = new FormSchema();
        schema.setFields(new ArrayList<>(List.of(device, occurredAt, description, division)));
        return schema;
    }

    /** 出差申请表单：起止日期（半行 × 2）+ 目标一级分部（整行），全部必填、全部系统内置字段 */
    private static FormSchema tripFormSchema() {
        FormField start = field(BusinessTripRules.FIELD_START_DATE, "出差开始日期",
                FormFieldType.DATE, true, 2);
        start.setHelp("同一时间只能有一份生效出差：与已审批通过的出差单日期重叠时无法提交");
        FormField end = field(BusinessTripRules.FIELD_END_DATE, "出差结束日期",
                FormFieldType.DATE, true, 2);
        end.setHelp("出差结束日期不得早于开始日期");
        FormField target = field(BusinessTripRules.FIELD_TARGET_DEPT, "目标一级分部",
                FormFieldType.ROOT_DEPT, true, 1);
        target.setHelp("只能选择组织树的一级分部；报修 / 借用单的「当前所在分部」会按在途出差自动预填");

        FormSchema schema = new FormSchema();
        schema.setFields(new ArrayList<>(List.of(start, end, target)));
        return schema;
    }

    private static FormField field(String key, String label, FormFieldType type,
                                   boolean required, int width) {
        return field(key, label, type, required, width, true);
    }

    /**
     * @param systemField 是否系统内置字段。默认 true（内置 schema 的绝大多数字段都是）；
     *                    传 false 表示「纯信息字段」—— 删了不影响任何业务链路，
     *                    如借用单的「用途」。这一档必须显式存在，否则所有字段都会被
     *                    无差别保护，D3 的「完全放开」就名不副实。
     */
    private static FormField field(String key, String label, FormFieldType type,
                                   boolean required, int width, boolean systemField) {
        FormField f = new FormField();
        f.setKey(key);
        f.setLabel(label);
        f.setType(type.name());
        f.setRequired(required);
        f.setWidth(width);
        // 系统内置字段：设计器禁止删除，允许改显示名 / 必填 / 排序（片1 机制）
        f.setSystemField(systemField);
        return f;
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
