package com.enterprise.ticket.module.export.service;

import com.enterprise.ticket.common.constant.ExportType;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.module.export.dto.ExportDownload;
import com.enterprise.ticket.module.export.dto.ExportQuery;
import com.enterprise.ticket.module.export.dto.vo.ExportResultVO;
import com.enterprise.ticket.module.export.dto.vo.ExportTaskVO;

import java.util.List;

/**
 * Excel 导出服务
 *
 * <p><b>同步 / 异步的分界</b>：「导出超过 10000 条时异步生成文件，
 * 完成后站内消息通知下载」。因此 {@link #export} 先按条件<b>计数</b>，
 * 再决定当场生成（返回可直接下载的文件）还是落任务排队（返回 ASYNC）。
 * 两条路径都落一条 {@code export_task} 记录，用户即便关掉页面也能在导出记录里找回文件。
 *
 * <p><b>权限</b>：
 * <ul>
 *   <li>设备台账导出——仅 {@code super_admin} / {@code admin}；</li>
 *   <li>工单记录导出——任何登录用户均可，但普通 {@code user} <b>只能导出自己的工单</b>
 *       （服务端强制，不看前端传的 {@code scope}）；</li>
 *   <li>下载导出文件——仅发起人本人或 {@code super_admin} / {@code admin}。</li>
 * </ul>
 */
public interface ExportService {

    /**
     * 发起导出
     *
     * @return 同步=文件已就绪（含 downloadUrl）；异步=已受理（含 taskId，稍后消息通知）
     */
    ExportResultVO export(ExportType type, ExportQuery query);

    /**
     * 我的导出记录（最近若干条）
     *
     * <p>管理员可看全部人的记录（便于运维排查「谁把库导出了」），此时返回含发起人信息。
     */
    List<ExportTaskVO> recentTasks(ExportType type, int limit);

    /**
     * 导出记录分页（管理员可见全部；普通用户仅本人）
     *
     * <p>与 {@link #recentTasks} 相比，本方法返回总条数，供前端分页控件使用；
     * 两者共用同一套「非管理员条件前置」的可见性收口。
     */
    PageResult<ExportTaskVO> pageTasks(ExportType type, int page, int size);

    /** 导出记录详情（仅本人或管理员） */
    ExportTaskVO getTask(Long taskId);

    /**
     * 删除导出记录（仅本人或管理员）
     *
     * <p>同时删除磁盘文件（失败只告警）。删除后即便文件残留，也会被附件/导出孤儿清理任务回收。
     */
    void deleteTask(Long taskId);

    /** 下载导出文件（仅本人或管理员；校验状态与过期时间） */
    ExportDownload download(Long taskId);
}
