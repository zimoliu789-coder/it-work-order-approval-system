-- V55 回滚：删除转单申请表
--
-- ⚠️ 回滚会**丢失全部转单申请记录**（含已拒绝的历史）：
-- 已生效的转单结果不会回退 —— 执行人早已变更，order_handler_transfer 里也有对应历史，
-- 删掉申请单不会让执行人变回原样。若要把执行人改回去，请走「转交」功能人工操作。
DROP TABLE IF EXISTS `order_transfer_request`;
