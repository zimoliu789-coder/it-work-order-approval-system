-- =====================================================================
-- ：站内消息通知中心 + 附件上传通用能力
-- V10__phase8_message_attachment.sql
-- 企业内部设备借用工单系统
-- 规范依据：
--   第 24 章  站内消息通知系统（消息表字段、触发场景、预留扩展）
--   第 25 章  附件上传通用能力（存 NAS 本地磁盘、DB 存路径/文件名/大小/业务类型/业务ID，
--             禁止大文件入库；下载后端鉴权；下载记操作日志；不得暴露 NAS 静态地址）
--   第 21 章  API 规范（统一响应与分页）
-- 说明：
--   1) 本迁移**不修改任何既有列的定义**，只做增量建表 + 加索引，向后兼容；
--   2) 附件落盘走本地磁盘（开发＝项目内 data/attachments，生产＝NAS 挂载点，
--      由 app.attachment.storage-root 配置），数据库只存**相对路径**，
--      便于 NAS 迁移时不改库（：禁止把大文件存入 MySQL）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 一、attachments —— 附件表（）
--
-- 关联业务类型（biz_type）四类，与 逐条对应：
--   APPLY_ATTACHMENT   申请附件         biz_id = orders.id
--   REJECT_ATTACHMENT  驳回附件         biz_id = orders.id
--   RETURN_PHOTO       归还照片         biz_id = orders.id
--   FAULT_PHOTO        故障照片         biz_id = device_fault.id
--
-- 为什么 biz_id 不建外键：
--   它是**多态外键**（工单表或故障表），单列外键无法同时指向两张表；
--   强制拆成两张关联表会让「按业务查附件」退化为多次 union，
--   而附件的完整性由**业务侧删除策略**保证（业务记录不物理删除，附件随软删隐藏）。
--
-- stored_path 存「相对存储根目录」：
--   绝对路径一旦落库，NAS 换挂载点 / 迁移磁盘就要全表刷数据；相对路径只换配置即可。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `attachments` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `biz_type`     VARCHAR(32)  NOT NULL                            COMMENT '关联业务类型：APPLY_ATTACHMENT 申请附件 / REJECT_ATTACHMENT 驳回附件 / RETURN_PHOTO 归还照片 / FAULT_PHOTO 故障照片',
  `biz_id`       BIGINT       NOT NULL                            COMMENT '关联业务主键：工单 orders.id 或故障记录 device_fault.id（多态，不建外键）',
  `file_name`    VARCHAR(255) NOT NULL                            COMMENT '原始文件名（仅用于展示与下载命名，落盘用随机名）',
  `stored_path`  VARCHAR(512) NOT NULL                            COMMENT '相对 app.attachment.storage-root 的相对路径（形如 2026/09/uuid.png）',
  `file_size`    BIGINT       NOT NULL                            COMMENT '文件大小（字节）',
  `content_type` VARCHAR(128)          DEFAULT NULL               COMMENT 'MIME 类型',
  `uploader_id`  BIGINT       NOT NULL                            COMMENT '上传人 user_id',
  `deleted`      TINYINT(1)   NOT NULL DEFAULT 0                  COMMENT '逻辑删除：0正常 1已删除（附件列表按此过滤）',
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '上传时间',
  PRIMARY KEY (`id`),
  KEY `idx_att_biz`      (`biz_type`, `biz_id`, `deleted`, `id`),
  KEY `idx_att_uploader` (`uploader_id`, `created_at`),
  CONSTRAINT `fk_att_uploader` FOREIGN KEY (`uploader_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '附件表（ 附件上传通用能力）';

-- ---------------------------------------------------------------------
-- 二、messages 补充索引 —— 消息中心「按类型筛选」用（）
--
-- 已有 idx_messages_user_read(user_id, is_read, id) 覆盖「我的未读/已读」；
-- 消息中心还需「某用户的某类型消息」这一查询形态，补一条覆盖索引避免回表放大。
-- ---------------------------------------------------------------------
CREATE INDEX `idx_messages_user_type` ON `messages` (`user_id`, `message_type`, `id`);
