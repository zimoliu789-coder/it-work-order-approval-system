package com.enterprise.ticket.module.auth.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 绑定 / 修改自己的联系方式（ / 四.3）。
 *
 * <p>两个字段都可选，但<b>至少填一个</b>，且「传空 = 保持原值」。
 * 「至少一个」这条无法用 Bean Validation 的字段级注解表达（它是跨字段约束），
 * 因此由服务层判定并抛 {@code CONTACT_REQUIRED}。
 *
 * <p>刻意不含 {@code userId}：越权无从表达（见 {@code ProfileController} 的说明）。
 */
@Data
public class BindContactRequest {

    @Size(max = 20, message = "手机号不能超过 20 个字符")
    private String phone;

    @Size(max = 128, message = "邮箱不能超过 128 个字符")
    private String email;

    /**
     * 手机渠道验证码（，）。
     *
     * <p><b>仅当 {@code phone} 相对库中值发生变化时必填</b>：把已绑的手机号原样回传
     * （表单常见行为）不应被要求再验证一次。缺失或错误一律按
     * {@code VERIFY_CODE_INVALID} 拒绝。
     */
    @Size(max = 12, message = "验证码长度不合法")
    private String phoneCode;

    /** 邮箱渠道验证码；语义同 {@link #phoneCode} */
    @Size(max = 12, message = "验证码长度不合法")
    private String emailCode;
}
