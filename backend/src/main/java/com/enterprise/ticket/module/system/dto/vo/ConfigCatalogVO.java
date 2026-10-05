package com.enterprise.ticket.module.system.dto.vo;

import java.util.List;

/**
 * 系统参数目录（配置页的数据源， / ）。
 *
 * <h2>为什么不复用 {@code GET /api/system/configs} 的扁平结构</h2>
 * <p>既有接口返回的是一个<b>数组</b>（{@code data} 直接是 {@code SystemConfig[]}），
 * 而卡片式配置页需要「分组 + 标签 + 控件类型 + 区间」这类元信息。
 * 往数组里塞元信息只能改成对象包一层 —— 那会改掉既有响应的形状，
 * 让依赖它的回归用例与旧前端一起失效。因此<b>新增一个接口</b>下发目录，
 * 旧接口原样保留（它的消费方只剩回归脚本，改它没有任何收益）。
 *
 * <h2>为什么 {@code items} 与 {@code sections} 同时下发（ 新增）</h2>
 * <p> 的重组把「一张卡 = 一件事」改成了「一张卡 = 一个业务域」，
 * 于是卡片内部需要小标题、分区角标与分区参考说明（见 {@code SystemConfigCatalog.Section}）。
 * 但配置页<b>除了渲染之外</b>还有四件事需要「把整组当成一个项的集合」：
 * 按 key 查项、遍历校验、统计改动、一键恢复默认。若只下发 {@code sections}，
 * 这四处都要各自写一遍「两层遍历 + 展平」，四处里漏掉一处就会出现
 * 「某项明明在页面上、校验却看不到它」这类看不见的缺陷。
 *
 * <p>因此服务层同时下发两份视图：{@code sections} 只负责「怎么画」，
 * {@code items} 是把它展平后的结果，只负责「有哪些项」。两者必须严格互为
 * 「展平」关系，由单测钉住（{@code SystemConfigServiceImplTest} 与
 * {@code SystemConfigCatalogTest} 各管一侧）。多传的这一份是纯派生数据，
 * 没有第二次计算机会产生分歧。
 *
 * <h2>为什么 value 与 defaultValue 同时下发</h2>
 * <p>「恢复默认」按钮要把该卡片所有项回填成 {@code defaultValue} 并标为「已改动」，
 * 而「已改动」的判定需要一个基准 —— 这就是当前值 {@code value}。
 * 两者都由服务端给出，前端不必内置任何默认值常量（内置就会与迁移脚本初值漂移）。
 *
 * <h2>密文项下发什么</h2>
 * <p>密文项（{@code secret=true}）的 {@code value} 是<b>掩码 {@code ****}</b> 而非密文：
 * 密文对界面毫无意义，回显它反而把「密文长度 / 版本前缀」暴露给前端。
 * 未设置时下发空串，前端据此区分「没配过」与「配过但我不告诉你」。
 */
public record ConfigCatalogVO(List<GroupVO> groups) {

    /**
     * 一个参数分组（= 一张卡片）
     *
     * @param code        分组编码（左侧目录与滚动高亮用，不显示）
     * @param label       卡片标题
     * @param description 卡片顶部的一句通俗说明
     * @param badge       卡片角标；无则 {@code null}（重组后角标已下沉到分区）
     * @param notes       卡片底部参考说明
     * @param collapsed   是否默认折叠
     * @param sections    组内分区（只负责渲染，顺序即界面顺序）
     * @param items       组内全部项（{@code sections} 展平后的结果，只负责「有哪些项」）
     */
    public record GroupVO(String code, String label, String description, String badge,
                          List<String> notes, boolean collapsed,
                          List<SectionVO> sections, List<ItemVO> items) {
    }

    /**
     * 组内分区（= 卡片内部的一小节， 新增）
     *
     * @param code         分区编码（前端取键用，不显示在界面上）
     * @param label        小标题；{@code null} 表示不渲染小标题（单分区分组）
     * @param description  小标题下的一句引导语；无则 {@code null}
     * @param badge        分区角标（如「暂未启用」）；无则 {@code null}
     * @param notes        分区底部参考说明
     * @param dependsOnKey 受哪个开关键控制；{@code null} 表示不受控制。
     *                     前端据此做「开关关闭 ⇒ 本分区除开关自身外全部置灰」，
     *                     与后端 {@code SystemConfigServiceImpl#updateValues} 的
     *                     拒绝口径必须完全一致（前端是体验、后端是边界）
     * @param items        分区内的参数项
     */
    public record SectionVO(String code, String label, String description, String badge,
                            List<String> notes, String dependsOnKey, List<ItemVO> items) {
    }

    /**
     * 一个参数项
     *
     * @param key          配置键（提交时用它做 map 的 key）
     * @param label        中文标签
     * @param type         控件类型：TOGGLE / NUMBER / TEXT / PASSWORD / SELECT
     * @param unit         单位后缀
     * @param value        当前值（密文项为掩码或空串）
     * @param defaultValue 内置默认值（「恢复默认」回填用）
     * @param description  灰色小字说明
     * @param editable     当前调用者是否可改（非内置超管改仅内置超管项时为 false）
     * @param adminOnly    该项是否「仅内置超管可改」
     * @param secret       是否密文项
     * @param required     是否不允许留空
     * @param widget       特殊渲染提示（如 {@code logo}）；无则 {@code null}
     * @param options      下拉选项（仅 SELECT）
     * @param min          整数下界（仅 NUMBER；来自 {@code ConfigRules}，前端据此做即时校验）
     * @param max          整数上界（仅 NUMBER）
     * @param maxLength    文本长度上限（仅 TEXT / PASSWORD）
     * @param layout       配置页网格跨度：SHORT / MEDIUM / FULL（见 {@code SystemConfigCatalog.Layout}）。
     *                     前端只按它算 grid-column，不在前端猜宽度
     */
    public record ItemVO(String key, String label, String type, String unit,
                         String value, String defaultValue, String description,
                         boolean editable, boolean adminOnly, boolean secret, boolean required,
                         String widget, List<OptionVO> options,
                         Integer min, Integer max, Integer maxLength, String layout) {
    }

    /** 下拉选项 */
    public record OptionVO(String value, String label) {
    }
}
