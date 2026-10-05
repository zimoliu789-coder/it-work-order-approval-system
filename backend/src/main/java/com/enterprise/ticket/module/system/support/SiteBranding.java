package com.enterprise.ticket.module.system.support;

import java.util.Set;

/**
 * 站点品牌（系统名称 / logo / 版权文字）的键名、默认值与取值解析 —— 、。
 *
 * <h2>版权文字为什么和名称 / logo 放在同一个类</h2>
 * <p>三者都是「站点外观」这一件事的不同侧面，且共用同一条权限口径
 * （见 {@link #ADMIN_ONLY_KEYS}）；放进第二个类只会让「哪些键仅内置超管可改」
 * 这件事分散到两处去查。
 *
 * <h2>为什么把「logo 的两种形态」编码进值本身</h2>
 * <p>logo 既可以是文字图标（默认 {@code IT}），也可以是上传的图片。若把「图片」表示成
 * 「值恰好是一个文件名」，读取侧就无法区分它与「一个名叫 xxx.png 的文字 logo」。
 * 因此约定：值以 {@link #LOGO_FILE_PREFIX} 开头 = 图片，其余 = 文字。
 * 一条规则、两边同源，运维在库里也能一眼看出当前形态。
 *
 * <h2>这些参数为什么不能靠「未登记键」放行</h2>
 * <p>{@code ConfigRules} 对未登记键不做值域校验（原样保存）。看起来「不登记也能用」，
 * 但系统名称会被写进 {@code <title>} 与侧边栏，若允许任意长度的值，
 * 一个几万字的名称会把界面撑坏。因此本类登记到 {@code ConfigRules}，
 * 由统一的 500 字上限兜底。
 *
 * <h2>谁可以改</h2>
 * <p>{@link #ADMIN_ONLY_KEYS} 里的键<b>只有内置超级管理员</b>可改
 * （见 {@code SystemConfigServiceImpl#updateValues}）—— 其他超管与 admin 只读置灰。
 * 这条规则刻意放在服务层按「当前登录者」判定，而不是把 {@code editable} 置 0：
 * 后者会连内置超管一起挡掉。
 */
public final class SiteBranding {

    private SiteBranding() {
    }

    /** 系统名称配置键 */
    public static final String KEY_SITE_NAME = "system.site-name";

    /** 系统 logo 配置键 */
    public static final String KEY_SITE_LOGO = "system.site-logo";

    /** 版权文字配置键（：登录页与侧边栏底部的一行小字） */
    public static final String KEY_COPYRIGHT = "system.copyright";

    /** 系统名称默认值（配置缺失 / 留空时的回落值） */
    public static final String DEFAULT_SITE_NAME = "设备借用工单系统";

    /** 默认文字 logo */
    public static final String DEFAULT_LOGO_TEXT = "IT";

    /**
     * 版权文字的默认值：<b>空串</b>。
     *
     * <p>说明是「可空，填了就显示，不填就不显示」。因此这里刻意<b>不</b>预置
     * 「© 2026 XX公司 版权所有」这类占位文案 —— 预置了就等于替所有部署单位
     * 默认挂上一行他们没写过的版权声明，而管理员在参数页看到它「已经有值」时，
     * 多半不会去改。留空 + 前端不渲染，是唯一不会替用户乱签名的默认。
     */
    public static final String DEFAULT_COPYRIGHT = "";

    /** logo 取值为「已上传图片」时的前缀，后接落盘文件名 */
    public static final String LOGO_FILE_PREFIX = "FILE:";

    /**
     * 参数页分组编码（ 重组后为「基础设置」）。
     *
     * <p> 把原「站点品牌」「文件存储」「附件与导出」三张卡并成一张
     * 「基础设置」，本常量随之由 {@code site} 改为 {@code basic}。
     * 注意这<b>只影响参数页的卡片编码</b>，与 {@code system_config.config_group}
     * 列无关（那一列只被旧扁平接口用于排序，重组时刻意没有动它，
     * 免得连带改掉既有回归脚本的数据断言）。
     */
    public static final String GROUP_BASIC = "basic";

    /**
     * 仅内置超级管理员可修改的配置键。
     *
     * <p>用 {@link Set#of} 而非 {@code List.of}：判定是「是否包含」，
     * 列表在参数页保存时可能被逐个比对几十次，集合更贴合语义。
     *
     * <p>版权文字为什么也在这一组：它和系统名称、图标同属「站点外观」，
     * 出现在登录页与每一个页面的侧边栏底部。若把它留成普通超管可改，
     * 同一张「站点品牌」卡片里就会出现「名称只读、版权可改」的错位规则 ——
     * 维护人员无法从界面上理解为什么相邻两项的权限不同。
     * 权限口径按<b>卡片</b>划一，比按「这一项看起来危不危险」逐项判断更不容易出错。
     */
    public static final Set<String> ADMIN_ONLY_KEYS = Set.of(KEY_SITE_NAME, KEY_SITE_LOGO, KEY_COPYRIGHT);

    /** 是否为「仅内置超管可改」的键 */
    public static boolean isAdminOnlyKey(String key) {
        return key != null && ADMIN_ONLY_KEYS.contains(key.trim());
    }

    /** logo 取值是否指向已上传的图片 */
    public static boolean isFileLogo(String value) {
        return value != null && value.startsWith(LOGO_FILE_PREFIX);
    }

    /**
     * 由 logo 配置值解析出落盘文件名；文字 logo 或空值返回 {@code null}。
     *
     * <p>只做前缀剥离，不做文件名合法性校验 —— 落盘名由服务端生成（UUID + 扩展名），
     * 真正读盘时仍会经过 {@code RelativeFileStorage.resolve} 的「不得越出存储根」断言。
     */
    public static String logoFileName(String value) {
        if (!isFileLogo(value)) {
            return null;
        }
        String fileName = value.substring(LOGO_FILE_PREFIX.length()).trim();
        return fileName.isEmpty() ? null : fileName;
    }

    /** 把落盘文件名包装成可入库的 logo 取值 */
    public static String fileLogoValue(String fileName) {
        return LOGO_FILE_PREFIX + fileName;
    }
}
