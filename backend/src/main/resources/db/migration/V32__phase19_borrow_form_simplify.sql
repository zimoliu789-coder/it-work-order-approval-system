-- ============================================================================
--  —— 申请表单简化（需求文档 三·）
-- ----------------------------------------------------------------------------
-- 表单只保留「借什么设备 / 还的日期 / 用途」+ 附件。
--
-- 本脚本做两件事：
--   ① **删除**「使用地点 use_place」与「申请备注 remark」两列。
--      二者已从表单砍掉，且全代码库**没有任何写入点**（use_place 只被读取过，
--      remark 早在本次之前就停止写入）—— 留着就是死列。
--   ② 「借用原因 reason」的列注释改为「用途」（**只改注释，列名与数据不动**）。
--
-- 关于删列的数据影响（执行前已备份，见 .docs/_p19cleanup-orders-backup.sql）：
--   · use_place：644 / 679 行有值   · remark：48 / 679 行有值
--   这两列是「退役字段」而非「业务关键字段」，删除后历史工单不再携带这两个信息。
--
-- 为什么不保留：项目标准是「旧字段既然不再采集，就不留『先留着以后再说』的死列」。
--   保留列会让后续每一次表结构阅读都要多问一句「这列还有人写吗」。
--
-- 幂等性：两处 DROP 均以 information_schema 判定包裹，重复执行不会报
--   「Can't DROP 'xxx'; check that column/key exists」。
-- ============================================================================

SET @drop_use_place = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'orders' AND column_name = 'use_place'),
    'ALTER TABLE `orders` DROP COLUMN `use_place`',
    'SELECT 1');
PREPARE st FROM @drop_use_place; EXECUTE st; DEALLOCATE PREPARE st;

SET @drop_remark = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'orders' AND column_name = 'remark'),
    'ALTER TABLE `orders` DROP COLUMN `remark`',
    'SELECT 1');
PREPARE st FROM @drop_remark; EXECUTE st; DEALLOCATE PREPARE st;

-- 用途（原「借用原因」）： 起改为选填
ALTER TABLE `orders`
  MODIFY COLUMN `reason` VARCHAR(500) NULL DEFAULT NULL
    COMMENT '用途（原「借用原因」； 起改为选填）';
