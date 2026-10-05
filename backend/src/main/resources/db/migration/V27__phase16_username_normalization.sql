-- =====================================================================
--  · 上线前需求（九·姓名与登录名规则）：存量登录名规范化
-- V27__phase16_username_normalization.sql
-- 企业内部设备借用工单系统
--
-- 背景（为什么必须做数据迁移，而不是只加一条校验）：
--    规定「登录名必须是 5 位以上纯数字」。但库里**存量数据**的登录名
--   是历史形态，全部不是纯数字：
--     · 中文姓名形态：张伟 / 李娜 / 刘洋 / 王强 / 陈晨 / 赵敏 …（ 早期「姓名即账号」）
--     · 带前缀编号形态：emp0001 / st001 / reg120673 / impA120673 / 回归…（演示与回归夹具）
--   只加校验而不迁移，会得到「新员工必须纯数字、老员工还是中文」的双轨状态 ——
--   用户列表里两种风格混排，且「登录名不符合规则」这件事永远无法收敛。
--
-- ---------------------------------------------------------------------
-- 设计要点（为什么这样做）
-- ---------------------------------------------------------------------
--  A. **内置超级管理员 `administrator` 必须原样保留**。
--     它的登录名是「配置驱动 + 永不失效的兜底入口」：值来自
--     `app.super-admin.username`（默认 administrator），被 `BuiltinAdmin` 用于
--     「谁是根账号」的判定，且是 AD 域控接管时唯一被强制走本地认证的账号。
--     把它改成数字会同时破坏三件事：配置默认值、兜底入口的可读性、
--     以及「超管口令只归本人」这条护栏的锚点。也只要求「登录名不能是中文」，
--     纯字母的固定超管名属于约定内的例外。
--     代码侧 `UserServiceImpl#resolveByUsernameThenName` 也据此把两类非法形态
--     （administrator 与 AD 域账号名）纳入了「先按登录名精确匹配」的查询路径。
--
--  B. **已经是纯数字的登录名不动**（幂等护栏）。
--     迁移可能被重跑（库重建 / 手工修复），若不排除已有数字名，
--     它们会被重新编号、把「员工自己记住的账号」改掉。
--
--  C. **编号规则：按 id 升序，从 10001 开始，`LPAD(...,5,'0')` 补齐 5 位**。
--     取 10000 + 序号 而不是 1 开始：① 保证「至少 5 位」这条规则对首行也成立；
--     ② 避免与人工可能已经分配过的短数字（1、12、100）撞车 ——
--     撞车会让下面的 UPDATE 直接撞唯一索引失败（MySQL 报 1062）。
--
--  D. **排序规则必须显式对齐（本迁移的第一版就栽在这里）**。
--     现象：`UPDATE users u JOIN users_username_backup_v27 b ... WHERE u.username <> b.new_username`
--     抛 `Illegal mix of collations (utf8mb4_general_ci,IMPLICIT) and
--     (utf8mb4_0900_ai_ci,IMPLICIT) for operation '<>'`，整个迁移失败、应用起不来。
--     根因：`users` 是历史表，列排序规则为 **utf8mb4_general_ci**；
--     而 `CREATE TABLE ... DEFAULT CHARSET = utf8mb4` 在 MySQL 8 上会取服务端默认排序规则
--     **utf8mb4_0900_ai_ci**。两张表各自的列排序规则不同，做 `<>` / `=` 比较时
--     MySQL 无法自动归并，直接报错。
--     两条修正，缺一不可：
--       ① 备份表**显式**声明 `COLLATE=utf8mb4_general_ci`，与 `users.username` 一致；
--       ② 改写语句**去掉跨表字符串比较**，只按主键（数值）关联 ——
--          数值比较与排序规则无关，从根上不可能再踩到这一类错误。
--     第 ② 条比第 ① 条更值得坚持：它让「将来谁把备份表的排序规则改掉」也不再影响本迁移。
--
--  E. **为什么先落一张备份表再改**。
--     这是一次**不可逆的批量改写**（员工的登录账号会变）。备份表让运维在
--     「上线后发现某个系统还在用旧登录名」时有据可查、可精确回滚，
--     而不是只能从数据库全量备份里翻。
--     备份表**刻意不删**：它是一份低成本的账。
--     确认无碍后可由运维自行 `DROP TABLE users_username_backup_v27;`。
--
--  F. **重跑安全**：开头 `DROP TABLE IF EXISTS` 后重建。
--     理由：备份表一旦以错误的排序规则被创建过（如本迁移的第一版），
--     `CREATE TABLE IF NOT EXISTS` 会**静默跳过**，把错误的表结构留在库上。
--     先删后建让「表结构由本文件唯一决定」，也让重跑时的备份内容是真正的原始登录名
--     （而不是上一轮的产物）。
--
--  G. **迁移后「用中文姓名登录」仍然可用 —— 这是本次迁移安全的前提**。
--     登录与找回密码都改成「先按登录名精确匹配、再按姓名」：
--     纯数字 → username；其余 → real_name（见 `UserServiceImpl#resolveByUsernameThenName`）。
--     因此「张伟」改名为 10001 之后，用户在登录框里**照旧输入「张伟」**即可登录，
--     演示脚本与用户习惯都不受影响。重名时会明确报「该姓名对应多个账号」，
--     而不是含糊的「账号或密码错误」。
--
--  H. **回滚方式**（在 Flyway 未删除本版本记录时）：
--     UPDATE users u JOIN users_username_backup_v27 b ON u.id = b.id SET u.username = b.old_username;
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 备份表（记录「改了谁、从什么改成什么」）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `users_username_backup_v27`;

CREATE TABLE `users_username_backup_v27` (
  `id`           BIGINT       NOT NULL COMMENT 'users.id',
  `old_username` VARCHAR(64)  NOT NULL COMMENT '迁移前的登录名',
  `new_username` VARCHAR(64)  NOT NULL COMMENT '迁移后的登录名',
  `backed_up_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_uub_new` (`new_username`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  -- 必须与 users.username 的排序规则一致，见设计要点 D
  COLLATE = utf8mb4_general_ci
  COMMENT = 'V27 登录名规范化备份（可据此回滚；确认无碍后可自行删除）';

-- 把「是否已备份」读进用户变量，再在 INSERT ... SELECT 里引用：
-- 直接在 WHERE 子查询里查目标表自己，MySQL 会在部分版本上报 1093（不能边写边查同一张表）。
-- 注：本表刚被 DROP 重建，正常情况下必然为空；这一层判断是为了「手工删掉历史记录后重跑」时也不重复备份。
SET @v27_already_backed_up := (SELECT COUNT(*) FROM `users_username_backup_v27`);

INSERT INTO `users_username_backup_v27` (`id`, `old_username`, `new_username`)
SELECT
  `id`,
  `username`,
  LPAD(10000 + ROW_NUMBER() OVER (ORDER BY `id`), 5, '0')
FROM `users`
WHERE `username` <> 'administrator'
  AND `username` NOT REGEXP '^[0-9]+$'
  AND @v27_already_backed_up = 0;

-- ---------------------------------------------------------------------
-- 2. 按备份表改写登录名
-- ---------------------------------------------------------------------
-- 只按主键（数值）关联，**不做任何跨表字符串比较**（见设计要点 D 第 ② 条）。
-- 一条 UPDATE 完成，不会出现「改了 300 个、剩下一半还是旧名」的中间态。
UPDATE `users` u
  JOIN `users_username_backup_v27` b ON b.`id` = u.`id`
   SET u.`username` = b.`new_username`;

-- ---------------------------------------------------------------------
-- 3. 自检：迁移后不应再存在任何「既不是纯数字、又不是 administrator」的登录名。
--    这段不会阻止迁移成功（Flyway 不解析 SELECT 结果），但它会在执行日志里
--    留下一个可核对的数字，方便上线核对 —— 期望值为 0。
--    为了让这句在日志里可读，显式给出列别名。
-- ---------------------------------------------------------------------
SELECT COUNT(*) AS remaining_non_numeric_usernames
  FROM `users`
 WHERE `username` <> 'administrator'
   AND `username` NOT REGEXP '^[0-9]+$';
