package com.enterprise.ticket.module.auth.dto.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 找回密码功能对外的「元信息」—— 供登录页决定是否显示入口（ / 六.3）。
 *
 * <p>这是<b>免鉴权</b>接口的返回值，因此刻意只包含「功能是否可用」这类
 * 不依赖任何具体账号的信息：不含任何号码、邮箱、账号是否存在的结果。
 */
@Data
public class ForgotPasswordMetaVO {

    /**
     * 找回密码整体是否可用。
     *
     * <p>为 {@code false} 时前端<b>隐藏</b>登录页的「无法登录？」入口
     * （：两个验证开关都关了的情况下链接直接隐藏）——
     * 让用户看到一个点进去必然失败的入口，比不显示它更糟糕。
     */
    private boolean enabled;

    /** 当前启用的渠道编码集合（可能为空 = 都不启用） */
    private List<String> channels = new ArrayList<>();

    /** 验证码长度（位），供输入框 maxlength 使用 */
    private int codeLength;

    /**
     * 不可用渠道 → 原因（键为渠道编码 {@code SMS} / {@code EMAIL}，值为服务端生成的中文原因）。
     *
     * <p>只登记<b>不可用</b>的渠道，可用渠道不出现在本表里。
     * 三种成因（管理员关闭 / 短信网关未接入 / SMTP 未配置完成）的文案由服务端生成，
     * 前端只负责展示 —— 这样后端将来新增第四种成因时界面会自动说对话，
     * 而不是继续显示一句已经过时的旧文案。
     */
    private Map<String, String> channelDisabledReasons = new LinkedHashMap<>();
}
