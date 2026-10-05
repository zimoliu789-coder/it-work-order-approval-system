package com.enterprise.ticket.module.upgrade.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 升级包清单（包内根目录的 {@code manifest.json}）
 *
 * <pre>
 * {
 *   "version":   "1.5.0",
 *   "buildTime": "2026-10-01T10:00:00",
 *   "files": [
 *     { "path": "backend.jar", "sha256": "&lt;64 位十六进制&gt;", "size": 12345678 },
 *     { "path": "frontend/dist/index.html", "sha256": "...", "size": 1024 }
 *   ]
 * }
 * </pre>
 *
 * <h2>为什么 manifest 必须放在包内，而不是走接口参数传入</h2>
 * <p>清单是「打包方对包内容的承诺」。若由调用方单独传参，篡改者完全可以
 * 换掉包里的文件却沿用旧清单 —— 校验就变成了一道自己证明自己的手续。
 * 放进包里、由包自身的 SHA-256 兜底，才构成一条完整的信任链：
 * <b>包哈希 → 清单 → 每个文件哈希</b>。
 *
 * <h2>为什么允许未知字段（{@code ignoreUnknown}）</h2>
 * <p>这是<b>向前兼容</b>的必需品：新版本打包脚本必然会往清单里加字段
 * （构建流水号、提交号、依赖锁定哈希……），而升级的旧后端只认识老字段。
 * 若不允许未知字段，一个完全正常的新包会因为「多了个字段」被旧后端拒绝 ——
 * 而那恰恰是最需要升级成功的时刻。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpgradeManifest(String version, String buildTime, List<FileEntry> files) {

    /**
     * 清单中的一个文件条目。
     *
     * <p>{@code size} 允许为空（老打包脚本可能不写），缺失时只校验 SHA-256 ——
     * 哈希本身已经蕴含了长度信息，所以这不是安全性上的妥协，
     * 只是别让「没写 size」变成一次无谓的拒绝。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FileEntry(String path, String sha256, Long size) {
    }

    /** 条目为空时返回空列表，避免调用方到处判空 */
    public List<FileEntry> fileList() {
        return files == null ? List.of() : files;
    }
}
