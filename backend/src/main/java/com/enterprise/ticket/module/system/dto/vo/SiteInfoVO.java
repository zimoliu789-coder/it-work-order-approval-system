package com.enterprise.ticket.module.system.dto.vo;

import lombok.Data;

/**
 * 站点品牌信息（、）—— 供前端渲染侧边栏、登录页与浏览器标题。
 *
 * <h2>为什么把「文字 / 图片」拆成两个字段而不是回传一个原始串</h2>
 * <p>库里的取值可能是 {@code IT}，也可能是 {@code FILE:2026/09/xxx.png}。
 * 若原样下发，每个渲染点（侧边栏、登录页、标签页）都要各自实现一遍
 * 「判断前缀 → 决定渲染 img 还是文字」的逻辑 —— 三处实现必然出现一处漏判，
 * 表现为「某个页面 logo 显示成一串 FILE: 开头的乱码」。
 * 因此由服务端一次判定，前端只按 {@code logoType} 分支。
 *
 * <h2>版权文字为什么也走这个免认证接口（）</h2>
 * <p>它要渲染在<b>登录页底部</b>，而那一刻用户还没有会话；而且它和系统名称一样，
 * 是「改完要求全站立即生效」的外观信息。挂在同一个接口上，前端只需一次的
 * {@code siteStore.load()} 就能把名称 / 图标 / 版权一起刷新 ——
 * 单独开一个接口会让「改了名称生效了、改了版权没生效」这种半边生效的状态成为可能。
 * 版权<b>允许为空串</b>（留空 = 不显示），前端按空串判断是否渲染，服务端不做回落。
 */
@Data
public class SiteInfoVO {

    /** 系统名称（永不为空，服务端已回落默认值） */
    private String siteName;

    /**
     * 版权文字；<b>可能为空串</b>。
     *
     * <p>空串表示「管理员没有填写」→ 前端不渲染页脚那一行。刻意不给它默认文案：
     * 见 {@code SiteBranding.DEFAULT_COPYRIGHT} 的注释。
     */
    private String copyright;

    /** logo 形态：{@code TEXT} 文字图标 / {@code IMAGE} 上传的图片 */
    private String logoType;

    /** 文字 logo 内容（{@code logoType=TEXT} 时有值） */
    private String logoText;

    /** 图片 logo 的访问地址（{@code logoType=IMAGE} 时有值；相对地址，前端直接用作 img src） */
    private String logoUrl;

    public static SiteInfoVO of(String siteName, String copyright, String logoType,
                                String logoText, String logoUrl) {
        SiteInfoVO vo = new SiteInfoVO();
        vo.setSiteName(siteName);
        vo.setCopyright(copyright);
        vo.setLogoType(logoType);
        vo.setLogoText(logoText);
        vo.setLogoUrl(logoUrl);
        return vo;
    }
}
