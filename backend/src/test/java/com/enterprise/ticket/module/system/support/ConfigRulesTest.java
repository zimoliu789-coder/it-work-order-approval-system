package com.enterprise.ticket.module.system.support;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 系统参数取值规则（P18-B 需求三 / 四 / 五 新增部分）。
 *
 * <h2>为什么新增参数必须连带加用例</h2>
 * <p>「未登记的键不做值域校验、原样保存」是本类刻意的设计（避免新增参数被旧版本拒绝），
 * 代价是<b>忘了登记就等于忘了校验</b>：把 {@code smtp_port} 漏登记，运维就能填 99999，
 * 表现为「测试邮件一直超时」；把密文项的长度上限漏登记，一条超长授权码的密文会超出
 * {@code VARCHAR(512)}，用户看到的是「保存失败」而不是「太长了」。
 * 因此这里逐项钉住「登记了、且边界正确」。
 */
class ConfigRulesTest {

    @Nested
    @DisplayName("邮件 SMTP（需求三）")
    class Mail {

        @Test
        @DisplayName("端口 1 ~ 65535，越界拒绝")
        void port() {
            assertEquals("465", ConfigRules.normalize(MailSettings.KEY_PORT, "465"));
            assertEquals("1", ConfigRules.normalize(MailSettings.KEY_PORT, "1"));
            assertEquals("65535", ConfigRules.normalize(MailSettings.KEY_PORT, "65535"));
            assertThrows(BusinessException.class, () -> ConfigRules.normalize(MailSettings.KEY_PORT, "0"));
            assertThrows(BusinessException.class, () -> ConfigRules.normalize(MailSettings.KEY_PORT, "65536"));
            assertThrows(BusinessException.class, () -> ConfigRules.normalize(MailSettings.KEY_PORT, "abc"));
        }

        @Test
        @DisplayName("服务器 / 账号 / 显示名允许留空（= 尚未配置）")
        void optionalTexts() {
            assertEquals("", ConfigRules.normalize(MailSettings.KEY_HOST, ""));
            assertEquals("", ConfigRules.normalize(MailSettings.KEY_HOST, "   "));
            assertEquals("smtp.qq.com", ConfigRules.normalize(MailSettings.KEY_HOST, "  smtp.qq.com  "));
            assertEquals("", ConfigRules.normalize(MailSettings.KEY_FROM_NAME, ""));
        }

        @Test
        @DisplayName("授权码是密文项：长度上限 200（而不是全局的 500），留空允许")
        void secretLength() {
            ConfigRules.Rule rule = ConfigRules.ruleOf(MailSettings.KEY_PASSWORD);
            assertNotNull(rule);
            assertTrue(rule.secretSized(), "授权码必须走密文长度上限");
            assertEquals(ConfigRules.MAX_SECRET_LENGTH, rule.max());

            assertEquals("", ConfigRules.normalize(MailSettings.KEY_PASSWORD, ""));
            assertEquals("authcode", ConfigRules.normalize(MailSettings.KEY_PASSWORD, "authcode"));
            // 长度上限内的最长值
            String max = "x".repeat(ConfigRules.MAX_SECRET_LENGTH);
            assertEquals(max, ConfigRules.normalize(MailSettings.KEY_PASSWORD, max));
            // 超一个字符即拒绝 —— 拒绝应发生在加密之前，否则密文会超出列宽报数据库错误
            String tooLong = "x".repeat(ConfigRules.MAX_SECRET_LENGTH + 1);
            assertThrows(BusinessException.class,
                    () -> ConfigRules.normalize(MailSettings.KEY_PASSWORD, tooLong));
        }

        @Test
        @DisplayName("SSL 开关归一化成 0 / 1，中文一律拒绝")
        void sslToggle() {
            assertEquals("1", ConfigRules.normalize(MailSettings.KEY_SSL, "true"));
            assertEquals("1", ConfigRules.normalize(MailSettings.KEY_SSL, "YES"));
            assertEquals("0", ConfigRules.normalize(MailSettings.KEY_SSL, "false"));
            assertEquals("0", ConfigRules.normalize(MailSettings.KEY_SSL, "no"));
            assertEquals("", ConfigRules.normalize(MailSettings.KEY_SSL, ""), "留空 = 使用代码默认值");
            assertThrows(BusinessException.class, () -> ConfigRules.normalize(MailSettings.KEY_SSL, "开"));
        }
    }

    @Nested
    @DisplayName("短信通道（需求四，预留）")
    class Sms {

        @Test
        @DisplayName("五项都可留空，Secret 走密文长度上限")
        void keys() {
            List<String> keys = List.of(
                    SmsSettings.KEY_PROVIDER,
                    SmsSettings.KEY_ACCESS_KEY_ID,
                    SmsSettings.KEY_ACCESS_KEY_SECRET,
                    SmsSettings.KEY_SIGN_NAME,
                    SmsSettings.KEY_TEMPLATE_CODE);
            for (String key : keys) {
                assertNotNull(ConfigRules.ruleOf(key), "短信参数「" + key + "」必须有规则");
                assertEquals("", ConfigRules.normalize(key, ""));
            }
            assertTrue(ConfigRules.ruleOf(SmsSettings.KEY_ACCESS_KEY_SECRET).secretSized());
            assertFalse(ConfigRules.ruleOf(SmsSettings.KEY_ACCESS_KEY_ID).secretSized());
        }

        @Test
        @DisplayName("服务商编码有中文名；未知编码返回空串（页面对未选项显示「未选择」）")
        void providerLabels() {
            assertEquals("阿里云短信", SmsSettings.providerLabel("ALIYUN"));
            assertEquals("腾讯云短信", SmsSettings.providerLabel("tencent"));
            assertEquals("华为云短信", SmsSettings.providerLabel("HUAWEI"));
            assertEquals("", SmsSettings.providerLabel("not-a-provider"));
            assertEquals("", SmsSettings.providerLabel(null));
        }
    }

    @Nested
    @DisplayName("文件存储与保留天数（需求五）")
    class Storage {

        @Test
        @DisplayName("附件目录可留空（= 沿用部署配置），超 300 字拒绝")
        void path() {
            assertEquals("", ConfigRules.normalize(StorageSettings.KEY_ATTACHMENT_PATH, ""));
            assertEquals("/mnt/nas/attachments",
                    ConfigRules.normalize(StorageSettings.KEY_ATTACHMENT_PATH, " /mnt/nas/attachments "));
            String tooLong = "/mnt/" + "a".repeat(300);
            assertThrows(BusinessException.class,
                    () -> ConfigRules.normalize(StorageSettings.KEY_ATTACHMENT_PATH, tooLong));
        }

        @Test
        @DisplayName("保留天数下限 1 天（0 会把仍在引用的文件删掉）")
        void retention() {
            assertEquals("30", ConfigRules.normalize(StorageSettings.KEY_ATTACHMENT_RETENTION_DAYS, "30"));
            assertEquals("7", ConfigRules.normalize(StorageSettings.KEY_EXPORT_RETENTION_DAYS, "7"));
            assertEquals("1", ConfigRules.normalize(StorageSettings.KEY_ATTACHMENT_RETENTION_DAYS, "1"));
            assertThrows(BusinessException.class,
                    () -> ConfigRules.normalize(StorageSettings.KEY_ATTACHMENT_RETENTION_DAYS, "0"));
            assertThrows(BusinessException.class,
                    () -> ConfigRules.normalize(StorageSettings.KEY_EXPORT_RETENTION_DAYS, "0"));
            assertThrows(BusinessException.class,
                    () -> ConfigRules.normalize(StorageSettings.KEY_EXPORT_RETENTION_DAYS, "3651"));
        }

        @Test
        @DisplayName("路径解析：配了就用自己的，没配回落部署配置")
        void resolveRoot() {
            assertEquals("./data/attachments",
                    StorageSettings.resolveRoot("", "./data/attachments"));
            assertEquals("./data/attachments",
                    StorageSettings.resolveRoot(null, "./data/attachments"));
            assertEquals("/mnt/nas/attachments",
                    StorageSettings.resolveRoot("/mnt/nas/attachments", "./data/attachments"));
            assertEquals("/mnt/nas/attachments",
                    StorageSettings.resolveRoot("  /mnt/nas/attachments  ", "./data/attachments"));
        }
    }

    @Test
    @DisplayName("V28 新增的 14 个键都被登记（漏登记 = 漏校验）")
    void newKeysRegistered() {
        List<String> newKeys = List.of(
                MailSettings.KEY_HOST, MailSettings.KEY_PORT, MailSettings.KEY_USERNAME,
                MailSettings.KEY_PASSWORD, MailSettings.KEY_FROM_NAME, MailSettings.KEY_SSL,
                SmsSettings.KEY_PROVIDER, SmsSettings.KEY_ACCESS_KEY_ID, SmsSettings.KEY_ACCESS_KEY_SECRET,
                SmsSettings.KEY_SIGN_NAME, SmsSettings.KEY_TEMPLATE_CODE,
                StorageSettings.KEY_ATTACHMENT_PATH, StorageSettings.KEY_ATTACHMENT_RETENTION_DAYS,
                StorageSettings.KEY_EXPORT_RETENTION_DAYS);
        assertEquals(14, newKeys.size());
        for (String key : newKeys) {
            assertTrue(ConfigRules.isKnown(key), "新增参数「" + key + "」未在 ConfigRules 登记");
        }
    }

    @Test
    @DisplayName("错误码是 CONFIG_VALUE_INVALID（前端据此把错误标在字段上）")
    void errorCode() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> ConfigRules.normalize(MailSettings.KEY_PORT, "0"));
        assertEquals(ErrorCode.CONFIG_VALUE_INVALID, e.getErrorCode());
        assertTrue(e.getMessage().contains(MailSettings.KEY_PORT), "错误信息要带上键名，便于定位");
    }
}
