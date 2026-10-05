-- =====================================================================
--  / V4__phase3_device.sql
-- 企业内部设备借用工单系统 —— 设备分类与设备台账
-- 规范依据：
--   第 8 章    设备分类与设备台账（一级 + 二级分类、资产编号全局唯一、软删除）
--   第 9 章    设备状态机（AVAILABLE / LOCKED / IN_APPROVAL / BORROWED / MAINTENANCE / SCRAPPED）
--   第 20 章   操作日志（设备删除、报废属高风险，必须同步留痕）
--   第 21 章   统一错误码（DEVICE_UNAVAILABLE / DEVICE_IN_BORROWED 等）
-- 说明：
--   1) 设备分类支持两级（），用自引用 parent_id 表达；根分类 parent_id = 0。
--      用 0 而非 NULL 表示根，是为了让唯一索引 (parent_id, category_name) 在两级都能生效
--      （MySQL 唯一索引不约束 NULL，若用 NULL 则同级重名只能靠应用层拦截）。
--      因此不对 parent_id 建外键（0 不是合法的分类主键），层级与父分类存在性由服务层校验。
--   2) 设备台账采用软删除（deleted）。资产编号 asset_no 要求「全局唯一」，
--      故唯一索引覆盖全部行（含已软删除行）—— 历史设备的资产编号不可被新设备复用。
--   3) 设备报废（SCRAPPED）与软删除（deleted）是两个概念（）：
--      报废是资产生命周期终结（状态字段），软删除是台账记录逻辑隐藏（deleted 字段）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- device_category 设备分类表（：一级分类 + 二级分类）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `device_category` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '主键',
  `category_name` VARCHAR(64)  NOT NULL                            COMMENT '分类名称',
  `parent_id`     BIGINT       NOT NULL DEFAULT 0                  COMMENT '父分类ID；0 表示一级分类（根）',
  `level`         TINYINT      NOT NULL DEFAULT 1                  COMMENT '层级：1 一级分类 / 2 二级分类（ 仅支持两级）',
  `sort_order`    INT          NOT NULL DEFAULT 0                  COMMENT '同级显示顺序',
  `remark`        VARCHAR(255)          DEFAULT NULL               COMMENT '备注',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_device_category_parent_name` (`parent_id`, `category_name`),
  KEY `idx_device_category_parent` (`parent_id`),
  KEY `idx_device_category_sort` (`sort_order`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '设备分类表（两级）';

-- ---------------------------------------------------------------------
-- device 设备台账表（ / ）
--   状态机六态由 status 承载； 仅落地「台账管理 + 管理员手动状态操作」，
--   LOCKED 临时锁、IN_APPROVAL / BORROWED 流转由  起的借用工单模块驱动。
--   临时锁字段（locked_by / locked_at / lock_token）属 ，本阶段不建，避免空列。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `device` (
  `id`                    BIGINT       NOT NULL AUTO_INCREMENT              COMMENT '主键 device_id，不可变',
  `device_name`           VARCHAR(128) NOT NULL                            COMMENT '设备名称',
  `asset_no`              VARCHAR(64)  NOT NULL                            COMMENT '资产编号，全局唯一（含已软删除记录，不可复用）',
  `primary_category_id`   BIGINT       NOT NULL                            COMMENT '一级分类ID，必须指向 level=1 的分类',
  `secondary_category_id` BIGINT                DEFAULT NULL               COMMENT '二级分类ID，可空（：二级分类可选）；非空时必须归属所选一级分类',
  `brand`                 VARCHAR(64)           DEFAULT NULL               COMMENT '品牌',
  `model`                 VARCHAR(128)          DEFAULT NULL               COMMENT '型号',
  `serial_no`             VARCHAR(128)          DEFAULT NULL               COMMENT '序列号',
  `storage_location`      VARCHAR(128)          DEFAULT NULL               COMMENT '存放位置',
  `purchase_date`         DATE                  DEFAULT NULL               COMMENT '购置日期',
  `status`                VARCHAR(32)  NOT NULL DEFAULT 'AVAILABLE'        COMMENT '设备状态（）：AVAILABLE/LOCKED/IN_APPROVAL/BORROWED/MAINTENANCE/SCRAPPED',
  `remark`                VARCHAR(500)          DEFAULT NULL               COMMENT '备注',
  `deleted`               TINYINT(1)   NOT NULL DEFAULT 0                  COMMENT '软删除标记：0正常 1已删除（不允许物理删除）',
  `created_at`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP  COMMENT '创建时间',
  `updated_at`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_device_asset_no` (`asset_no`),
  KEY `idx_device_status` (`status`),
  KEY `idx_device_primary_category` (`primary_category_id`),
  KEY `idx_device_secondary_category` (`secondary_category_id`),
  KEY `idx_device_deleted` (`deleted`),
  CONSTRAINT `fk_device_primary_category`
    FOREIGN KEY (`primary_category_id`) REFERENCES `device_category` (`id`)
    ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT `fk_device_secondary_category`
    FOREIGN KEY (`secondary_category_id`) REFERENCES `device_category` (`id`)
    ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '设备台账表';
