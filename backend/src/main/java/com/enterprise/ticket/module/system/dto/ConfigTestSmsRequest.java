package com.enterprise.ticket.module.system.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 「发送测试短信」请求（ · ）。
 *
 * <h2>与 {@code ConfigTestMailRequest} 同口径：允许带上未保存的表单值</h2>
 * <p>管理员的真实操作顺序是「填完 → 想先试一下 → 没问题再保存」。
 * 若测试只能用已保存的值，他就被迫「先保存错的配置、再测试、再改回来」——
 * 而保存之后系统会立刻按这份配置去发验证码。因此每个覆盖项都可空，空 = 沿用已保存值。
 *
 * <h2>为什么手机号用 {@code @NotBlank} 而不是 {@code @Pattern}</h2>
 * <p>手机号格式的<b>唯一事实源</b>是 {@code AccountFormats.isPhone}（ 已收敛），
 * 找回密码、绑定联系方式、员工导入三处都调它。在这里再写一份正则，
 * 就多出第二个判定口径 —— 而两者的漂移会表现为
 * 「测试说号码没问题、绑定却报格式不正确」。因此这里只校验「非空」，
 * 格式由服务层调同一个方法判定。
 */
@Data
public class ConfigTestSmsRequest {

    /** 接收测试短信的手机号（必填） */
    @NotBlank(message = "请填写接收测试短信的手机号")
    private String to;

    /** 服务商编码；空 = 沿用已保存值 */
    private String provider;

    /** AccessKey ID；空 = 沿用已保存值 */
    private String accessKeyId;

    /**
     * AccessKey Secret 明文；空 或 {@code ****} = 沿用已保存的密钥。
     *
     * <p>「等于掩码即沿用」这一条不能省：页面回显的就是掩码，
     * 若把它当成新密钥留着，下一次真实发码会拿 {@code ****} 去签名，
     * 而故障点（保存测试）与现象（线上签名失败）相隔极远。
     */
    private String accessKeySecret;

    /** 短信签名；空 = 沿用已保存值 */
    private String signName;

    /** 模板代码；空 = 沿用已保存值 */
    private String templateCode;
}
