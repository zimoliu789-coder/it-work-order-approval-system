package com.enterprise.ticket.module.auth.dto.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 找回密码 · 可用渠道查询结果（第一步）。
 *
 * <p>只回传<b>打码后</b>的联系方式（138****8888 / a***@qq.com）：这一步是免鉴权的，
 * 任何知道某人姓名的人都能调用它。回显完整号码等于把「姓名 → 手机号」这条
 * 对应关系公开出去，而这正是短信钓鱼最需要的前置信息。
 * 打码后的值足够用户确认「发的是我那个号」，又不构成泄露。
 */
@Data
public class ForgotPasswordChannelsVO {

    /**
     * 可用于接收验证码的渠道编码集合，取值 {@code SMS} / {@code EMAIL}。
     *
     * <p>已经<b>同时</b>过滤掉两类不可用的情况：
     * ① 该渠道的系统开关被管理员关闭（）；
     * ② 该账号没有绑定对应的联系方式。
     * 因此前端拿到什么就展示什么，不必再做二次判断 —— 判断逻辑只存在于服务端一处。
     */
    private List<String> channels = new ArrayList<>();

    /** 打码后的手机号；未绑定或手机渠道未启用时为 {@code null} */
    private String maskedPhone;

    /** 打码后的邮箱；未绑定或邮箱渠道未启用时为 {@code null} */
    private String maskedEmail;

    /**
     * 账号的展示名（姓名），供界面回显「正在为「张伟」重置密码」。
     *
     * <p>这一步能走到，说明账号已经真实存在（不存在会在第一步直接报错），
     * 因此回显姓名不额外泄露任何东西，但能显著降低「输错了一位数、
     * 给同事的账号重置了密码」这类误操作。
     */
    private String displayName;

    /** 验证码长度（位），供输入框的 maxlength 与提示文案使用（） */
    private int codeLength;

    /** 验证码有效期（分钟），供提示文案使用（） */
    private int codeExpireMinutes;
}
