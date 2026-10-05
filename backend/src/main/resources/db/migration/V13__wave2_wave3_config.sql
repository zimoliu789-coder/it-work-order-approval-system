-- =====================================================================
-- 三波遗漏补做 · 第二波 / 第三波 系统参数播种
-- V13__wave2_wave3_config.sql
-- 企业内部设备借用工单系统
--
-- 覆盖需求（需求方 2026-09-19 三波清单）：
--   第二波 5. 审批超时提醒定时任务   —— 复用既有 approval_timeout_remind_hours（V2 已播种）
--   第二波 6. 设备工单对账定时任务   —— 无可配参数（告警接收人由角色结构决定）
--   第三波 13. 附件孤儿文件清理      —— 新增 attachment_orphan_grace_hours
--   第三波 14. 导出过期清理 + 僵尸回收 —— 新增 export_zombie_timeout_minutes、export_orphan_grace_hours
--
-- 规范依据：
--   第 27 章 定时任务（可配置参数 + 幂等 + 异常隔离）
--   第 20 章 操作审计
--
-- 说明：
--   本次**不新增任何表 / 列**，只播种三个「维护类」参数，使它们出现在系统参数页并可按现场调整。
--   参数的业务含义、取值下限与「为什么必须有下限」见
--   com.enterprise.ticket.module.system.support.ConfigRules 中对应注释。
--   INSERT IGNORE 依赖 uk_system_config_key 唯一键，重复执行不报错（幂等）。
-- =====================================================================

INSERT IGNORE INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`) VALUES
-- 附件孤儿清理宽限期（小时）：只清理「最后修改时间早于此」的无引用文件，
-- 避免把「已落盘、尚未插库」的上传中文件误判为孤儿。
('attachment_orphan_grace_hours', '24', 'maintenance',
 '附件孤儿文件清理宽限期（小时）：无引用的磁盘文件超过该时长才清理，范围 1-720', 1),

-- 导出僵尸任务判定阈值（分钟）：PENDING / RUNNING 超过该时长未完成即置为失败。
-- 用于回收「应用重启导致卡死」的异步导出任务，让界面不再永远显示「生成中…」。
('export_zombie_timeout_minutes', '30', 'maintenance',
 '导出僵尸任务判定阈值（分钟）：异步导出超过该时长仍未完成即回收为失败，范围 5-1440', 1),

-- 导出孤儿文件宽限期（小时）：同附件，保护「文件已写、stored_path 尚未回填」的窗口。
('export_orphan_grace_hours', '12', 'maintenance',
 '导出孤儿文件清理宽限期（小时）：无引用的导出文件超过该时长才清理，范围 1-720', 1);
