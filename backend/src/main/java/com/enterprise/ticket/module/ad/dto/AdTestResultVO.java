package com.enterprise.ticket.module.ad.dto;

import lombok.Data;

/**
 * AD 连接测试结果（「测试连接：成功 / 失败都给明确提示」）
 *
 * <p>「明确提示」的落点是 {@code message}：不能只给一个「连接失败」——
 * 管理员拿着这句话无法判断该改哪里。因此服务层会按失败类型给出指向性文案
 * （主机名解析失败 / 端口不通 / 绑定账号被拒 / 基础 DN 不存在 / 过滤器语法错）。
 *
 * <p>{@code insecure} 用于在测试成功时也提示「证书校验已关闭」——
 * 否则「测试通过」会被误读为「配置没问题」。
 */
@Data
public class AdTestResultVO {

    private boolean ok;

    /** 人类可读的结论（成功或失败都要能指导下一步动作） */
    private String message;

    /** 实际连上的服务器（主备轮询后的结果） */
    private String host;

    /** 探测到的用户数（成功时给出，用于确认「过滤器是不是把所有人都过滤掉了」） */
    private Integer userCount;

    /** 耗时（毫秒） */
    private Long elapsedMs;

    /** 是否处于「跳过证书校验」的宽松模式 */
    private boolean insecure;
}
