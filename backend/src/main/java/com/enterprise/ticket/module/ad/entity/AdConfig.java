package com.enterprise.ticket.module.ad.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AD 域控配置（；）—— {@code ad_config} 表的全局单行实体
 *
 * <p><b>本表有且只有一行</b>（由 {@code uk_ad_config_singleton} 唯一索引保证）。
 * 之所以不把 AD 配置拆成 {@code system_config} 的键值对，见 V15 迁移脚本的说明：
 * 这组字段必须<b>整体自洽</b>（端口 / SSL / 证书策略），键值化以后就没法做整体校验，
 * 也没法把绑定密码限制在密文列里。
 *
 * <p><b>绑定密码</b>存的是 {@link com.enterprise.ticket.security.SecretCipher} 产出的密文，
 * 字段名刻意带 {@code Cipher} 后缀，让任何读到这段代码的人立刻知道
 * 「这里拿到的不是明文，要解密请走 SecretCipher」。
 */
@Data
@TableName("ad_config")
public class AdConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 单行约束位：恒为 1（业务代码不需要读写它） */
    private Integer singletonKey;

    /** 是否启用 AD 认证：关闭时全部账号走本地认证 */
    private Boolean enabled;

    /** AD 服务器列表（逗号分隔；仅主机名/IP，也可写完整 ldap(s):// 地址） */
    private String serverUrls;

    /** 端口：LDAP 默认 389，LDAPS 默认 636 */
    private Integer serverPort;

    /** 是否使用 LDAPS */
    private Boolean useSsl;

    /** 证书严格校验；false 表示跳过（有中间人风险，仅限内网自建 CA 场景） */
    private Boolean strictCert;

    private String baseDn;

    private String bindDn;

    /** 绑定密码密文（{@code ENC1:...}）；明文绝不落库 */
    private String bindPasswordCipher;

    /** 用户搜索过滤器，{@code {0}} 为登录名占位符 */
    private String userFilter;

    private String attrLogin;

    private String attrName;

    private String attrEmail;

    /** 手机号属性映射；为空时不同步手机号 */
    private String attrPhone;

    private String attrDept;

    private String attrStatus;

    /** AD 新用户自动分配的角色编码 */
    private String defaultRole;

    /** 连接与读取超时（秒） */
    private Integer connectTimeoutSeconds;

    /** 是否启用每日自动同步；关闭时仅支持手动「立即同步」 */
    private Boolean syncEnabled;

    /** 每日自动同步时刻（0-23 时，服务器本地时间）；默认 2 即凌晨 2 点 */
    private Integer syncHour;

    private LocalDateTime lastTestAt;

    private String lastTestResult;

    private LocalDateTime lastSyncAt;

    private String lastSyncResult;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
