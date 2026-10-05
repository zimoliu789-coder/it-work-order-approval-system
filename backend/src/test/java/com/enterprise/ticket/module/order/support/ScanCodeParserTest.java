package com.enterprise.ticket.module.order.support;

import com.enterprise.ticket.common.constant.ScanMatchType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 扫码内容解析单元测试（P1 扫码借还）
 *
 * <h2>为什么这个类值得逐条断言</h2>
 * <p>「码里到底是什么」是**外部输入**，且解析错的表现极其隐蔽 —— 整串拿去查库只会得到
 * 「未识别到设备」，看不出是解析把编号切坏了（P0 的 JDBC URL 解析正是这么翻的车：
 * 库名解析成 {@code /localhost:3306/ticket_system}，报错却像「库不存在」）。
 *
 * <p>因此这里对**每种形态逐字段断言**：{@code code} 解析成了什么、{@code hint} 是哪种码，
 * 而不是只断言「没有抛异常」。
 */
@DisplayName("扫码内容解析（P1 扫码借还）")
class ScanCodeParserTest {

    // ==================================================================
    // 纯编号：最常见的形态，也是唯一「无从判断类型」的形态
    // ==================================================================

    @Nested
    @DisplayName("纯编号")
    class PlainCode {

        @Test
        @DisplayName("资产编号原样返回，且不猜类型")
        void plainAssetNo() {
            ScanCodeParser.ParsedScan parsed = ScanCodeParser.parse("IT-2026-0001");

            assertEquals("IT-2026-0001", parsed.code());
            assertNull(parsed.hint(),
                    "无前缀时必须返回 null（= 两张表都查）。若在这里拍脑袋猜成资产编号，"
                            + "扫工单号的场景就会永远查不到");
            assertTrue(parsed.hasCode());
        }

        @Test
        @DisplayName("工单号原样返回，且不猜类型")
        void plainOrderNo() {
            ScanCodeParser.ParsedScan parsed = ScanCodeParser.parse("BO20261003-100");

            assertEquals("BO20261003-100", parsed.code());
            assertNull(parsed.hint());
        }

        @Test
        @DisplayName("首尾空白被裁掉（二维码里带换行是常态）")
        void trimsWhitespace() {
            ScanCodeParser.ParsedScan parsed = ScanCodeParser.parse("  IT-2026-0001\n");

            assertEquals("IT-2026-0001", parsed.code());
        }
    }

    // ==================================================================
    // 前缀码
    // ==================================================================

    @Nested
    @DisplayName("带前缀")
    class Prefixed {

        @Test
        @DisplayName("ASSET: 前缀 ⇒ 剥壳并标记为资产编号")
        void assetPrefix() {
            ScanCodeParser.ParsedScan parsed = ScanCodeParser.parse("ASSET:IT-2026-0001");

            assertEquals("IT-2026-0001", parsed.code(), "前缀必须被剥掉，否则拿去查库永远查不到");
            assertEquals(ScanMatchType.ASSET_NO, parsed.hint());
        }

        @Test
        @DisplayName("ORDER: 前缀 ⇒ 剥壳并标记为工单号")
        void orderPrefix() {
            ScanCodeParser.ParsedScan parsed = ScanCodeParser.parse("ORDER:BO20261003-100");

            assertEquals("BO20261003-100", parsed.code());
            assertEquals(ScanMatchType.ORDER_NO, parsed.hint());
        }

        @Test
        @DisplayName("前缀大小写与短横线都归一：asset-no=xxx 同样命中")
        void prefixIsNormalized() {
            ScanCodeParser.ParsedScan parsed = ScanCodeParser.parse("asset-no=IT-1");

            assertEquals("IT-1", parsed.code());
            assertEquals(ScanMatchType.ASSET_NO, parsed.hint());
        }

        @Test
        @DisplayName("通用外壳 CODE: ⇒ 剥壳但**不**指定类型")
        void genericPrefix() {
            ScanCodeParser.ParsedScan parsed = ScanCodeParser.parse("CODE:XYZ-1");

            assertEquals("XYZ-1", parsed.code());
            assertEquals("", parsed.hint(),
                    "通用外壳只说明「外面包了一层」，装的是资产编号还是工单号仍未知 ⇒ 两张表都查");
        }

        @Test
        @DisplayName("未知前缀不切分：编号里含冒号时按原文处理")
        void unknownPrefixKeepsRaw() {
            ScanCodeParser.ParsedScan parsed = ScanCodeParser.parse("IT:001");

            assertEquals("IT:001", parsed.code(),
                    "前缀不在白名单就必须原文返回 —— 切了只会拿一个不存在的短串去查库");
            assertNull(parsed.hint());
        }

        @Test
        @DisplayName("前缀后为空 ⇒ 不产生空编码")
        void prefixWithoutValue() {
            ScanCodeParser.ParsedScan parsed = ScanCodeParser.parse("ASSET:");

            assertFalse(parsed.code() == null || parsed.code().isBlank(),
                    "剥壳后若为空，必须退回原文而不是产出一个空编码");
            assertNull(parsed.hint());
        }
    }

    // ==================================================================
    // URL 形态（贴纸常印成链接）
    // ==================================================================

    @Nested
    @DisplayName("URL 形态")
    class Url {

        @Test
        @DisplayName("?assetNo= 参数优先于 path")
        void assetQueryWins() {
            ScanCodeParser.ParsedScan parsed =
                    ScanCodeParser.parse("https://itsm.example.com/scan?assetNo=IT-2026-0001");

            assertEquals("IT-2026-0001", parsed.code());
            assertEquals(ScanMatchType.ASSET_NO, parsed.hint());
        }

        @Test
        @DisplayName("?orderNo= 参数同样被识别")
        void orderQuery() {
            ScanCodeParser.ParsedScan parsed =
                    ScanCodeParser.parse("https://itsm.example.com/s?orderNo=BO20261003-100");

            assertEquals("BO20261003-100", parsed.code());
            assertEquals(ScanMatchType.ORDER_NO, parsed.hint());
        }

        @Test
        @DisplayName("参数顺序无关：目标参数在其它参数之后也能取到")
        void queryOrderIndependent() {
            ScanCodeParser.ParsedScan parsed =
                    ScanCodeParser.parse("https://h.example.com/p?from=wechat&ts=1&assetNo=IT-9");

            assertEquals("IT-9", parsed.code());
            assertEquals(ScanMatchType.ASSET_NO, parsed.hint());
        }

        @Test
        @DisplayName("无 query 参数时取 path 最后一段")
        void pathTail() {
            ScanCodeParser.ParsedScan parsed =
                    ScanCodeParser.parse("https://itsm.example.com/asset/IT-2026-0001");

            assertEquals("IT-2026-0001", parsed.code());
            assertNull(parsed.hint());
        }

        @Test
        @DisplayName("尾斜杠与 fragment 都要去掉")
        void trailingSlashAndFragment() {
            ScanCodeParser.ParsedScan parsed =
                    ScanCodeParser.parse("https://h.example.com/asset/IT-9/#detail");

            assertEquals("IT-9", parsed.code());
        }

        @Test
        @DisplayName("URL 里没有可用编码 ⇒ 视为没读到内容")
        void urlWithoutCode() {
            ScanCodeParser.ParsedScan parsed = ScanCodeParser.parse("https://itsm.example.com/");

            assertFalse(parsed.hasCode());
        }
    }

    // ==================================================================
    // 空值
    // ==================================================================

    @Nested
    @DisplayName("空值")
    class Blanks {

        @Test
        @DisplayName("null / 空串 / 纯空白 ⇒ 没有编码")
        void blanksHaveNoCode() {
            assertFalse(ScanCodeParser.parse(null).hasCode());
            assertFalse(ScanCodeParser.parse("").hasCode());
            assertFalse(ScanCodeParser.parse("   ").hasCode());
        }

        @Test
        @DisplayName("空值的 hint 也是 null，不会误判成某一种码")
        void blankHintIsNull() {
            assertNull(ScanCodeParser.parse(null).hint());
            assertNull(ScanCodeParser.parse("").hint());
        }
    }
}
