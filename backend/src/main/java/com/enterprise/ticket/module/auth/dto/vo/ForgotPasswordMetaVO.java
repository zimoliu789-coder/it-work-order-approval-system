package com.enterprise.ticket.module.auth.dto.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

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
}
