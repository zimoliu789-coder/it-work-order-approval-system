package com.enterprise.ticket.module.export.dto;

import com.enterprise.ticket.module.export.entity.ExportTask;
import org.springframework.core.io.Resource;

/**
 * 导出文件下载载荷（Service → Controller 的内部传递对象）
 *
 * <p>用 {@code record} 承载「已通过鉴权与状态校验的任务 + 可流式读出的资源」，
 * 避免 Controller 再去查一次库或拼一次路径：路径解析（含越界断言）只在 Service 里做一次。
 */
public record ExportDownload(ExportTask task, Resource resource) {
}
