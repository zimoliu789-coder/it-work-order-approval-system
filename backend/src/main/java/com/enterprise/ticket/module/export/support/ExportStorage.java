package com.enterprise.ticket.module.export.support;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.storage.RelativeFileStorage;
import com.enterprise.ticket.config.AppProperties;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Path;

/**
 * 导出文件落盘支撑
 *
 * <p>导出文件与附件是<b>两类生命周期不同</b>的东西，故根目录分开（{@code app.export.storage-root}）：
 * 附件是用户上传的原始资料，长期保留；导出文件是系统生成的临时产物，有明确过期时间。
 * 混在同一目录会让「按过期时间清理」误伤附件。
 *
 * <p>写盘 / 相对路径解析 / 路径穿越断言复用 {@link RelativeFileStorage}，
 * 与附件共用同一份实现（避免两处各写一份后只给其中一处打补丁）。
 * 本类只补充导出特有的三件事：固定扩展名 {@code xlsx}、异步阈值、过期天数。
 */
@Component
public class ExportStorage {

    /** 导出文件扩展名固定为 xlsx（ 只要求 Excel 导出） */
    private static final String EXPORT_EXTENSION = "xlsx";

    private final AppProperties appProperties;
    private final RelativeFileStorage fileStorage;

    public ExportStorage(AppProperties appProperties) {
        this.appProperties = appProperties;
        this.fileStorage = new RelativeFileStorage(
                appProperties.getExport().getStorageRoot(),
                ErrorCode.EXPORT_NOT_FOUND,
                ErrorCode.EXPORT_SAVE_FAILED);
    }

    /** 写盘并返回相对存储根目录的相对路径（形如 {@code 2026/09/<uuid>.xlsx}） */
    public String store(InputStream in) {
        return fileStorage.store(in, EXPORT_EXTENSION);
    }

    /** 相对路径 → 绝对路径（含越界断言） */
    public Path resolve(String relativePath) {
        return fileStorage.resolve(relativePath);
    }

    /** 删除磁盘文件；失败只告警 */
    public void deleteQuietly(String relativePath) {
        fileStorage.deleteQuietly(relativePath);
    }

    /** 递归列出存储根下全部文件（ 导出孤儿 / 过期文件清理） */
    public java.util.List<Path> listFiles() {
        return fileStorage.listFiles();
    }

    /** 绝对路径 → 相对存储根的相对路径（{@code /} 分隔，与落库格式一致） */
    public String relativeOf(Path file) {
        return fileStorage.relativeOf(file);
    }

    /** 行数超过该值改为异步生成 + 站内消息通知（：10000） */
    public int asyncThreshold() {
        return appProperties.getExport().getAsyncThreshold();
    }

    /** 单次导出允许的最大行数（防止把整库拖进内存） */
    public int maxRows() {
        return appProperties.getExport().getMaxRows();
    }

    /**
     * 单个工作表的列数上限（仅自定义表单导出取用）
     *
     * <p>放在这里而不是让 writer 自己读配置：与行数上限同一处理手法 ——
     * 「一次导出的规模约束」集中在一处，调用方不需要知道 AppProperties 的结构。
     */
    public int maxColumns() {
        return appProperties.getExport().getMaxColumns();
    }

    /** 异步导出文件保留天数 */
    public int expireDays() {
        return appProperties.getExport().getExpireDays();
    }
}
