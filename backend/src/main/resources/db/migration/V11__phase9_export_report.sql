-- =====================================================================
-- ：Excel 导入导出 + 统计报表
-- V11__phase9_export_report.sql
-- 企业内部设备借用工单系统
-- 规范依据：
--   第 26 章  Excel 导入导出与统计报表
--     26.1 设备台账导出 / 工单记录导出 / 超过 10000 条异步生成 + 站内消息通知下载 /
--          所有导入导出记审计日志
--     26.2 统计报表（仅 super_admin、admin 可见）：设备借用频次、工单审批时效、设备故障；
--          按年月筛选，支持导出 Excel
--   第 21 章  API 规范
-- 说明：
--   本迁移**不修改任何既有列定义**，只做增量建表 + 加索引，向后兼容。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 一、export_tasks —— 导出任务表（「超过 10000 条时异步生成」）
--
-- 为什么导出也要落一张表，而不是「查完直接写响应」：
--   规范要求大结果集异步生成、完成后用站内消息通知下载。异步意味着「用户请求」
--   与「文件产出」在时间上分离，必须有一个持久化的载体记录任务状态，否则：
--     · 用户刷新页面后就再也找不到自己提交的导出；
--     · 同时导出的人数不可观测，出问题无法排查；
--     · 文件在磁盘上没有归属，无法做权限校验与过期清理。
--   因此无论同步（≤阈值）还是异步（>阈值）都写一行：同步的那行当场置 SUCCESS，
--   异步的先行 PENDING，由独立线程池跑完回填 —— 状态机只有一条路径，前端也只需
--   处理「拿到 downloadUrl」与「等消息通知」两种情况。
--
-- stored_path 存「相对 app.export.storage-root 的相对路径」（与附件同策略）：
--   绝对路径一旦落库，NAS / 磁盘换挂载点就要全表刷数据。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `export_tasks` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `export_type`   VARCHAR(32)  NOT NULL                            COMMENT '导出类型：DEVICE 设备台账 / ORDER 工单记录 / REPORT_DEVICE_USAGE 借用频次报表 / REPORT_APPROVAL_EFFICIENCY 审批时效报表 / REPORT_DEVICE_FAULT 故障统计报表',
  `query_json`    VARCHAR(2000)         DEFAULT NULL               COMMENT '导出时的筛选条件快照（JSON），用于复现与排查',
  `requester_id`  BIGINT       NOT NULL                            COMMENT '发起人 user_id',
  `status`        VARCHAR(16)  NOT NULL DEFAULT 'PENDING'          COMMENT '任务状态：PENDING 待生成 / RUNNING 生成中 / SUCCESS 已完成 / FAILED 生成失败',
  `file_name`     VARCHAR(255)          DEFAULT NULL               COMMENT '下载时的文件名（含 .xlsx）',
  `stored_path`   VARCHAR(512)          DEFAULT NULL               COMMENT '相对 app.export.storage-root 的相对路径（形如 2026/09/uuid.xlsx）',
  `file_size`     BIGINT                DEFAULT NULL               COMMENT '文件大小（字节）',
  `total_rows`    INT                   DEFAULT NULL               COMMENT '导出的数据行数（不含表头）',
  `error_message` VARCHAR(500)          DEFAULT NULL               COMMENT '失败原因（仅 FAILED 时有值，供排查）',
  `expire_at`     DATETIME              DEFAULT NULL               COMMENT '文件过期时间（过期后下载返回明确错误码，磁盘由清理任务回收）',
  `finished_at`   DATETIME              DEFAULT NULL               COMMENT '生成完成时间',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_export_requester`     (`requester_id`, `status`, `id`),
  KEY `idx_export_status_created` (`status`, `created_at`),
  KEY `idx_export_expire`        (`expire_at`),
  CONSTRAINT `fk_export_requester` FOREIGN KEY (`requester_id`) REFERENCES `users` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '导出任务表（ 异步导出与下载审计）';

-- ---------------------------------------------------------------------
-- 二、统计报表所需索引（）
--
-- 三张报表的查询形态：
--   ① 设备借用频次  → orders 按「时间区间 + 设备」GROUP BY
--                     已有 idx_orders_device_status(device_id,status) 覆盖按设备；
--                     但「先按时间区间过滤再分组」缺少 created_at 的前缀索引，
--                     补 idx_orders_created_at 让时间条件走索引而不是全表扫。
--   ② 工单审批时效  → order_approval_nodes 取 APPROVED 且 action_time 非空，
--                     按 (order_id, step_order) 算节点耗时。已有 idx_oan_order 覆盖
--                     按单聚合；跨全表的「按时间区间圈定已完成审批」需要 (status, action_time)，
--                     补 idx_oan_status_action。
--   ③ 设备故障统计  → device_fault 已有 idx_fault_occurred(occurred_at) 与
--                     idx_fault_device_status(device_id,status)，无需新增。
--
-- 索引名刻意与既有迁移不重名；时间列均建在「区间过滤」这一最常用谓词上。
-- ---------------------------------------------------------------------
CREATE INDEX `idx_orders_created_at` ON `orders` (`created_at`);
CREATE INDEX `idx_oan_status_action` ON `order_approval_nodes` (`status`, `action_time`);
