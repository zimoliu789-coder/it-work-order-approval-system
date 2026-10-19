-- V55：同组 / 跨组转单申请（D5.7 三态流转）
--
-- 与既有 order_handler_transfer（转交**历史**）的分工：
--   · order_handler_transfer —— 已经发生的事实，只追加不修改（"谁转给了谁"）；
--   · order_transfer_request —— **申请单**，有状态（待接收 / 已接收分配 / 拒绝退回），
--     会被后续动作改写（accept / reject）。
-- 两者不能合并：一笔工单可以先被拒绝两次、第三次才成功，历史表只该记"最终成功的那次"，
-- 而申请单必须留下每一次被拒绝的过程（否则「为什么这单在组里挂了三天」无法解释）。
CREATE TABLE `order_transfer_request`
(
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `order_id`           BIGINT       NOT NULL COMMENT '工单 id',
    `transfer_scope`     VARCHAR(16)  NOT NULL COMMENT 'SAME_GROUP 同组转单 / CROSS_GROUP 跨组转单',
    `from_department_id` BIGINT       NULL COMMENT '原处理小组 id（快照，原组被删也读得出来）',
    `to_department_id`   BIGINT       NULL COMMENT '目标处理小组 id；同组转单时等于 from_department_id',
    `from_handler_id`    BIGINT       NULL COMMENT '原实际执行人（生效时用它做条件 UPDATE，防并发覆盖）',
    `to_handler_id`      BIGINT       NULL COMMENT '目标执行人；跨组转单在「接收并分配」时写入',
    `to_admin_id`        BIGINT       NULL COMMENT '待处理人：同组=本组管理员（审批人），跨组=目标组管理员（接收人）',
    `status`             VARCHAR(16)  NOT NULL COMMENT 'PENDING 待接收 / ACCEPTED 已接收分配 / REJECTED 拒绝退回 / CANCELLED 已作废',
    `reason`             VARCHAR(500) NOT NULL COMMENT '转单原因（必填，审计要素）',
    `operator_id`        BIGINT       NOT NULL COMMENT '发起人',
    `decide_by`          BIGINT       NULL COMMENT '处理人（审批 / 接收 / 拒绝的人）',
    `decide_at`          DATETIME     NULL COMMENT '处理时间',
    `decide_comment`     VARCHAR(500) NULL COMMENT '处理意见（拒绝时建议填写）',
    `created_at`         DATETIME     NOT NULL,
    `updated_at`         DATETIME     NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_otr_order` (`order_id`, `status`),
    KEY `idx_otr_admin` (`to_admin_id`, `status`),
    CONSTRAINT `fk_otr_order` FOREIGN KEY (`order_id`) REFERENCES `borrow_order` (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='工单转单申请（同组 / 跨组，三态流转）';
