package com.enterprise.ticket.common.constant;

import java.util.Arrays;
import java.util.List;

/**
 * 动态表单字段类型
 *
 * <h2>为什么把「类型元数据」写进枚举</h2>
 * <p>字段类型决定了三件事：<b>能不能配选项</b>、<b>值长什么样</b>（校验依据）、
 * <b>前端用哪个控件渲染</b>。这三条如果分别写在「设计器」「校验器」「渲染器」三处，
 * 新增一种字段类型就必须记得改三个地方 —— 漏一处就是「能拖进画布但保存报错」
 * 或「存进去却不校验」这类最难发现的缺陷。
 *
 * <p>因此这里把「能不能配选项」「值的种类」收敛到枚举上，前后端都从这一份元数据派生行为。
 * 枚举名与 {@code schema_json} 里存的 {@code type} 值<b>逐字对应</b>，
 * 前端 {@code types/form.ts} 是它的镜像（新增类型需同步两处，测试里有对齐断言）。
 *
 * <h2>布局类字段不产生数据</h2>
 * <p>{@link #DESCRIPTION} / {@link #DIVIDER} 只影响展示，不参与取值与校验，
 * 因此 {@link #isDataField()} 为 {@code false} —— 校验器据此跳过它们，
 * 也避免它们出现在「必填」配置里造成「说明文字必填」这种荒谬要求。
 */
public enum FormFieldType {

    // ---------------- 基础 ----------------
    /** 单行文本 */
    TEXT("单行文本", FieldCategory.BASIC, ValueKind.TEXT, false),
    /** 多行文本 */
    TEXTAREA("多行文本", FieldCategory.BASIC, ValueKind.TEXT, false),
    /** 数字 */
    NUMBER("数字", FieldCategory.BASIC, ValueKind.NUMBER, false),
    /** 日期 */
    DATE("日期", FieldCategory.BASIC, ValueKind.DATE, false),
    /** 日期时间 */
    DATETIME("日期时间", FieldCategory.BASIC, ValueKind.DATETIME, false),

    // ---------------- 选择 ----------------
    /** 单选下拉 */
    SELECT("单选下拉", FieldCategory.SELECT, ValueKind.OPTION, true),
    /** 多选下拉 */
    MULTI_SELECT("多选下拉", FieldCategory.SELECT, ValueKind.OPTION_MULTI, true),
    /** 单选框 */
    RADIO("单选框", FieldCategory.SELECT, ValueKind.OPTION, true),
    /** 复选框 */
    CHECKBOX("复选框", FieldCategory.SELECT, ValueKind.OPTION_MULTI, true),

    // ---------------- 高级 ----------------
    /** 附件上传 */
    FILE("附件上传", FieldCategory.ADVANCED, ValueKind.FILE, false),
    /** 图片上传 */
    IMAGE("图片上传", FieldCategory.ADVANCED, ValueKind.FILE, false),
    /** 人员选择 */
    USER("人员选择", FieldCategory.ADVANCED, ValueKind.USER_REF, false),
    /** 设备选择 */
    DEVICE("设备选择", FieldCategory.ADVANCED, ValueKind.DEVICE_REF, false),
    /** 部门选择（任意层级） */
    BIZ_GROUP("部门选择", FieldCategory.ADVANCED, ValueKind.GROUP_REF, false),
    /**
     * 一级分部选择（V53）
     *
     * <h2>为什么是独立类型，而不是给「部门选择」加一个「仅一级」开关</h2>
     * 这个字段的业务语义**就是**选一级分部，后续多处业务都会持续复用它
     * （小额免审批额度、处理组绑定、出差目标分部、工单分配）。
     * 若做成通用组件上的开关，会有三个真实代价：
     * <ul>
     *   <li><b>易误用</b>：管理员建表单时随手勾选/取消，一不小心就能选子部门 ⇒ 工单分配错误；</li>
     *   <li><b>隐性配置</b>：以后每个与分部相关的新功能都要记得打开它，排查问题要靠猜；</li>
     *   <li><b>语义模糊</b>：日志、数据库、接口里看不出这是分部，只是普通部门字段多了个标记。</li>
     * </ul>
     * 独立类型则<b>底层固定只能选组织树一级部门、没有开关、不能切成全层级</b>。
     *
     * <p>取值仍是部门 id，故沿用 {@link ValueKind#GROUP_REF}；
     * 「只能选一级」由渲染器与校验器各自依据本类型判定，不依赖任何配置。
     */
    ROOT_DEPT("一级分部选择", FieldCategory.ADVANCED, ValueKind.GROUP_REF, false),

    // ---------------- 布局（不产生数据） ----------------
    /** 说明文字（只读） */
    DESCRIPTION("说明文字", FieldCategory.LAYOUT, ValueKind.NONE, false),
    /** 分隔线 */
    DIVIDER("分隔线", FieldCategory.LAYOUT, ValueKind.NONE, false);

    /** 设计器左侧面板的分组 */
    public enum FieldCategory {
        BASIC("基础"),
        SELECT("选择"),
        ADVANCED("高级"),
        LAYOUT("布局");

        private final String label;

        FieldCategory(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /**
     * 值的种类 —— 校验器据此选择校验策略。
     *
     * <p>刻意不复用 Java 类型或 JSON 类型：这些字段最终以 JSON 存进
     * {@code form_data_json}，需要描述的是「校验语义」（是单选还是多选？
     * 是引用 id 还是自由文本？），而不是某个语言的类型。
     */
    public enum ValueKind {
        /** 不取值（布局类） */
        NONE,
        /** 自由文本 */
        TEXT,
        /** 数字（含小数与范围） */
        NUMBER,
        /** 日期（yyyy-MM-dd） */
        DATE,
        /** 日期时间（yyyy-MM-dd HH:mm:ss） */
        DATETIME,
        /** 单选：值必须命中选项集合 */
        OPTION,
        /** 多选：值必须是非空数组且每项命中选项集合 */
        OPTION_MULTI,
        /** 附件 id 数组（附件由既有上传能力落库，表单里存附件 id） */
        FILE,
        /** 人员 id（必须是在职启用的员工） */
        USER_REF,
        /** 设备 id（必须存在） */
        DEVICE_REF,
        /** 部门 id */
        GROUP_REF
    }

    private final String label;
    private final FieldCategory category;
    private final ValueKind valueKind;
    private final boolean supportsOptions;

    FormFieldType(String label, FieldCategory category, ValueKind valueKind, boolean supportsOptions) {
        this.label = label;
        this.category = category;
        this.valueKind = valueKind;
        this.supportsOptions = supportsOptions;
    }

    public String getLabel() {
        return label;
    }

    public FieldCategory getCategory() {
        return category;
    }

    public ValueKind getValueKind() {
        return valueKind;
    }

    /** 是否需要在属性面板配置「选项列表」 */
    public boolean isSupportsOptions() {
        return supportsOptions;
    }

    /**
     * 是否为「数据字段」。
     *
     * <p>布局类字段（说明文字 / 分隔线）只是视觉元素，无 key、无值、不参与校验；
     * 发布校验与数据校验都据此跳过它们。
     */
    public boolean isDataField() {
        return valueKind != ValueKind.NONE;
    }

    /** 是否为「多值」字段（值为数组），供前端渲染与校验共用 */
    public boolean isMultiValued() {
        return valueKind == ValueKind.OPTION_MULTI || valueKind == ValueKind.FILE;
    }

    public static FormFieldType of(String value) {
        if (value == null) {
            return null;
        }
        for (FormFieldType type : values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        return null;
    }

    /** 全部数据字段类型名（供测试与前端字典对齐断言使用） */
    public static List<String> dataFieldNames() {
        return Arrays.stream(values()).filter(FormFieldType::isDataField).map(Enum::name).toList();
    }
}
