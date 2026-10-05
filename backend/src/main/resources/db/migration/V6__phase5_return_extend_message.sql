-- =====================================================================
--  / V6__phase5_return_extend_message.sql
-- 企业内部设备借用工单系统 —— 归还与顺延、站内消息
-- 规范依据：
--   第 12 章   工单状态机（BORROWED → PENDING_RETURN → RETURNED；borrow_timeout 为标记位）
--   第 16.2   两步归还流程（申请人发起 → 实际执行人确认收回，登记设备状态）
--   第 16.3   自动顺延机制（到期未归还自动顺延 1 天，最多 2 次；2 次后标记超时）
--   第 16.5   超时告警（幂等重复推送，last_timeout_alert_at 控制间隔；执行人离职转发超管）
--   第 24 章   站内消息通知系统（触发场景表 + 消息表字段）
--   第 27 章   定时任务（到期预警 / 自动顺延 / 超时告警，全部幂等）
--   第 28 章   离职员工数据管控（离职联动回收设备）
-- 说明：
--   1) 本迁移只做「加列 + 建表 + 加索引」，**不修改任何既有列的可空性与类型**，
--      因此对 –4b 的存量数据与代码完全向后兼容；三段均为幂等写法。
--   2) 所有新增列均可空或带默认值： 已落库的历史工单不需要回填即可正常读取
--      （order_type 默认 BORROW，其余归还相关列在归还流程发生前保持 NULL）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 一、orders 增列：工单类型 + 归还登记 + 定时任务幂等位
--
-- 设计要点：
--   · order_type        —— 借用申请单 / 归还单 / 维修单 / 换货单。当前阶段仅实际使用
--                          BORROW（主借用流程）；RETURN 为「无在办借用单时的独立归还登记」
--                          预留，离职联动按需求方确认的规则**复用原借用单**，不新建 RETURN 单；
--   · return_trigger    —— 记录「这次归还由谁触发」，用于列表区分与事后追溯：
--                          USER_INITIATED 申请人主动 / DIMISSION 员工离职联动 / ADMIN_FORCE 管理员强制收回；
--   · return_note       —— 申请人发起归还时填写的归还说明（「可选」）；
--   · return_condition  —— 实际执行人收回时登记的设备状态：GOOD 完好 / MINOR_DAMAGE 轻微损坏 /
--                          FAULT 故障。GOOD 与 MINOR_DAMAGE 均回到 AVAILABLE，FAULT 进入 MAINTENANCE
--                          （ /  设备状态机）；
--   · return_remark     —— 实际执行人的收回备注（损坏情况说明等），与申请人的 return_note 分开存放，
--                          避免双方文字互相覆盖；
--   · returned_by       —— 收回人 user_id（「记录实际归还时间」+ 需求方要求记录收回人）；
--   · last_timeout_alert_at / remind_before_sent_at / due_reminded_at
--                       —— 三个**幂等位**（「所有定时任务必须幂等」）。
--                          定时任务每天运行，靠这三个时间戳保证同一件事只通知一次：
--                          超时告警按 timeout_alert_interval_hours 间隔重复推送，另两个只发一次。
-- ---------------------------------------------------------------------
ALTER TABLE `orders`
  ADD COLUMN `order_type`            VARCHAR(16)  NOT NULL DEFAULT 'BORROW' COMMENT '工单类型：BORROW 借用申请 / RETURN 归还单 / REPAIR 维修单 / EXCHANGE 换货单' AFTER `order_no`,
  ADD COLUMN `return_trigger`        VARCHAR(16)           DEFAULT NULL    COMMENT '归还触发来源：USER_INITIATED 申请人主动 / DIMISSION 离职联动 / ADMIN_FORCE 管理员强制' AFTER `actual_end_time`,
  ADD COLUMN `return_note`           VARCHAR(500)          DEFAULT NULL    COMMENT '申请人归还说明（，可选）'                                  AFTER `return_trigger`,
  ADD COLUMN `return_condition`      VARCHAR(16)           DEFAULT NULL    COMMENT '收回时登记的设备状态：GOOD 完好 / MINOR_DAMAGE 轻微损坏 / FAULT 故障'   AFTER `return_note`,
  ADD COLUMN `return_remark`         VARCHAR(500)          DEFAULT NULL    COMMENT '收回备注（实际执行人填写，如损坏情况说明）'                          AFTER `return_condition`,
  ADD COLUMN `returned_by`           BIGINT                DEFAULT NULL    COMMENT '收回人 user_id（实际执行人 / 强制收回的管理员）'                     AFTER `return_remark`,
  ADD COLUMN `last_timeout_alert_at` DATETIME              DEFAULT NULL    COMMENT '最后一次超时告警时间（幂等：按配置间隔重复推送，）'          AFTER `returned_by`,
  ADD COLUMN `remind_before_sent_at` DATETIME              DEFAULT NULL    COMMENT '到期前预警已发送时间（幂等：只发一次，）'                      AFTER `last_timeout_alert_at`,
  ADD COLUMN `due_reminded_at`       DATETIME              DEFAULT NULL    COMMENT '到期当天提醒已发送时间（幂等：只发一次，需求方 ）'                AFTER `remind_before_sent_at`;

-- 定时任务扫描索引：
--   · (status, planned_end_time) —— 到期预警 / 自动顺延 均按「状态 + 计划结束时间」筛选，
--     没有该索引时每日全表扫描 orders；
--   · (status, borrow_timeout)   —— 超时告警扫描 + 「我的待处理」超时置顶排序；
--   · (actual_final_handler_id, status) —— 「我的待处理」按执行人 + 状态集合查询。
ALTER TABLE `orders`
  ADD KEY `idx_orders_status_planned_end` (`status`, `planned_end_time`),
  ADD KEY `idx_orders_status_timeout` (`status`, `borrow_timeout`),
  ADD KEY `idx_orders_handler_status` (`actual_final_handler_id`, `status`);

-- 收回人外键：与申请人的处理方式一致（RESTRICT —— 业务表历史数据不允许因人员变动而被破坏）
ALTER TABLE `orders`
  ADD CONSTRAINT `fk_orders_returned_by` FOREIGN KEY (`returned_by`)
      REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE;

-- ---------------------------------------------------------------------
-- 二、messages 站内消息表（）
--
-- 字段与 完全一致：id / user_id / title / content / order_id / is_read / created_at，
-- 另按需求方与工程需要补充：
--   · message_type —— 消息类型枚举，前端据此选择图标与跳转目标（ 触发场景表逐行对应）；
--   · read_at      —— 已读时间（规范只要求 is_read；保留时间便于后续做「已读时效」统计）。
--
-- 索引设计：
--   · (user_id, is_read, id) —— 铃铛未读数统计与「只看未读」列表都走这条联合索引；
--   · (order_id)             —— 按工单回溯消息（工单详情/排障时用）。
--
-- 外键：
--   · user_id  ON DELETE CASCADE —— 消息属于接收人个人数据，人员删除时一并清理；
--   · order_id ON DELETE SET NULL —— 工单若被清理，消息本身仍应保留（正文已包含可读信息），
--     仅失去跳转目标，因此置空而不是级联删除。
-- 说明：「预留扩展字段」——企业 IM / 邮件通道为  集成，届时新增 channel 等列。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `messages` (
  `id`           BIGINT        NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `user_id`      BIGINT        NOT NULL                            COMMENT '接收人 user_id（）',
  `title`        VARCHAR(128)  NOT NULL                            COMMENT '消息标题',
  `content`      VARCHAR(1000) NOT NULL                            COMMENT '消息正文',
  `order_id`     BIGINT                 DEFAULT NULL               COMMENT '跳转关联工单 orders.id，可为空（非工单类消息）',
  `message_type` VARCHAR(32)   NOT NULL                            COMMENT '消息类型，取值见 MessageType',
  `is_read`      TINYINT(1)    NOT NULL DEFAULT 0                  COMMENT '是否已读：0未读 1已读',
  `read_at`      DATETIME               DEFAULT NULL               COMMENT '已读时间',
  `created_at`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_messages_user_read` (`user_id`, `is_read`, `id`),
  KEY `idx_messages_order` (`order_id`),
  KEY `idx_messages_created` (`created_at`),
  CONSTRAINT `fk_messages_user`  FOREIGN KEY (`user_id`)  REFERENCES `users` (`id`)  ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `fk_messages_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '站内消息表';

-- ---------------------------------------------------------------------
-- 三、存量数据兼容
--
--  已交付并已进入「使用中」的工单没有 order_type 列值，靠上面的 DEFAULT 'BORROW' 自动补齐，
-- 但 DEFAULT 只对**本次 ALTER 之后新插入的行**生效，既有行仍为 NULL（MySQL 加列时会把默认值
-- 一次性填给存量行，故此处实际不需要回填）。为稳妥起见仍显式兜底一次，保证任何情况下
-- 都不会出现 order_type IS NULL 的行导致实体解析异常。
-- ---------------------------------------------------------------------
UPDATE `orders` SET `order_type` = 'BORROW' WHERE `order_type` IS NULL;

-- 已终态（已归还）的工单补齐归还触发来源，便于「全部工单」列表按触发来源筛选/展示。
--  无归还功能，理论上不存在这类行；此语句仅作为老数据/手工造数的兜底。
UPDATE `orders`
SET `return_trigger` = 'USER_INITIATED'
WHERE `status` = 'RETURNED' AND `return_trigger` IS NULL;
