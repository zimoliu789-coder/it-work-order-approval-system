-- ============================================================================
--  —— 审批流程简化（需求文档 四·）
-- ----------------------------------------------------------------------------
-- 本脚本做两件事：
--   ① 给设备台账加「设备金额」列 —— 金额分档审批的唯一依据；
--   ② 登记系统参数 approval_device_amount_threshold（默认 5000 元）。
--
-- 关于 ① 为什么金额放在设备表而不是借用单上：
--   已明确「金额（自动带）」，即金额不是员工可填字段，它的归属只能是资产本身。
--   放在 orders 上会造成「同一台设备被不同人借用时金额可能不一致」这种自相矛盾的数据。
--
-- 幂等性：
--   · ADD COLUMN 以 information_schema 判定包裹，重复执行不会报
--     「Duplicate column name 'amount'」；
--   · INSERT 用 SELECT ... WHERE NOT EXISTS 形式，重复执行不会撞唯一键。
--
-- 存量数据：现有设备金额一律留 NULL，**不做兜底赋值**。
--   需求口径是「金额为空按 ≤ 阈值处理」（见 BorrowFieldCatalog#DEVICE_AMOUNT 的注释），
--   因此 NULL 是一个**有语义的值**（"未录入 → 走三级"），不是需要被填平的缺口。
--   管理员后续在台账里逐台补录即可，补录一台生效一台。
-- ============================================================================

-- ① 设备金额（元），选填
SET @add_amount = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'device' AND column_name = 'amount'),
    'SELECT 1',
    'ALTER TABLE `device`
       ADD COLUMN `amount` DECIMAL(12,2) NULL DEFAULT NULL
       COMMENT ''设备金额（元），选填；用于借用审批的金额分档（）'' AFTER `remark`');
PREPARE st FROM @add_amount; EXECUTE st; DEALLOCATE PREPARE st;

-- ② 设备金额审批阈值（元）
INSERT INTO `system_config` (`config_key`, `config_value`, `config_group`, `config_desc`, `editable`)
SELECT 'approval_device_amount_threshold', '5000', 'approval',
       '设备金额审批阈值（元）：超过此值时借用审批加一级「上级部门主管」', 1
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `system_config`
                    WHERE `config_key` = 'approval_device_amount_threshold');
