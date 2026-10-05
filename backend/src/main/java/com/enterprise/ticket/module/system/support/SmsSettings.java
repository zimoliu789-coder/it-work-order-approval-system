package com.enterprise.ticket.module.system.support;

import java.util.List;

/**
 * 短信参数契约（：<b>先做出来、标注「暂未启用」</b>）。
 *
 * <h2>为什么「暂未启用」还要把参数做出来</h2>
 * <p>说明就是「预留」。一旦走了「等接入网关时再加参数」这条路，
 * 上线当天就要同时改：迁移脚本、参数目录、配置页、发送实现 ——
 * 而其中最容易出错的恰恰是「配置项该加密的没加密」。
 * 现在把键名、默认值、加密用途先定下来，接入网关时只需实现发送与连通性测试。
 *
 * <h2>为什么这里没有 {@code complete()} 之类的「完整性」布尔判定</h2>
 * <p>SMTP 有「够不够发一封信」这种明确的完整性口径，短信没有 ——
 * 不同服务商（阿里云 / 腾讯云 / 华为云）对模板与签名的必填要求各不相同，
 * 在接入网关之前写一套「完整性」判定只会误报。
 *
 * <p> 新增的「发送测试短信」需要一个判定入口，做法是
 * {@link Settings#problems()}：返回<b>逐项问题清单</b>而不是一个布尔量。
 * 两者的差别不是措辞 —— 布尔量只能回答「能不能发」（说了等于没说），
 * 而清单能一次把「差哪几项」说完，且刻意只覆盖两类<b>确定性事实</b>：
 * 必填项为空、服务商编码不在枚举内。
 * 「签名未备案 / 模板不存在 / 密钥无效」只有服务商能判，本地假装能判就是误报。
 * 因此本类<b>只登记参数与参考说明</b>，不做任何运行时判定；
 * 配置页对整张卡片展示「暂未启用」提示。
 *
 * <h2>为什么 Secret 要单独加密用途</h2>
 * <p>AccessKey Secret 与 SMTP 授权码、AD 绑定密码同属「可还原凭据」，
 * 但用途隔离后，误把某一个粘到另一个字段里会立刻解密失败（见 {@code SecretCipher}），
 * 而不是带着错误凭据去请求服务商、拿到一个看不懂的签名错误。
 */
public final class SmsSettings {

    private SmsSettings() {
    }

    // ------------------------------------------------------------------
    // 配置键（与 V28 迁移脚本里的 config_key 逐字对应）
    // ------------------------------------------------------------------

    /** 服务商：ALIYUN / TENCENT / HUAWEI / OTHER */
    public static final String KEY_PROVIDER = "sms_provider";

    /** AccessKey ID */
    public static final String KEY_ACCESS_KEY_ID = "sms_access_key_id";

    /** AccessKey Secret（加密存储） */
    public static final String KEY_ACCESS_KEY_SECRET = "sms_access_key_secret";

    /** 短信签名 */
    public static final String KEY_SIGN_NAME = "sms_sign_name";

    /** 模板代码 */
    public static final String KEY_TEMPLATE_CODE = "sms_template_code";

    // ------------------------------------------------------------------
    // 取值域与默认值
    // ------------------------------------------------------------------

    /** 服务商编码（与配置页下拉选项一致；留空表示尚未选择） */
    public static final List<String> PROVIDERS = List.of("ALIYUN", "TENCENT", "HUAWEI", "OTHER");

    /** 服务商编码 → 显示名（唯一出处，避免配置页与后端各写一份） */
    public static String providerLabel(String code) {
        if (code == null) {
            return "";
        }
        return switch (code.trim().toUpperCase()) {
            case "ALIYUN" -> "阿里云短信";
            case "TENCENT" -> "腾讯云短信";
            case "HUAWEI" -> "华为云短信";
            case "OTHER" -> "其他 / 自建网关";
            default -> "";
        };
    }

    /** 默认服务商（空 = 未选择，不预置 —— 预留阶段不该假装已经选好了） */
    public static final String DEFAULT_PROVIDER = "";

    /** 配置页展示的说明（不参与运行时判定） */
    public static final String[] REFERENCE_NOTES = {
            "短信通道当前未接入：本卡片参数会正常保存，但验证码仍投递到应用日志（grep FORGOT-CODE 可取）。",
            "接入网关后，找回密码与绑定联系方式的手机验证将自动改用真实短信（无需再改配置项）。",
            "AccessKey Secret 与 SMTP 授权码一样加密落库，页面上只显示掩码；留空表示不修改。"
    };

    /**
     * 生效中的短信设置（AccessKey Secret 已解密）。
     *
     * @param provider        服务商编码（{@code ALIYUN / TENCENT / HUAWEI / OTHER}，空 = 未选择）
     * @param accessKeyId     访问密钥 ID
     * @param accessKeySecret 访问密钥密文（解密后；解密失败为 {@code null}）
     * @param signName        短信签名
     * @param templateCode    模板代码
     */
    public record Settings(String provider, String accessKeyId, String accessKeySecret,
                           String signName, String templateCode) {

        /**
         * 逐项问题清单（空列表 = 看起来可以发）。
         *
         * <h2>为什么是「问题清单」而不是 {@code complete()}</h2>
         * <p>见类注释第二段：短信没有跨服务商通用的「完整性」口径，
         * 一个布尔量只够回答「能不能发」，回答不了「差什么」——
         * 而差什么才是管理员点「发送测试短信」时唯一想知道的事。
         * 返回清单让接口能把缺项一次说完，而不是修一项、再报下一项。
         *
         * <h2>为什么这里只判「有没有填 + 认不认得」</h2>
         * <p>「签名未备案 / 模板不存在 / 密钥无效」这类问题只有<b>服务商</b>能判 ——
         * 在本地假装能判，就会把「填得对但服务商说不行」误报成配置错误。
         * 因此本地只覆盖两类确定性事实：必填项为空、服务商编码不在枚举内。
         */
        public java.util.List<String> problems() {
            java.util.List<String> out = new java.util.ArrayList<>();
            String code = provider == null ? "" : provider.trim();
            if (code.isEmpty()) {
                out.add("未选择服务商");
            } else if (!PROVIDERS.contains(code.toUpperCase())) {
                out.add("服务商「" + code + "」无法识别（可选：" + String.join(" / ", PROVIDERS) + "）");
            }
            if (!hasText(accessKeyId)) {
                out.add("未填写 AccessKey ID");
            }
            if (!hasText(accessKeySecret)) {
                out.add("未填写 AccessKey Secret（或密文无法解密，请重新保存）");
            }
            if (!hasText(signName)) {
                out.add("未填写短信签名");
            }
            if (!hasText(templateCode)) {
                out.add("未填写模板代码");
            }
            return out;
        }

        /** 服务商显示名（未选择时返回空串） */
        public String providerLabel() {
            return SmsSettings.providerLabel(provider);
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }
}
