-- =====================================================================
-- 回滚 V53 · 处理小组按一级分部绑定
--
-- ⚠️ 执行前务必先导出绑定关系，回滚会**丢弃**管理员的配置：
--     SELECT * FROM division_handler_group;
--
-- 顺序：先删表（依赖 department 的外键），再删列。
-- =====================================================================

DROP TABLE IF EXISTS `division_handler_group`;

-- handler_default 是 V53 新增的解析兜底列；回滚后一律走
-- 「handler_group = 1 里 id 最小的那个」（V53 之前的行为）。
ALTER TABLE `department` DROP COLUMN IF EXISTS `handler_default`;
