-- =====================================================================
-- V53 · 处理小组按「一级分部」绑定 + 逐级向上匹配（D5 / D6）
-- 企业内部设备借用工单系统
--
-- 背景：改造前「最终处理部门」是**全局唯一**的（departments.handler_group = 1，
--       V31 迁移甚至用 SELECT COUNT(*) 硬校验「有且仅有 1 个」），
--       OrderServiceImpl 取它以 LIMIT 1 兜底。
--       后果是：北京分部的单子也必然落到上海那个唯一的 IT 运维组 ——
--       分配错误是**确定性发生**的，不是偶发。
--
-- ---------------------------------------------------------------------
-- 设计要点（为什么这样做）
-- ---------------------------------------------------------------------
--  A. **不新建「分部」实体**（用户裁决 D6=A）：分部就是部门树的**一级节点**
--     （depth = 1，parent_id = 根节点 id）。因此绑定关系只需要一张
--     「分部 → 处理小组」的关联表，不引入新概念。
--
--  B. **处理小组仍用 `department.handler_group = 1` 标记**，允许**多个**。
--     V31 里那条「恰好 1 个」的校验是**历史迁移里的断言**，
--     按项目硬约定「历史迁移不可改（连注释）」→ 保持原样。
--     运行期不再依赖它：新逻辑一律走本表的绑定解析。
--
--  C. **全局默认小组用新列 handler_default 表达**，而不是「没被绑定的那个」。
--     原因：后者在「有 2 个组、只绑了 1 个分部」时是**有歧义**的 ——
--     剩下那个是默认组，还是另一个分部的组只是还没配？
--     显式标记 + 唯一性由服务层保证（应用层校验「最多 1 个」），语义无歧义。
--
--  D. **UNIQUE(division_id)**：一个分部只能绑一个处理小组。
--     反过来一个处理小组可以服务多个分部（现实中「一个大区 IT 组管 3 个分公司」很常见）
--     —— 所以不在 handler_department_id 上加唯一约束。
--
--  E. **存量数据零行为变化**：把当前唯一那个 handler_group = 1 的部门
--     标成全局默认 ⇒ 升级后「找不到绑定」的单子仍然落到它，
--     与升级前逐字一致（零回归）。绑定关系留空，由管理员按需配置。
--
-- 回滚：见 deploy/scripts/rollback/V53__rollback_division_handler_group.sql
--       （删表 + 删列；已配置的绑定关系会随之丢失，回滚前请先导出该表）
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 全局默认处理小组标记
-- ---------------------------------------------------------------------
-- 为什么单独一列而不是复用 handler_group：
--   handler_group 回答「这个部门是不是处理小组」（可以有多个），
--   handler_default 回答「找不到绑定/分部时用哪个」（最多 1 个）。
--   两个问题维度不同，挤在一列里会立刻出现「默认组被解绑后标记跟着丢」这类问题。
ALTER TABLE `department`
    ADD COLUMN `handler_default` TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '是否全局默认处理小组：1 是（最多 1 个，解析兜底用）';

-- 存量：当前唯一的处理小组 = 升级前的那个「全局最终处理部门」，标为默认。
-- 用 ORDER BY + LIMIT 1 保证即使历史上有多行异常数据也只标 1 行。
UPDATE `department` SET `handler_default` = 1
 WHERE `handler_group` = 1
 ORDER BY `id` ASC
 LIMIT 1;

-- ---------------------------------------------------------------------
-- 2. 分部 ↔ 处理小组 绑定
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `division_handler_group` (
    `id`                    BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `division_id`           BIGINT   NOT NULL COMMENT '一级分部 id（department.depth = 1）',
    `handler_department_id` BIGINT   NOT NULL COMMENT '处理小组 id（department.handler_group = 1）',
    `created_at`            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                                     ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    -- 一个分部只能绑一个处理小组（反过来一个组可服务多个分部）
    UNIQUE KEY `uk_division` (`division_id`),
    KEY `idx_handler_department` (`handler_department_id`),
    CONSTRAINT `fk_dhg_division` FOREIGN KEY (`division_id`)
        REFERENCES `department` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
    -- 处理小组被删时 RESTRICT：绑定关系不能静默消失，必须先解绑
    CONSTRAINT `fk_dhg_handler` FOREIGN KEY (`handler_department_id`)
        REFERENCES `department` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '一级分部 ↔ 处理小组绑定（逐级匹配的事实源）';
