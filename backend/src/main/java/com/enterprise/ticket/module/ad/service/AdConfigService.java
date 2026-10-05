package com.enterprise.ticket.module.ad.service;

import com.enterprise.ticket.module.ad.dto.AdConfigRequest;
import com.enterprise.ticket.module.ad.dto.AdConfigVO;
import com.enterprise.ticket.module.ad.dto.AdDnPreviewRequest;
import com.enterprise.ticket.module.ad.dto.AdDnPreviewVO;
import com.enterprise.ticket.module.ad.dto.AdTestResultVO;
import com.enterprise.ticket.module.ad.entity.AdConfig;
import com.enterprise.ticket.module.ad.ldap.AdConnection;

/**
 * AD 域控配置服务（；）
 *
 * <p>职责边界：只负责「配置的读写、校验、脱敏呈现、组装成连接参数」，
 * <b>不</b>负责登录分支（{@code AdAuthenticationService}）与用户同步（{@code AdUserSyncService}）。
 */
public interface AdConfigService {

    /** 读取配置实体（恒返回非 null：缺行时按默认值补建） */
    AdConfig current();

    /** 配置视图（绑定密码脱敏为 {@code ****}） */
    AdConfigVO view();

    /**
     * 保存配置
     *
     * <p>绑定密码：{@code bindPassword} 留空表示保持原值；填写则加密后覆盖。
     * 校验为「格式总是校验、完整性仅在启用时校验」（见 {@code AdConfigValidator}）。
     */
    void save(AdConfigRequest request);

    /** 是否已启用 AD 认证 */
    boolean isEnabled();

    /** AD 新用户默认角色编码（未配置时回退 {@code user}） */
    String defaultRole();

    /**
     * 是否启用「每日自动同步」
     *
     * <p>该开关原先放在 {@code system_config.ad_sync_enabled}，本次搬进 {@code ad_config.sync_enabled}：
     * 它与连接参数是同一件事的两半 —— 「连不上域控时该不该继续按点同步」这个判断，
     * 拆成两张表后在库层面无法表达。
     */
    boolean syncEnabled();

    /** 每日自动同步时刻（0-23 时；越界值钳到区间内，见 {@code AdConfigValidator#normalizeSyncHour}） */
    int syncHour();

    /**
     * 用「当前已保存的配置」组装连接参数
     *
     * @throws com.enterprise.ticket.common.exception.BusinessException
     *         {@code AD_DISABLED} 未启用 / {@code AD_CONFIG_INCOMPLETE} 缺必填项或密码不可解
     */
    AdConnection activeConnection();

    /**
     * 用「本次提交的配置」组装连接参数（测试连接用）
     *
     * <p>与 {@link #activeConnection()} 的区别：这里的密码优先取请求里的，
     * 请求里没填才回退已存密文 —— 这样管理员在保存之前就能用新密码测通。
     */
    AdConnection connectionOf(AdConfigRequest request);

    /** 测试连接（连通性 + 绑定认证 + 基础 DN 下探测一条） */
    AdTestResultVO testConnection(AdConfigRequest request);

    /**
     * 预览「基础 DN / 绑定 DN 自动推导」的结果
     *
     * <p>纯计算、无副作用：与 {@link #save(AdConfigRequest)} 共用同一份推导规则，
     * 因此界面上的预览值与落库值必然一致。
     */
    AdDnPreviewVO previewDns(AdDnPreviewRequest request);

    /** 记录一次测试结果（供配置页展示「上次测试」） */
    void markTestResult(String summary);

    /** 记录一次同步结果（供配置页展示「上次同步」） */
    void markSyncResult(String summary);
}
