package com.enterprise.ticket.module.system.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 「发送测试邮件」请求（）。
 *
 * <h2>为什么允许带上表单值，而不是只用已保存的配置</h2>
 * <p>管理员的真实操作顺序是「填完 → 想先试一下 → 没问题再保存」。
 * 如果测试只能用已保存的值，他就被迫「先保存错的配置、再测试、再改回来」——
 * 而保存之后系统会立刻按这份配置去发验证码，等于把用户当成小白鼠。
 * 因此测试接口接受<b>未保存的表单值</b>作为覆盖项：测的就是屏幕上那套参数。
 *
 * <h2>为什么每个覆盖项都可空</h2>
 * <p>只改端口试一次是很常见的动作，此时其他字段应当沿用已保存值。
 * 「可空 = 沿用」比「必须提交完整表单」更贴近真实使用方式，
 * 也让前端不必为了测一个端口去凑齐整张表单。
 */
@Data
public class ConfigTestMailRequest {

    /** 收件人（必填）：测试邮件必须真的发到某个能收信的地址 */
    @NotBlank(message = "请填写接收测试邮件的邮箱地址")
    @Email(message = "收件邮箱格式不正确")
    private String to;

    /** SMTP 服务器地址；空 = 沿用已保存值 */
    private String host;

    /**
     * SMTP 端口；null = 沿用已保存值。
     *
     * <p>此处就带上 1 ~ 65535 的即时校验：让「端口填错」在参数绑定阶段就被拒，
     * 而不是走到建连超时之后再报一个模糊的错误。
     */
    @Min(value = 1, message = "端口必须在 1 ~ 65535 之间")
    @Max(value = 65535, message = "端口必须在 1 ~ 65535 之间")
    private Integer port;

    /** 发件邮箱账号；空 = 沿用已保存值 */
    private String username;

    /**
     * SMTP 授权码明文；空 或 {@code ****} = 沿用已保存的授权码。
     *
     * <p>「等于掩码即沿用」这一条不能省：页面回显的授权码就是掩码，
     * 若把它当成新授权码去认证，测试必然失败，而管理员会以为是自己填错了。
     */
    private String password;

    /** 发件人显示名；空 = 沿用已保存值 */
    private String fromName;

    /** 是否启用 SSL；null = 沿用已保存值 */
    private Boolean ssl;
}
