package com.enterprise.ticket.module.ad.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.ad.dto.AdConfigRequest;
import com.enterprise.ticket.module.ad.dto.AdConfigVO;
import com.enterprise.ticket.module.ad.dto.AdDnPreviewRequest;
import com.enterprise.ticket.module.ad.dto.AdDnPreviewVO;
import com.enterprise.ticket.module.ad.dto.AdTestResultVO;
import com.enterprise.ticket.module.ad.entity.AdConfig;
import com.enterprise.ticket.module.ad.ldap.AdConnection;
import com.enterprise.ticket.module.ad.ldap.AdDirectoryClient;
import com.enterprise.ticket.module.ad.ldap.AdDirectoryException;
import com.enterprise.ticket.module.ad.mapper.AdConfigMapper;
import com.enterprise.ticket.module.ad.service.AdConfigService;
import com.enterprise.ticket.module.ad.support.AdConfigValidator;
import com.enterprise.ticket.module.ad.support.AdDnResolver;
import com.enterprise.ticket.module.role.service.RoleService;
import com.enterprise.ticket.security.SecretCipher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * AD 配置服务实现（；同步节奏并入 ）
 *
 * <h2>绑定密码的三条不变式</h2>
 * <ol>
 *   <li><b>明文绝不落库</b>：写入前一律经 {@link SecretCipher} 加密，库里只有 {@code ENC1:...}；</li>
 *   <li><b>明文绝不出接口</b>：{@link #view()} 恒定返回 {@code ****}，
 *       另用 {@code bindPasswordConfigured} 表达「有没有配」；</li>
 *   <li><b>明文绝不进日志</b>：本类所有日志只打主机、端口、DN 等非敏感信息。</li>
 * </ol>
 *
 * <h2>为什么把「测试连接」放在这里而不是独立服务</h2>
 * <p>它需要「用未保存的配置去连一次」，即必须复用本类的配置组装逻辑
 * （{@link #connectionOf(AdConfigRequest)}）。拆出去只会导致密码回退规则
 * 出现两份实现，进而产生「保存后能连、测试时连不上」这类分歧。
 *
 * <h2>为什么「每日同步开关 / 时刻」也归本类</h2>
 * <p> 把这两个值从 {@code system_config} 搬进 {@code ad_config}：
 * 它们与连接参数是同一件事的两半（连不上域控时该不该继续按点同步，
 * 取决于连接配置本身），且要求「一个页面搞定 AD」。
 *
 * <h2>⚠️ 保存为什么用 UpdateWrapper 而不是 {@code updateById(entity)}</h2>
 * <p>全局配置是 {@code update-strategy: not_null}，实体里的 null 字段**不会进 SET 子句**。
 * 而本模块有四个字段必须能被显式清空（{@code server_urls} / {@code base_dn} /
 * {@code bind_dn} / {@code attr_phone}，加上可置 NULL 的 {@code bind_password_cipher}），
 * 用实体更新会让「留空 = 清空」静默失效 —— 清不掉旧值，且界面上完全看不出来。
 * 同时这些文本列在 DDL 里是 {@code NOT NULL DEFAULT ''}，所以清空的落库值是<b>空串</b>而非 NULL。
 * 详见 {@link #save(AdConfigRequest)} 内的注释。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdConfigServiceImpl implements AdConfigService {

    /** 测试连接时探测样本的条数：够判断「过滤器是否把所有人过滤掉了」即可 */
    private static final int PROBE_SAMPLE_SIZE = 50;

    private final AdConfigMapper adConfigMapper;
    private final AdDirectoryClient directoryClient;
    private final SecretCipher secretCipher;
    private final RoleService roleService;

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    @Override
    public AdConfig current() {
        AdConfig config = adConfigMapper.selectOne(Wrappers.<AdConfig>lambdaQuery()
                .orderByAsc(AdConfig::getId)
                .last("LIMIT 1"));
        if (config == null) {
            // 正常路径下 V15 已播种唯一一行；此处兜底是为了「迁移被人工跳过」的环境
            // 也能打开配置页，而不是直接 500
            AdConfig created = new AdConfig();
            created.setSingletonKey(1);
            created.setEnabled(false);
            created.setServerPort(AdConfigValidator.DEFAULT_PORT);
            created.setUseSsl(false);
            created.setStrictCert(true);
            created.setUserFilter(AdConnection.DEFAULT_FILTER);
            created.setAttrLogin("sAMAccountName");
            created.setAttrName("displayName");
            created.setAttrEmail("mail");
            created.setAttrPhone("telephoneNumber");
            created.setAttrDept("department");
            created.setAttrStatus("userAccountControl");
            created.setDefaultRole("user");
            created.setConnectTimeoutSeconds(AdConfigValidator.DEFAULT_TIMEOUT_SECONDS);
            created.setSyncEnabled(false);
            created.setSyncHour(AdConfigValidator.DEFAULT_SYNC_HOUR);
            adConfigMapper.insert(created);
            log.warn("ad_config 无数据行，已按默认值补建（请到 AD 配置页补全后启用）");
            return created;
        }
        return config;
    }

    @Override
    public AdConfigVO view() {
        AdConfig config = current();
        AdConfigVO vo = new AdConfigVO();
        vo.setEnabled(Boolean.TRUE.equals(config.getEnabled()));
        vo.setServerUrls(config.getServerUrls());
        vo.setServerPort(config.getServerPort());
        vo.setUseSsl(Boolean.TRUE.equals(config.getUseSsl()));
        vo.setStrictCert(!Boolean.FALSE.equals(config.getStrictCert()));
        vo.setBaseDn(config.getBaseDn());
        vo.setBindDn(config.getBindDn());

        boolean configured = StringUtils.hasText(config.getBindPasswordCipher());
        // ：接口返回时脱敏，只显示 ****
        vo.setBindPassword(configured ? SecretCipher.MASK : "");
        vo.setBindPasswordConfigured(configured);

        vo.setUserFilter(config.getUserFilter());
        vo.setAttrLogin(config.getAttrLogin());
        vo.setAttrName(config.getAttrName());
        vo.setAttrEmail(config.getAttrEmail());
        vo.setAttrPhone(config.getAttrPhone());
        vo.setAttrDept(config.getAttrDept());
        vo.setAttrStatus(config.getAttrStatus());
        vo.setDefaultRole(config.getDefaultRole());
        vo.setConnectTimeoutSeconds(config.getConnectTimeoutSeconds());
        vo.setSyncEnabled(Boolean.TRUE.equals(config.getSyncEnabled()));
        vo.setSyncHour(config.getSyncHour() == null
                ? AdConfigValidator.DEFAULT_SYNC_HOUR : config.getSyncHour());
        vo.setLastTestAt(config.getLastTestAt());
        vo.setLastTestResult(config.getLastTestResult());
        vo.setLastSyncAt(config.getLastSyncAt());
        vo.setLastSyncResult(config.getLastSyncResult());
        vo.setSecurityWarning(buildSecurityWarning(config));
        return vo;
    }

    /**
     * 证书校验被关闭时的警告文案。
     *
     * <p>刻意放在接口返回里而不是只写日志：受影响的是「谁来承担风险」——
     * 只有管理员本人看到这句话，才可能把内网 CA 导入 JVM truststore 并改回严格校验。
     */
    private String buildSecurityWarning(AdConfig config) {
        if (!Boolean.TRUE.equals(config.getUseSsl()) || !Boolean.FALSE.equals(config.getStrictCert())) {
            return null;
        }
        return "当前已关闭证书校验：LDAPS 不会验证服务端证书与主机名，存在中间人攻击风险。"
                + "建议把内网 CA 证书导入 JVM truststore 后改回「严格校验」。";
    }

    // ------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(AdConfigRequest request) {
        AdConfig config = current();
        boolean hasStored = StringUtils.hasText(config.getBindPasswordCipher());
        boolean clearing = Boolean.TRUE.equals(request.getClearBindPassword());
        boolean enable = Boolean.TRUE.equals(request.getEnabled());

        // 「基础 DN / 绑定 DN 自动推导」必须发生在校验之前 ——
        // 校验的是**将要落库的值**，而不是维护人员随手填的原始写法
        resolveDns(request);

        // 完整性仅在「启用」时强制：允许先存草稿，否则管理员拿不到域控信息时无法暂存
        AdConfigValidator.validate(request, enable, hasStored, clearing);

        boolean ssl = Boolean.TRUE.equals(request.getUseSsl());
        int port = AdConfigValidator.normalizePort(request.getServerPort(), ssl);
        boolean strictCert = !Boolean.FALSE.equals(request.getStrictCert());
        // 这几个字段的「空」是**语义值**而不是「没填」：
        // serverUrls = 还没配域控；baseDn = 交给系统从域地址推导；bindDn = 未指定；
        // attrPhone = 不同步手机号。落库表示一律用**空串**（见下方注释）。
        String serverUrls = trimToEmpty(request.getServerUrls());
        String baseDn = trimToEmpty(request.getBaseDn());
        String bindDn = trimToEmpty(request.getBindDn());
        String attrPhone = trimToEmpty(request.getAttrPhone());
        boolean syncEnabled = Boolean.TRUE.equals(request.getSyncEnabled());
        int syncHour = AdConfigValidator.normalizeSyncHour(request.getSyncHour());

        String defaultRole = defaultIfBlank(request.getDefaultRole(), "user");
        if (enable && !roleService.isAssignable(defaultRole)) {
            // 默认角色必须真实可分配：否则 AD 首批用户自动建号时会撞
            // 「角色不存在或已停用」，而这条错误发生在别人登录的时候，排查成本极高
            throw new BusinessException(ErrorCode.AD_CONFIG_INCOMPLETE,
                    "默认角色「" + defaultRole + "」不存在或已停用，请先在「角色与权限」中确认");
        }

        // ⚠️ 这里刻意用「显式 UpdateWrapper + 逐列 set」而不是 updateById(entity)：
        // 全局配置 update-strategy=not_null 会让实体里的 null 字段**不进 SET 子句**，
        // 于是「留空 = 清空」这条约定会在服务端静默失效，而失效方向恰好都很难发现：
        //   · 基础 DN 清不掉 —— 管理员把「高级选项 → 自定义基础 DN」清空、想改回自动推导时，
        //     库里仍是旧的自定义值，而界面预览显示的是推导值：**显示与落库当场分家**；
        //   · 手机号属性清不掉 —— 「不同步手机号」永远做不到，域手机号会继续覆盖本地值，
        //     而那是找回密码的唯一渠道。
        //
        // 另有一条与之配套的**类型约束**：{@code server_urls / base_dn / bind_dn / attr_*}
        // 在 DDL 里全是 {@code NOT NULL DEFAULT ''} ——「没有值」的既有表示就是空串，
        // 写成 NULL 会被数据库直接拒掉（`Column 'base_dn' cannot be null`）。
        // 全表唯一允许 NULL 的是 {@code bind_password_cipher}，它在那里才真正表示「未配置」。
        // 因此：文本列一律落空串，密码列用 NULL。
        // 逐列 set 的另一个好处正是让这条差异在代码里一眼可见。
        LambdaUpdateWrapper<AdConfig> update = Wrappers.<AdConfig>lambdaUpdate()
                .eq(AdConfig::getId, config.getId())
                .set(AdConfig::getEnabled, enable)
                .set(AdConfig::getServerUrls, serverUrls)
                .set(AdConfig::getUseSsl, ssl)
                .set(AdConfig::getServerPort, port)
                .set(AdConfig::getStrictCert, strictCert)
                .set(AdConfig::getBaseDn, baseDn)
                .set(AdConfig::getBindDn, bindDn)
                .set(AdConfig::getUserFilter,
                        defaultIfBlank(request.getUserFilter(), AdConnection.DEFAULT_FILTER))
                .set(AdConfig::getAttrLogin, defaultIfBlank(request.getAttrLogin(), "sAMAccountName"))
                .set(AdConfig::getAttrName, defaultIfBlank(request.getAttrName(), "displayName"))
                .set(AdConfig::getAttrEmail, defaultIfBlank(request.getAttrEmail(), "mail"))
                // 手机号属性刻意允许留空：留空 = 不同步手机号（现场可能不希望 AD 的手机号覆盖本地值）
                .set(AdConfig::getAttrPhone, attrPhone)
                .set(AdConfig::getAttrDept, defaultIfBlank(request.getAttrDept(), "department"))
                .set(AdConfig::getAttrStatus, defaultIfBlank(request.getAttrStatus(), "userAccountControl"))
                .set(AdConfig::getDefaultRole, defaultRole)
                .set(AdConfig::getConnectTimeoutSeconds,
                        AdConfigValidator.normalizeTimeout(request.getConnectTimeoutSeconds()))
                .set(AdConfig::getSyncEnabled, syncEnabled)
                .set(AdConfig::getSyncHour, syncHour);

        // 绑定密码：只有「显式清空」或「确实填了新密码」才进 SET；
        // 留空时**整列不进 SET**，原密文保持不动（否则「只改端口」会把密码一并抹掉）
        if (clearing) {
            update.set(AdConfig::getBindPasswordCipher, null);
        } else if (StringUtils.hasText(request.getBindPassword())) {
            // 前端脱敏回显是 ****：若用户没改，提交上来的就是空串，不会走到这里；
            // 万一真把 **** 提交上来（例如手工构造请求），这里也会把它当新密码加密 ——
            // 因此额外把掩码显式拒绝掉，避免「绑定密码变成了四个星号」这种最难排查的故障
            if (SecretCipher.MASK.equals(request.getBindPassword().trim())) {
                log.warn("检测到提交内容为脱敏占位符，已忽略该「新密码」，保持原密码不变");
            } else {
                update.set(AdConfig::getBindPasswordCipher,
                        secretCipher.encrypt(request.getBindPassword().trim()));
            }
        }

        adConfigMapper.update(null, update);
        log.info("AD 配置已保存：enabled={}，servers={}，{}://{}，baseDn={}，证书严格校验={}，每日同步={} 时",
                enable, serverUrls, ssl ? "ldaps" : "ldap", port, baseDn, strictCert,
                syncEnabled ? syncHour : "关闭");
    }

    /**
     * 把「基础 DN / 绑定 DN」归一化后<b>写回请求对象</b>。
     *
     * <p>写回是刻意的：随后的校验与落库读的都是同一份值，
     * 因此「测试连接用的配置」与「保存下来的配置」必然一致 ——
     * 这正是 那条闸门（必须先测试通过才能保存）成立的前提。
     */
    private void resolveDns(AdConfigRequest request) {
        AdDnResolver.ResolvedDns resolved = AdDnResolver.resolve(
                request.getServerUrls(), request.getBaseDn(), request.getBindDn());
        request.setBaseDn(resolved.baseDn());
        request.setBindDn(resolved.bindDn());
    }

    // ------------------------------------------------------------------
    // 连接组装
    // ------------------------------------------------------------------

    @Override
    public boolean isEnabled() {
        return Boolean.TRUE.equals(current().getEnabled());
    }

    @Override
    public String defaultRole() {
        return defaultIfBlank(current().getDefaultRole(), "user");
    }

    @Override
    public boolean syncEnabled() {
        return Boolean.TRUE.equals(current().getSyncEnabled());
    }

    @Override
    public int syncHour() {
        return AdConfigValidator.normalizeSyncHour(current().getSyncHour());
    }

    @Override
    public AdConnection activeConnection() {
        AdConfig config = current();
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            throw new BusinessException(ErrorCode.AD_DISABLED);
        }
        String password = secretCipher.decrypt(config.getBindPasswordCipher());
        if (password == null) {
            // 解密失败几乎只有一个原因：JWT_SECRET 被更换 → 旧密文不可解
            throw new BusinessException(ErrorCode.AD_CONFIG_INCOMPLETE,
                    "绑定密码无法解密（通常是 JWT_SECRET 已变更），请到 AD 配置页重新填写绑定密码");
        }
        return build(config, password);
    }

    @Override
    public AdConnection connectionOf(AdConfigRequest request) {
        // 幂等：已是 DN 的值再进一次不会被改写（见 AdDnResolver#toBindDn）
        resolveDns(request);
        AdConfig config = current();
        String password = StringUtils.hasText(request.getBindPassword())
                ? request.getBindPassword().trim()
                : secretCipher.decrypt(config.getBindPasswordCipher());
        if (password == null) {
            throw new BusinessException(ErrorCode.AD_CONFIG_INCOMPLETE,
                    "绑定密码无法解密（通常是 JWT_SECRET 已变更），请重新填写绑定密码后再测试");
        }
        AdConfig merged = new AdConfig();
        merged.setServerUrls(request.getServerUrls());
        boolean ssl = Boolean.TRUE.equals(request.getUseSsl());
        merged.setUseSsl(ssl);
        merged.setServerPort(AdConfigValidator.normalizePort(request.getServerPort(), ssl));
        merged.setStrictCert(!Boolean.FALSE.equals(request.getStrictCert()));
        merged.setBaseDn(request.getBaseDn());
        merged.setBindDn(request.getBindDn());
        merged.setUserFilter(defaultIfBlank(request.getUserFilter(), AdConnection.DEFAULT_FILTER));
        merged.setAttrLogin(defaultIfBlank(request.getAttrLogin(), "sAMAccountName"));
        merged.setAttrName(defaultIfBlank(request.getAttrName(), "displayName"));
        merged.setAttrEmail(defaultIfBlank(request.getAttrEmail(), "mail"));
        merged.setAttrPhone(firstNonBlankOrNull(request.getAttrPhone()));
        merged.setAttrDept(defaultIfBlank(request.getAttrDept(), "department"));
        merged.setAttrStatus(defaultIfBlank(request.getAttrStatus(), "userAccountControl"));
        merged.setConnectTimeoutSeconds(
                AdConfigValidator.normalizeTimeout(request.getConnectTimeoutSeconds()));
        return build(merged, password);
    }

    private AdConnection build(AdConfig config, String bindPassword) {
        return new AdConnection(
                AdConnection.parseHosts(config.getServerUrls()),
                AdConfigValidator.normalizePort(config.getServerPort(), Boolean.TRUE.equals(config.getUseSsl())),
                Boolean.TRUE.equals(config.getUseSsl()),
                !Boolean.FALSE.equals(config.getStrictCert()),
                config.getBaseDn(),
                config.getBindDn(),
                bindPassword,
                AdConfigValidator.normalizeTimeout(config.getConnectTimeoutSeconds()),
                defaultIfBlank(config.getUserFilter(), AdConnection.DEFAULT_FILTER),
                new com.enterprise.ticket.module.ad.ldap.AdAttributeMapping(
                        defaultIfBlank(config.getAttrLogin(), "sAMAccountName"),
                        defaultIfBlank(config.getAttrName(), "displayName"),
                        defaultIfBlank(config.getAttrEmail(), "mail"),
                        firstNonBlankOrNull(config.getAttrPhone()),
                        defaultIfBlank(config.getAttrDept(), "department"),
                        defaultIfBlank(config.getAttrStatus(), "userAccountControl")));
    }

    // ------------------------------------------------------------------
    // 测试连接
    // ------------------------------------------------------------------

    @Override
    public AdTestResultVO testConnection(AdConfigRequest request) {
        AdTestResultVO result = new AdTestResultVO();
        boolean ssl = Boolean.TRUE.equals(request.getUseSsl());
        result.setInsecure(ssl && Boolean.FALSE.equals(request.getStrictCert()));
        long started = System.currentTimeMillis();
        try {
            // 归一化同样要早于校验：维护人员在「测试连接」时填的仍是 company\query 这种原始写法
            resolveDns(request);
            // 测试连接必须完整，否则测了也是白测
            AdConfigValidator.validate(request, true,
                    StringUtils.hasText(current().getBindPasswordCipher()),
                    Boolean.TRUE.equals(request.getClearBindPassword()));
            AdConnection connection = connectionOf(request);
            result.setHost(AdDirectoryClient.describeHost(connection));
            int probed = directoryClient.probe(connection, PROBE_SAMPLE_SIZE);
            result.setOk(true);
            result.setUserCount(probed);
            result.setMessage(probed == 0
                    ? "连接与绑定成功，但在基础 DN 下未匹配到任何用户 —— 请检查「基础 DN」与「用户搜索过滤器」"
                    : "连接与绑定成功，探测到 " + probed + " 个用户"
                            + (result.isInsecure() ? "（注意：当前已关闭证书校验）" : ""));
        } catch (AdDirectoryException e) {
            // 「失败也给明确提示」：把异常分类翻成管理员能直接照着改的话
            result.setOk(false);
            result.setMessage(describeFailure(e));
        } catch (BusinessException e) {
            result.setOk(false);
            result.setMessage(e.getMessage());
        } catch (Exception e) {
            log.error("AD 连接测试发生未预期异常", e);
            result.setOk(false);
            result.setMessage("连接测试失败：" + e.getMessage());
        } finally {
            result.setElapsedMs(System.currentTimeMillis() - started);
        }
        markTestResult(buildTestSummary(result));
        return result;
    }

    private String describeFailure(AdDirectoryException e) {
        return switch (e.getKind()) {
            case CONFIG -> "配置有问题：" + e.getMessage();
            case BAD_CREDENTIALS -> "绑定认证失败：请检查「绑定 DN」与「绑定密码」是否正确（" + e.getMessage() + "）";
            case USER_NOT_FOUND -> "目录中未找到指定账号：" + e.getMessage();
            case UNAVAILABLE -> "无法连接域控：" + e.getMessage()
                    + "（请检查服务器地址、端口、是否启用 LDAPS，以及防火墙是否放行）";
        };
    }

    private String buildTestSummary(AdTestResultVO result) {
        return (result.isOk() ? "成功：" : "失败：") + result.getMessage();
    }

    // ------------------------------------------------------------------
    // DN 自动推导预览
    // ------------------------------------------------------------------

    @Override
    public AdDnPreviewVO previewDns(AdDnPreviewRequest request) {
        AdDnPreviewVO vo = new AdDnPreviewVO();
        if (request == null) {
            vo.setHint("缺少参数");
            return vo;
        }
        AdDnResolver.ResolvedDns resolved = AdDnResolver.resolve(
                request.getServerUrls(), request.getBaseDn(), request.getBindDn());

        boolean baseDnAuto = !StringUtils.hasText(request.getBaseDn())
                && StringUtils.hasText(resolved.baseDn());
        boolean bindDnAuto = StringUtils.hasText(request.getBindDn())
                && !request.getBindDn().trim().equals(resolved.bindDn());

        vo.setBaseDn(resolved.baseDn());
        vo.setBindDn(resolved.bindDn());
        vo.setBaseDnAuto(baseDnAuto);
        vo.setBindDnAuto(bindDnAuto);
        vo.setHint(buildDnHint(request, resolved, baseDnAuto, bindDnAuto));
        return vo;
    }

    /**
     * 推导结果的一句话说明。
     *
     * <p>文案由服务端给，而不是让前端按 {@code baseDn == null} 自己拼 ——
     * 「为什么推不出来 / 该去哪里填」属于规则本身的一部分，
     * 放在规则旁边才不会被前端改出两份口径。
     */
    private String buildDnHint(AdDnPreviewRequest request, AdDnResolver.ResolvedDns resolved,
                               boolean baseDnAuto, boolean bindDnAuto) {
        if (!StringUtils.hasText(resolved.baseDn())) {
            return "当前填的是 IP 地址（或主机名不含域名），系统无法推导基础 DN，"
                    + "请在「高级选项 → 自定义基础 DN」中手工填写，例如 DC=company,DC=com";
        }
        StringBuilder sb = new StringBuilder();
        if (baseDnAuto) {
            sb.append("基础 DN 由域地址自动推导");
        } else {
            // ⚠️ 本分支刻意不出现「自动推导」四个字：这里的语义是「当前值不是系统推的」，
            //    AdConfigServiceImplTest#previewWithExplicitBaseDn 会断言该文案不含这四个字。
            // 后半句「清空该处即由系统按域地址接管」是必须的： 之前基础 DN 是必填项，
            // 现场填过的值在 会被判定为「人工指定」并锁定 —— 改了域地址它不会跟着变，
            // 而这一行提示是维护人员唯一能看出来、也能改回去的地方。
            sb.append("使用自定义基础 DN（来自「高级选项 → 自定义基础 DN」，清空该处即由系统按域地址接管）");
        }
        if (bindDnAuto) {
            sb.append("；绑定账号已转为完整 DN");
        }
        return sb.toString();
    }

    @Override
    public void markTestResult(String summary) {
        try {
            AdConfig update = new AdConfig();
            update.setId(current().getId());
            update.setLastTestAt(LocalDateTime.now());
            update.setLastTestResult(truncate(summary, 500));
            adConfigMapper.updateById(update);
        } catch (Exception e) {
            // 留痕失败不得影响测试结果本身：管理员看到的结论比「有没有记下来」重要
            log.warn("记录 AD 测试结果失败（已忽略）：{}", e.getMessage());
        }
    }

    @Override
    public void markSyncResult(String summary) {
        try {
            AdConfig update = new AdConfig();
            update.setId(current().getId());
            update.setLastSyncAt(LocalDateTime.now());
            update.setLastSyncResult(truncate(summary, 500));
            adConfigMapper.updateById(update);
        } catch (Exception e) {
            log.warn("记录 AD 同步结果失败（已忽略）：{}", e.getMessage());
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String defaultIfBlank(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    /** 去空白；空白一律落 null（与 {@link #defaultIfBlank} 的区别：不提供兜底值） */
    private String firstNonBlankOrNull(String value) {
        return trimToNull(value);
    }

    /**
     * 去空白；空白一律落<b>空串</b>。
     *
     * <p>用于 {@code ad_config} 的 {@code NOT NULL DEFAULT ''} 文本列 ——
     * 这些列的「没有值」就是空串。刻意与 {@link #firstNonBlankOrNull} 的 null 语义分开：
     * null 只用于内存对象（如 {@code AdAttributeMapping.attrPhone} 的「不同步手机号」），
     * 一旦当成落库值写下去，数据库会用 {@code Column 'xxx' cannot be null} 直接拒绝。
     */
    private static String trimToEmpty(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
