-- ============================================================================
-- P0 —— 数据库自动备份 + 结构化归还检查
-- V39__p0_backup_and_return_check.sql
-- 企业内部设备借用工单系统
-- ----------------------------------------------------------------------------
-- 本脚本承载 P0 的全部 DDL + 数据迁移 + 参数播种：
--   ① 新建 `backup_record`  —— 每次备份的执行流水（时间 / 文件 / 大小 / 状态 / 失败原因）；
--   ② 播种 4 个备份类系统参数（开关 / 备份时刻 / 保留天数 / 备份目录）；
--   ③ 迁移 `orders.return_condition` 的历史取值到新枚举
--      （FAULT → DAMAGED、MINOR_DAMAGE → GOOD）。
--
-- ----------------------------------------------------------------------------
-- ⚠️ 权限码 `backup:view` / `backup:manage` 刻意**不在本脚本里写授权行**。
--    与 V38 的 `ha:view` / `ha:manage` 同一姿势：两者都**不进 `DEFAULT_PERMISSIONS`**，
--    因此既不会被播种给任何非超管角色，也就不需要授权迁移；
--    而超管走 `PermissionGuard#has()` 的短路放行，本来就与授权行无关。
--    ⇒ 「仅超管可见」是靠**代码目录 + 守卫**两处保证的，不是靠数据行。
--    （反向提醒：将来若要把备份记录开放给 admin，必须补一条幂等授予迁移，
--      否则只对新库生效、存量库静默少权 —— 见 PROJECT_NOTES 的「权限默认集合的坑」。）
-- ============================================================================

-- ----------------------------------------------------------------------------
-- ① `backup_record` —— 备份执行流水
-- ----------------------------------------------------------------------------
-- 设计要点（每条都对应一类真实的「备份其实没跑」事故）：
--
--  A. **为什么要有 `status` 而不只是「有文件就说明成功」**。
--     备份的失败形态很多是「跑了一半」：mysqldump 中途报错、磁盘写满、目录不可写。
--     只看文件在不在，会把这些当成成功。`RUNNING → SUCCESS / FAILED` 三段式让
--     「进行中」与「已失败」在页面上分得开，也才能对残留的 RUNNING 做僵尸回收。
--
--  B. **为什么 `error_message` 要独立成列而不是写进 `created_at` 旁边的备注**。
--     它是页面上的核心判据：维护人员在「备份记录」页第一眼要看到的就是失败原因。
--     截断到前 8 行由服务层负责（mysqldump 的报错可能很长，整段塞进来反而看不清重点）。
--
--  C. **`file_path` 与 `file_name` 分开**。
--     `file_name` 是给人看的（列表展示、排错时去目录里找），
--     `file_path` 是给程序用的（保留清理要删它、将来下载要读它）。
--     目录可配（NAS 挂载点），所以路径必须落库，不能在展示时再拼。
--
--  D. **`file_size` 用 BIGINT 且默认 0**。
--     0 的确切语义是「本次未产出归档」（失败），而不是「空文件」——
--     生产库全量导出在几十 MB 量级，INT 虽够用，但备份文件大小是最不该省的地方。
--
--  E. **`operator_id` 可空**：定时备份没有操作人；手动触发才有。
--     不为了「列对齐」而把定时备份写成 0 或 1 —— 那会让「谁点的备份」查不出来。
--
--  F. **索引只要两个**：`started_at`（列表按时间倒序 + 保留清理按时间过滤）
--     与 `status`（僵尸回收扫 RUNNING、并发保护判「是否已有任务在跑」）。
--     这两条查询都会随记录增长而变慢，而记录会**每天一条**长期累积。
-- ----------------------------------------------------------------------------
CREATE TABLE `backup_record`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `file_name`     VARCHAR(255) NOT NULL DEFAULT ''
        COMMENT '归档文件名（不含目录），失败时为空串',
    `file_path`     VARCHAR(512) NOT NULL DEFAULT ''
        COMMENT '归档绝对路径；保留清理按它删文件，故必须落库（目录可配置）',
    `file_size`     BIGINT       NOT NULL DEFAULT 0
        COMMENT '归档字节数；0 的确切语义是「本次未产出归档」，不是「空文件」',
    `status`        VARCHAR(16)  NOT NULL
        COMMENT 'RUNNING 进行中 / SUCCESS 成功 / FAILED 失败（三段式，见脚本头 A）',
    `trigger_type`  VARCHAR(16)  NOT NULL
        COMMENT 'SCHEDULED 定时自动 / MANUAL 手动触发（「今天是否已自动备份」按它判定）',
    `started_at`    DATETIME     NOT NULL
        COMMENT '开始时间（保留清理与列表排序的主判据）',
    `finished_at`   DATETIME     NULL
        COMMENT '结束时间；RUNNING 期间为 NULL',
    `duration_ms`   BIGINT       NOT NULL DEFAULT 0
        COMMENT '耗时毫秒；大库备份耗时可观测（规范未要求，但排障必需）',
    `error_message` VARCHAR(500) NULL
        COMMENT '失败原因（服务层截断到前 8 行）；成功为 NULL',
    `operator_id`   BIGINT       NULL
        COMMENT '手动触发的操作人；定时为 NULL（不写 0/1，否则「谁点的」查不出来）',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_backup_started` (`started_at`),
    KEY `idx_backup_status` (`status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  COMMENT = '数据库备份执行流水（P0）';


-- ----------------------------------------------------------------------------
-- ② 播种备份类系统参数（幂等）
-- ----------------------------------------------------------------------------
-- `config_group` 取 `maintenance`：该组现有 附件/导出 的清理阈值参数
-- （attachment_orphan_grace_hours / export_zombie_timeout_minutes …），
-- 与备份同属「维护类兜底配置」，归在一起维护人员找得到。
--
-- `backup_enabled` 默认 **0**，这是刻意的：
--   Docker 部署已有 `deploy/backup` 侧车负责每日全量（含附件与配置，三合一），
--   应用内备份若默认开启，同一份数据会存两处 —— 白占 NAS 空间与 IO，
--   且两条链路各自"成功"会让人分不清哪份才是权威归档。
--   ⇒ 能力型开关默认关闭，要用先显式打开（与 app.upgrade.enabled 同取向）。
--
-- `backup_dir` 默认空串：留空 ⇒ 回落应用配置 `app.backup.dir`（见 AppProperties）。
--   之所以不在迁移里写死一个绝对路径：不同部署的 NAS 挂载点不同，
--   写死会让「跑在别的机器上」直接失败，而失败原因看起来像权限问题。
--
-- 幂等写法：`INSERT ... SELECT ... FROM DUAL WHERE NOT EXISTS`
--   （`FROM DUAL` 不是装饰 —— MySQL 的「无表 SELECT」不允许直接跟 WHERE，缺了它解析阶段就报 1064）。
INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'backup_enabled', '0', 'maintenance',
       '应用内数据库自动备份总开关。默认关闭：Docker 部署请使用 deploy/backup 备份容器，两者不要同时开启。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'backup_enabled');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'backup_hour', '2', 'maintenance',
       '每天自动备份的时刻（0-23 整点）。若该时刻应用未运行，当天会在恢复后补跑一次。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'backup_hour');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'backup_retention_days', '30', 'maintenance',
       '备份保留天数，超期归档在【本次备份成功后】才会被清理（避免备份失败与删旧归档叠加）。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'backup_retention_days');

INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'backup_dir', '', 'maintenance',
       '备份文件存放目录（可指向 NAS 挂载点）。留空则使用应用配置 app.backup.dir。', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config` WHERE `config_key` = 'backup_dir');


-- ----------------------------------------------------------------------------
-- ③ 归还检查枚举的数据迁移
-- ----------------------------------------------------------------------------
-- 旧枚举：GOOD（完好）/ MINOR_DAMAGE（轻微损坏）/ FAULT（故障）
-- 新枚举：GOOD（完好）/ DAMAGED（损坏）/ MISSING_PARTS（缺配件）/ LOST（丢失）
--
-- 映射理由：
--   FAULT → DAMAGED            只是改名：语义完全一致（设备坏了，进维修中 + 建故障记录）。
--                              不改的话，历史 12 条记录在新枚举下会解析不出（of() 返回 null），
--                              工单详情页的「归还结果」会直接显示成裸编码 'FAULT'。
--   MINOR_DAMAGE → GOOD        旧语义是「轻微损坏但仍回可用」，与「完好」同分支（见旧 ReturnCondition javadoc）。
--                              新枚举取消了这一档，最近似的归并是 GOOD（都进 AVAILABLE）。
--                              实测本库 0 条，此语句是为其它环境兜底 —— 不写的话别的环境会留下裸编码。
--
-- ⚠️ 这两条是**幂等**的：`WHERE return_condition = 'FAULT'` 在第二次执行时命中 0 行。
--    与本项目「DELETE 不 TRUNCATE」「迁移必须可重入」的约定一致。
UPDATE `orders` SET `return_condition` = 'DAMAGED'
 WHERE `return_condition` = 'FAULT';

UPDATE `orders` SET `return_condition` = 'GOOD'
 WHERE `return_condition` = 'MINOR_DAMAGE';
