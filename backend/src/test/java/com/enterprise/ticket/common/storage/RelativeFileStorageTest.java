package com.enterprise.ticket.common.storage;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 相对路径落盘支撑单测（附件 §25 与导出 §26 共用）
 *
 * <p>本类集中了<b>路径穿越防护</b>这一跨模块安全不变量，测试围绕两层防线：
 * <ol>
 *   <li>第一层 —— {@code normalizeExtension}：扩展名白名单 {@code [a-z0-9]}，
 *       杜绝把用户输入拼进落盘名；</li>
 *   <li>第二层 —— {@code resolve}：解析结果必须仍在存储根之内，否则拒绝
 *       （防 {@code stored_path} 被人工改库或未来被污染）。</li>
 * </ol>
 * 这里断言的是「安全边界」，而不是实现细节 —— 只要边界被突破，测试必须变红。
 */
class RelativeFileStorageTest {

    @TempDir
    Path tempDir;

    private RelativeFileStorage storage() {
        return new RelativeFileStorage(tempDir.toString(), ErrorCode.EXPORT_NOT_FOUND, ErrorCode.EXPORT_SAVE_FAILED);
    }

    // ------------------------------------------------------------------
    // 第一层防线：扩展名白名单
    // ------------------------------------------------------------------

    @Test
    @DisplayName("合法扩展名：小写化并接受（含前置点）")
    void normalizeExtension_acceptsValid() {
        assertEquals("xlsx", RelativeFileStorage.normalizeExtension("xlsx"));
        assertEquals("png", RelativeFileStorage.normalizeExtension(".PNG"));
        assertEquals("jpeg", RelativeFileStorage.normalizeExtension("jpeg"));
    }

    @Test
    @DisplayName("非法扩展名一律返回 null：含路径分隔 / 点 / 符号 / 超长 / 空")
    void normalizeExtension_rejectsMalicious() {
        assertNull(RelativeFileStorage.normalizeExtension(null));
        assertNull(RelativeFileStorage.normalizeExtension(""));
        assertNull(RelativeFileStorage.normalizeExtension("   "));
        assertNull(RelativeFileStorage.normalizeExtension("a.b/../c"));
        assertNull(RelativeFileStorage.normalizeExtension("../xlsx"));
        assertNull(RelativeFileStorage.normalizeExtension("pd f"));
        assertNull(RelativeFileStorage.normalizeExtension("thisisexttoolong"));
    }

    // ------------------------------------------------------------------
    // 第二层防线：resolve 越界断言
    // ------------------------------------------------------------------

    @Test
    @DisplayName("resolve：解析结果越出存储根 → EXPORT_NOT_FOUND（防路径穿越）")
    void resolve_traversalRejected() {
        RelativeFileStorage s = storage();
        assertThrows(BusinessException.class, () -> s.resolve("../evil.xlsx"));
        // 三个 .. 才能从 root/2026/09 逃出 root（两个 .. 只是回到 root，仍在根内）
        assertThrows(BusinessException.class, () -> s.resolve("2026/09/../../../evil.xlsx"));
    }

    @Test
    @DisplayName("resolve：空 / 空白路径 → EXPORT_NOT_FOUND（不抛 NPE）")
    void resolve_blankRejected() {
        RelativeFileStorage s = storage();
        assertEquals(ErrorCode.EXPORT_NOT_FOUND, assertThrows(BusinessException.class,
                () -> s.resolve(" ")).getErrorCode());
    }

    @Test
    @DisplayName("resolve：正常相对路径解析到存储根之内")
    void resolve_withinRoot() {
        RelativeFileStorage s = storage();
        Path resolved = s.resolve("2026/09/abc.xlsx");
        assertTrue(resolved.startsWith(s.root()));
    }

    // ------------------------------------------------------------------
    // 落盘 / 读取 / 删除
    // ------------------------------------------------------------------

    @Test
    @DisplayName("store：返回相对路径（形如 yyyy/MM/<uuid>.xlsx），内容原样落盘且可被 resolve 读回")
    void store_thenResolve_roundTrip() throws Exception {
        RelativeFileStorage s = storage();
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);

        String relative = s.store(new ByteArrayInputStream(content), "xlsx");

        assertNotNull(relative);
        assertTrue(relative.endsWith(".xlsx"), "相对路径应以 .xlsx 结尾，实际：" + relative);
        assertFalse(relative.contains(".."), "落盘名绝不能包含 .. ");
        Path onDisk = s.resolve(relative);
        assertTrue(Files.exists(onDisk));
        assertEquals("hello", Files.readString(onDisk));
    }

    @Test
    @DisplayName("store：非法扩展名 → 拒绝落盘（EXPORT_SAVE_FAILED），不产生文件")
    void store_invalidExtension_throws() {
        RelativeFileStorage s = storage();
        assertThrows(BusinessException.class,
                () -> s.store(new ByteArrayInputStream(new byte[0]), "a.b/../c"));
    }

    @Test
    @DisplayName("deleteQuietly：文件不存在不抛错（清理已过期文件时不应阻断业务）")
    void deleteQuietly_missingIsNoOp() {
        RelativeFileStorage s = storage();
        s.deleteQuietly("2026/09/not-there.xlsx");
    }

    @Test
    @DisplayName("deleteQuietly：已存在文件被删除")
    void deleteQuietly_removesFile() throws Exception {
        RelativeFileStorage s = storage();
        String relative = s.store(new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), "xlsx");
        Path onDisk = s.resolve(relative);
        assertTrue(Files.exists(onDisk));

        s.deleteQuietly(relative);

        assertFalse(Files.exists(onDisk));
    }
}
