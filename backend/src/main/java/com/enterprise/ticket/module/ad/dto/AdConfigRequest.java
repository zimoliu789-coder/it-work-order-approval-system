package com.enterprise.ticket.module.ad.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AD 配置保存请求（；）
 *
 * <p><b>为什么密码字段允许为空</b>：配置页每次保存都会把整张表单提交上来，
 * 而绑定密码在接口返回时是脱敏的（{@code ****}）。若强制必填，
 * 用户只是改一下端口也得把密码重新输一遍 —— 更糟的是，很多人会直接把
 * {@code ****} 粘回去，于是「密码被改成了四个星号」这种故障会周期性发生。
 * 约定：<b>留空 = 保持原密码不变</b>；要清空请显式传 {@code ""} 之外的方式
 * （本系统不支持清空，因为清空等于把 AD 认证弄坏）。
 *
 * <p>校验分「格式」（本 DTO 的注解 + {@code AdConfigValidator}）与「完整性」
 * （启用时才要求填全）两层：允许先存草稿、配好再启用。
 */
@Data
public class AdConfigRequest {

    /** 是否启用 AD 认证 */
    private Boolean enabled;

    /** 服务器地址列表（逗号分隔，主备顺序） */
    @Size(max = 500, message = "服务器地址过长")
    private String serverUrls;

    /** 端口：LDAP 389 / LDAPS 636 */
    @Min(value = 1, message = "端口取值范围为 1-65535")
    @Max(value = 65535, message = "端口取值范围为 1-65535")
    private Integer serverPort;

    /** 是否 LDAPS */
    private Boolean useSsl;

    /** 是否严格校验证书（生产建议 true） */
    private Boolean strictCert;

    /** 基础 DN */
    @Size(max = 250, message = "基础 DN 过长")
    private String baseDn;

    /** 绑定 DN */
    @Size(max = 250, message = "绑定 DN 过长")
    private String bindDn;

    /** 绑定密码；留空表示保持原值不变 */
    @Size(max = 200, message = "绑定密码过长")
    private String bindPassword;

    /** 用户搜索过滤器（须含 {0} 占位符） */
    @Size(max = 500, message = "搜索过滤器过长")
    private String userFilter;

    @Size(max = 60, message = "登录名属性过长")
    private String attrLogin;

    @Size(max = 60, message = "姓名属性过长")
    private String attrName;

    @Size(max = 60, message = "邮箱属性过长")
    private String attrEmail;

    /** 手机号属性映射；留空表示不同步手机号 */
    @Size(max = 60, message = "手机号属性过长")
    private String attrPhone;

    @Size(max = 60, message = "部门属性过长")
    private String attrDept;

    @Size(max = 60, message = "状态属性过长")
    private String attrStatus;

    /** AD 新用户默认角色 */
    @Size(max = 60, message = "默认角色编码过长")
    private String defaultRole;

    /** 连接 / 读取超时（秒） */
    @Min(value = 1, message = "超时时间取值范围为 1-30 秒")
    @Max(value = 30, message = "超时时间取值范围为 1-30 秒")
    private Integer connectTimeoutSeconds;

    /** 是否启用每日自动同步 */
    private Boolean syncEnabled;

    /** 每日自动同步时刻（0-23 时） */
    @Min(value = 0, message = "同步时刻取值范围为 0-23")
    @Max(value = 23, message = "同步时刻取值范围为 0-23")
    private Integer syncHour;

    /** 是否清除已保存的绑定密码（正常保存流程不使用；仅供排障） */
    private Boolean clearBindPassword;
}
