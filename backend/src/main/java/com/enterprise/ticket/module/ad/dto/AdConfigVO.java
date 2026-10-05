package com.enterprise.ticket.module.ad.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AD 配置视图—— 供配置页回填
 *
 * <p><b>绑定密码只回 {@code ****}</b>（「接口返回时脱敏」）：
 * 即使管理员本人有权限看配置，也没有任何理由让明文口令走一次 HTTP ——
 * 它会进浏览器内存、进开发者工具、进可能的访问日志。前端看到 {@code ****}
 * 就知道「已配置」，提交时留空即表示不改。
 *
 * <p>{@code bindPasswordConfigured} 单独给出一个布尔值，因为「密码是 ****」
 * 与「密码根本没配」在界面上必须可区分，而单看掩码字符串分不出来。
 */
@Data
public class AdConfigVO {

    private Boolean enabled;

    private String serverUrls;

    private Integer serverPort;

    private Boolean useSsl;

    private Boolean strictCert;

    private String baseDn;

    private String bindDn;

    /** 恒为 {@code ****}（未配置时为空串） */
    private String bindPassword;

    /** 是否已配置绑定密码（用于表单校验与界面提示） */
    private Boolean bindPasswordConfigured;

    private String userFilter;

    private String attrLogin;

    private String attrName;

    private String attrEmail;

    /** 手机号属性映射；空串表示不同步手机号 */
    private String attrPhone;

    private String attrDept;

    private String attrStatus;

    private String defaultRole;

    private Integer connectTimeoutSeconds;

    /** 是否启用每日自动同步 */
    private Boolean syncEnabled;

    /** 每日自动同步时刻（0-23 时） */
    private Integer syncHour;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastTestAt;

    private String lastTestResult;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastSyncAt;

    private String lastSyncResult;

    /** 证书校验被关闭时的醒目警告（前端直接展示）；严格模式为 null */
    private String securityWarning;
}
