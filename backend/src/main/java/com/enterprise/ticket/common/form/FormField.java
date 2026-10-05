package com.enterprise.ticket.common.form;

import lombok.Data;

/**
 * 动态表单字段定义
 *
 * <p>与前端设计器「右侧属性面板」一一对应：面板上能配什么，这里就有什么字段。
 * 该类同时用于两个方向：
 * <ol>
 *   <li><b>写入</b>：设计器保存草稿时，前端把它序列化成 JSON 落进
 *       {@code form_template_version.schema_json}；</li>
 *   <li><b>读取</b>：提交时服务端把它反序列化回来，作为校验依据。</li>
 * </ol>
 *
 * <h2>为什么所有「类型特定配置」都平铺在一个类里</h2>
 * <p>用多态（每个字段类型一个子类）看起来更整洁，但会带来两个实际问题：
 * ① Jackson 反序列化多态需要 {@code @JsonTypeInfo} 与子类注册，schema 的 JSON 会多出类型标签，
 * 让「存档的 schema」与「设计器导出的 schema」不再是同一份东西；
 * ② 用户在设计器里切换字段类型（如「文本 → 数字」）时，多态结构会丢掉已配好的公共属性。
 * <p>因此这里保持平铺：某个类型的专属字段对别的类型为空，由
 * {@link FormSchemaValidator} 按类型校验「该配的都配了」。
 */
@Data
public class FormField {

    /** 字段 key：英文 + 数字 + 下划线，不以数字开头；同一模板内唯一。布局类字段可为空 */
    private String key;

    /** 显示名称 */
    private String label;

    /** 字段类型，取值见 {@link com.enterprise.ticket.common.constant.FormFieldType} */
    private String type;

    /** 是否必填（布局类字段恒为 false） */
    private Boolean required;

    /** 占位提示 */
    private String placeholder;

    /** 帮助说明（展示在控件下方） */
    private String help;

    /**
     * 栅格宽度：1 = 整行，2 = 半行。
     *
     * <p>刻意只支持 1/2 两种：企业内网表单的实际排布需求就是「整行」与「两个一行」，
     * 放开到 12 栅格只会让设计器出现 5/12 这种既难对齐、手机上又必然塌成一列的宽度。
     */
    private Integer width;

    /** 选项列表（仅选项类字段：单选/多选下拉、单选框、复选框） */
    private java.util.List<FormOption> options;

    /** 默认值（文本类为字符串，多选类为 JSON 数组字符串；由前端生成，服务端只存不解析） */
    private String defaultValue;

    // ---------------- 数字 ----------------
    private Double min;
    private Double max;
    /** 小数位数 0–4；0 表示整数 */
    private Integer precision;
    /** 单位（如「元」「台」），仅展示用 */
    private String unit;

    // ---------------- 文本 ----------------
    private Integer minLength;
    private Integer maxLength;
    /** 正则校验（后端会编译校验，非法正则直接拒绝保存） */
    private String pattern;

    // ---------------- 日期 / 日期时间 ----------------
    /**
     * 日期范围限制：NONE / NOT_BEFORE_TODAY / NOT_AFTER_TODAY / CUSTOM。
     *
     * <p>用枚举字符串而非布尔对：需求里「不早于今天」「不晚于今天」「自定义区间」
     * 是三种互斥的场景，用两个布尔会表达出「既不能早于也不能晚于今天」这种无意义的组合。
     */
    private String dateLimit;
    /** 自定义区间下限（yyyy-MM-dd；仅 dateLimit=CUSTOM 时有意义） */
    private String dateMin;
    /** 自定义区间上限（yyyy-MM-dd；仅 dateLimit=CUSTOM 时有意义） */
    private String dateMax;

    // ---------------- 附件 / 图片 ----------------
    /** 数量上限（1–10） */
    private Integer maxCount;
    /** 允许的扩展名（逗号分隔，可空 = 用系统默认白名单） */
    private String fileTypes;
    /** 单文件大小上限 MB（1–50） */
    private Integer maxSizeMb;

    // ---------------- 布局类 ----------------
    /** 说明文字内容（仅 DESCRIPTION 使用） */
    private String content;
}
