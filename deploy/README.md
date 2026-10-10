# 设备借用工单系统 · 部署手册（群晖 NAS / 通用 Linux 服务器）

> 对应（接口限流）、（跨平台容器化部署）、（数据备份与恢复）。
> 一套 `docker-compose.yml` 同时支持**群晖 NAS** 与**通用 Linux 服务器**，差异只体现在 `.env` 里的路径与宿主侧的开机自启方式，`compose` 文件本身不改。

---

## 一、部署架构

```
                     客户端浏览器
                         │  https://ticket.你的域名.com
                         ▼
        ┌──────────────────────────────────────────┐
        │  外层反向代理（TLS 终止在宿主）            │
        │  · 群晖：DSM 登录门户 → 反向代理           │
        │  · Linux：宿主 Nginx 或 Caddy              │
        └──────────────────────────────────────────┘
                         │  http://127.0.0.1:8080
                         ▼
        ┌──────────────────────────────────────────┐
        │  边缘 Nginx 容器（唯一对宿主暴露的入口）    │
        │  · 静态资源 → frontend                    │
        │  · /api     → backend                     │
        │  · limit_req 第一道限流兜底               │
        └──────────────────────────────────────────┘
              │                          │
              ▼                          ▼
      ┌───────────────┐         ┌────────────────┐
      │ frontend 容器 │         │  backend 容器   │
      │ (Vue 构建产物)│         │ (Spring Boot)  │
      └───────────────┘         └────────────────┘
                                     │        │
                                     ▼        ▼
                              ┌────────┐  ┌────────┐
                              │ mysql  │  │ redis  │   ← 均不发布端口
                              └────────┘  └────────┘
                                     ▲
                              ┌──────────────┐
                              │ backup 容器   │ 每日 02:00 全量备份
                              │ (cron)       │
                              └──────────────┘
```

**四个必须理解的设计点：**

| 设计 | 原因 |
|---|---|
| 应用只监听 HTTP 80，TLS 交给外层反代 | 证书续期、私钥分布、端口冲突都归外层代理管理，容器保持无状态可迁移 |
| 只有 nginx 容器映射宿主端口 | mysql / redis / backend 在网络层就不从宿主可达，缩小攻击面 |
| `DATA_ROOT` / `BACKUP_DIR` 全部走 `.env` | 群晖是 `/volume1/...`、Linux 是 `/opt/...`，硬编码任一个都会让另一个平台起不来 |
| 备份用**容器内 cron** 而非宿主定时任务 | 群晖「任务计划」与 Linux `crontab` 语法不同，容器内 cron 让两平台共用一份调度配置 |

---

## 二、前置条件

### 硬件与系统

| 项 | 最低 | 建议 |
|---|---|---|
| CPU / 内存 | 2 核 / 2 GB | 2 核 / 4 GB（后端 JVM + MySQL 同机） |
| 磁盘 | 20 GB 可用 | 系统盘 ≥ 20 GB，**另备一块盘放备份** |
| 群晖 | DSM 7.2+，Container Manager 套件 | 已启用 SSH |
| Linux | 内核 5.x+，Docker 24+，Compose v2 | 发行版不限（Debian/Ubuntu/CentOS 均可） |

### 端口

| 端口 | 用途 | 说明 |
|---|---|---|
| `WEB_PORT`（默认 8080） | 应用入口，映射到边缘 Nginx 的 80 | 群晖**必须**用高位端口，因为 80/443 已被 DSM 占用 |
| 外层反代监听 80/443 | HTTPS 入口 | 由 DSM 或宿主 Nginx/Caddy 占用 |

---

## 三、部署前准备

### 1. 获取代码并进入编排目录

```bash
# 群晖：/volume1/docker/ticket-system
# Linux：/opt/ticket-system
cd <项目根>/deploy
```
> 以下所有命令**都在 `deploy` 目录下执行**（`docker-compose.yml`、`.env`、`scripts/` 均在此）。

### 2. 生成本地 `.env`

```bash
cp .env.production.example .env
```

逐项替换 `.env` 中的 `__CHANGE_ME__`。密钥一律用随机值，**不要自己编**：

```bash
openssl rand -base64 48    # → JWT_SECRET            （必须 ≥ 32 字节）
openssl rand -base64 24    # → MYSQL_ROOT_PASSWORD / REDIS_PASSWORD
openssl rand -hex 32       # → INTERNAL_ALERT_TOKEN  （备份失败上报共用密钥）
```

必须填写的 5 个变量：

| 变量 | 群晖示例 | Linux 示例 |
|---|---|---|
| `DATA_ROOT` | `/volume1/docker/ticket-system/data` | `/opt/ticket-system/data` |
| `MYSQL_ROOT_PASSWORD` | 随机 | 随机 |
| `REDIS_PASSWORD` | 随机 | 随机 |
| `JWT_SECRET` | `openssl rand -base64 48` | 同 |
| `INTERNAL_ALERT_TOKEN` | `openssl rand -hex 32` | 同 |

> **`BACKUP_DIR` 也可以留空**：备份容器挂在 profile `backup` 下、**默认不启动**。
> 需要时执行 `docker compose --profile backup up -d`；留空则归档写入 `$DATA_ROOT/backup`。
> 但要写到**另一块盘**才叫备份 —— 需要时在 `.env` 补 `BACKUP_DIR=/volume1/backups/ticket-system`。

> **超管账号不在这里填**：`SUPER_ADMIN_*` 留空即可 —— 首次用浏览器访问会自动跳到
> 「初始化向导」，由你现场设定超管账号名与密码（设定后向导不再出现，且该账号不可改、不可被重置）。
> 仅当需要无人值守自动建号时，才填 `SUPER_ADMIN_USERNAME` + `SUPER_ADMIN_INIT_PASSWORD`。

> ⚠️ `.env` 内含全部密钥，**绝不能提交到代码仓库**（`.gitignore` 已忽略）。
> ⚠️ 拥有 Docker 权限 = 拥有全部密钥（`docker inspect` 能读到环境变量），宿主机账号权限本身就是安全边界。

### 3. 修改 TRUSTED_PROXIES（重要）

`TRUSTED_PROXIES` 决定「哪些地址发来的 `X-Forwarded-For` 才被采信」。默认值已覆盖常见内网网段：

```
127.0.0.1/32,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16
```

- **群晖 / 内网反代**：通常无需修改。
- **云负载均衡 / 跨网段反代**：必须把反代的出口 IP 段追加进来，否则后果是——
  1. 审计日志里客户端 IP 全部变成代理地址；
  2. 登录限流的「同 IP」维度把所有用户算成一个人，**误伤正常登录**。

> 判断方法：部署后访问 `GET /api/health`，看返回的 `clientIp` 是不是你自己的真实出口 IP。

### 4. 执行部署前体检

```bash
sudo bash scripts/preflight.sh
```

体检覆盖：Docker/Compose 版本、`.env` 占位值残留、密钥长度、`DATA_ROOT` 与 `BACKUP_DIR` 是否分离、目录属主（后端以 `10001:10001` 运行）、端口占用、磁盘空间、`docker compose config` 语法。

**退出码 0 = 通过（允许有 WARN）；1 = 有必须处理的问题。** 未通过不要往下走。

---

## 四、启动

```bash
sudo docker compose up -d --build      # 首次需构建镜像，NAS 上可能耗时数分钟
sudo docker compose ps                 # 等待各容器 healthy
```

首次启动顺序由 compose 的 `depends_on.condition: service_healthy` 保证：`mysql`/`redis` 健康 → `backend` 启动 → `frontend` → `nginx`。

### 验证（按顺序，不要跳）

```bash
# 1) 容器状态：mysql / redis / backend / frontend / nginx 全部 healthy，backup 为 running
sudo docker compose ps

# 1.5) 一键冒烟：健康检查 + 真实 IP 识别 + 告警通道鉴权 + 登录限流（ / ）
bash scripts/smoke-test.sh

# 2) 后端健康 + 真实 IP 识别
curl -s http://127.0.0.1:8080/api/health
```

期望返回关键字段：

```json
{ "code":"SUCCESS",
  "data":{ "status":"UP", "clientIp":"...", "scheme":"http",
           "secure":false, "trustedProxyRules":5 } }
```

| 字段 | 直连（127.0.0.1）期望 | 经外层反代后期望 | 不符合时的含义 |
|---|---|---|---|
| `clientIp` | `127.0.0.1` | **你的真实出口 IP** | 反代没传 XFF，或 `TRUSTED_PROXIES` 未覆盖反代网段 |
| `scheme` | `http` | `https` | 反代缺少 `X-Forwarded-Proto` 头 |
| `trustedProxyRules` | `5`（默认） | 同 | `0` 表示 `TRUSTED_PROXIES` 配空了 |

```bash
# 3) 首次登录：浏览器打开 https://你的域名/
#    库中还没有超管 → 会自动跳到「初始化向导」/setup
#    在此自己设定超管账号名与密码（用户名自取，密码需满足强度要求）
#    设定完成后该页面不再出现；该账号不可改、不可被重置
# 4) 确认 Cookie：DevTools → Application → Cookies → TICKET_TOKEN
#    应同时具备 HttpOnly ✅ / Secure ✅ / SameSite=Lax
```

### 4.1 「强制绑定联系方式」闸门是怎么判的（重要）

登录后是否弹出「绑定手机号 / 邮箱」引导，**完全由服务端判定**，规则是下列三条判据的**与**：

| 判据 | 说明 |
|---|---|
| 库中手机与邮箱**都为空** | 绑了任意一个即视为已完成，不再打扰 |
| **至少一个渠道真的能发出验证码** | 短信 = 「启用短信通知」打开 **且**网关已接入；邮箱 = 「启用邮箱通知」打开 **且** SMTP 四项配齐 |
| **不是内置超级管理员** | 内置超管**单独豁免**，任何配置状态下都直达工作台 |

由此得到两条必须知道的现场结论：

1. **全新部署不会有人被拦住。** SMTP 一个字都没配、短信网关也尚未接入 ⇒ 两个渠道都不可用 ⇒
   闸门对**全部用户**放行。此时「找回密码」入口同样整体不可用（没有任何渠道能发出验证码）。
2. **配好 SMTP 之后，普通账号才会被要求绑定邮箱。** 在「系统参数 → 通知与验证 → 邮件通知」里把
   **SMTP 服务器地址 / SMTP 端口 / 发件邮箱账号 / SMTP 授权码**四项填齐并保存，**保存即生效**
   （参数缓存立即刷新，无需重启容器）。之后：

   - 普通员工登录 → 进入绑定页，用**邮箱验证码**自助完成绑定；
   - **内置超管登录 → 依旧不弹绑定页**，需要绑定时自行进「个人资料」页操作。

   第 2 条的后半句是刻意的：内置超管是唯一能配 SMTP 的账号，若它也被拦住，就会出现
   「想解锁得先配好 SMTP，而配 SMTP 的入口又只有它能进」的自锁。

> **当前短信网关尚未接入**，因此短信渠道**恒为不可用**（参数页的短信卡片可以正常保存，
> 但验证码只会投递到应用日志：`docker logs --tail=300 ticket-backend` 里找 `FORGOT-CODE`）。
> **对外宣导时只要求员工绑定邮箱即可。**

#### 4.2 上线前临时绕过（老库升级场景）

若库里已经有一批**既没手机号也没邮箱**的历史账号，而 SMTP 已经配好，
它们下次登录就会被引导到绑定页。升级窗口内若不想让这批账号被拦，
可以先用下面的 SQL **预置**联系方式（正式做法仍然是让员工自助绑定）：

```sql
-- ⚠️ 临时手段，仅限升级窗口使用。用完请通知员工到「个人资料」自助改绑成真实邮箱。

-- 1) 先看清楚哪些账号会被拦（手机与邮箱都为空）
SELECT id, username, email, phone
  FROM `users`
 WHERE (phone IS NULL OR phone = '') AND (email IS NULL OR email = '');

-- 2) 为指定账号预置邮箱。email 与 phone 在 users 上都是唯一索引
--    （uk_users_email / uk_users_phone），批量写入时不要用同一个邮箱 —— 会直接撞索引。
UPDATE `users` SET email = 'zhangsan@example.com' WHERE username = '10001';

-- 3) 若想「全站临时关掉闸门」，把两个验证开关都关掉即可。
--    改完等 60 秒兜底刷新，或 `docker compose restart backend` 立即生效；恢复时把值改回 '1'。
UPDATE `system_config` SET config_value = '0'
 WHERE config_key IN ('sms_verify_enabled', 'email_verify_enabled');
```

**回滚**：`UPDATE users SET email = NULL WHERE email = 'zhangsan@example.com';`（只清你写进去的那批）。

**不要**用 `UPDATE users SET email = CONCAT(username, '@example.invalid')` 之类的批量造数 ——
唯一索引会拦住重复值，而且假邮箱一旦被员工沿用，找回密码的验证码将永远发不到人手上。

---

## 五、外层反代接入（按平台二选一）

应用本身只监听 HTTP，**必须**由外层反代终止 TLS。三份可直接照抄的配置在 `reverse-proxy/`：

### 5.1 群晖 DSM 反向代理

见 **[`reverse-proxy/synology-reverse-proxy.md`](./reverse-proxy/synology-reverse-proxy.md)**（含 Let's Encrypt 证书签发、自定义标题、逐条验证与常见问题）。

关键三点：
- 来源 `HTTPS 443` → 目标 `HTTP localhost:8080`（**填 localhost，不是容器名**，反代跑在宿主上）；
- 在「自定义标题」里**手工添加** `X-Forwarded-Proto: https`（DSM 不会自动加）；
- `X-Forwarded-For` / `X-Real-IP` 由 DSM 内置 nginx 自动写入，无需手工加。

### 5.2 Linux · 宿主 Nginx

见 **[`reverse-proxy/linux-nginx.conf`](./reverse-proxy/linux-nginx.conf)**。放到 `/etc/nginx/conf.d/`，用 certbot 签证书：

```bash
sudo certbot --nginx -d ticket.example.com
sudo nginx -t && sudo systemctl reload nginx
```

> 宿主 Nginx 只负责 TLS 与注入真实 IP，`location /api` 的转发**不用再写**——那是容器内边缘 Nginx 的职责。

### 5.3 Linux · Caddy（自动 HTTPS）

见 **[`reverse-proxy/linux-caddy.conf`](./reverse-proxy/linux-caddy.conf)**。粘贴到 `/etc/caddy/Caddyfile`：

```bash
sudo caddy validate --config /etc/caddy/Caddyfile && sudo systemctl reload caddy
```

> Caddy 默认丢弃客户端自带的 XFF 并重写为真实对端地址，比 Nginx 默认行为更安全。

**无论用哪种反代，都必须传递：** `X-Real-IP`、`X-Forwarded-For`（用 `$proxy_add_x_forwarded_for` 追加而非覆盖）、`X-Forwarded-Proto`。

---

## 六、限流加固（）

系统部署后自带**四层**限流，无需额外配置即可生效：

| 层 | 位置 | 说明 |
|---|---|---|
| ① | 外层反代 | 可选，把恶意流量挡在最外层（群晖/宿主 Nginx 均可配 `limit_req`） |
| ② | 边缘 Nginx 容器 | `limit_req_zone` 兜底：登录 20r/m、API 600r/m；超限返回统一 429 JSON |
| ③ | 后端登录接口 | **同 IP 5 次/分 + 同账号 3 次/分**，两个维度独立判断，与「连续失败 5 次锁定」叠加 |
| ④ | 后端全局 API | 按 IP 令牌桶：匿名更严、登录用户放宽、白名单 IP 不限流 |

### 阈值全部可热调，无需重启

超管在 **系统配置 → 参数设置** 里可调整以下参数，保存后即时生效（存于 `system_config` 表，Redis 缓存）：

| 参数 key | 默认 | 含义 |
|---|---|---|
| `rate_limit_enabled` | 1 | 总开关 |
| `login_ip_rate_limit_per_minute` | 5 | 登录：同 IP 每分钟上限 |
| `login_account_rate_limit_per_minute` | 3 | 登录：同账号每分钟上限 |
| `rate_limit_ip_per_minute` / `_burst` | 300 / 60 | 全局 API：IP 维度令牌桶速率 / 突发 |
| `rate_limit_anon_per_minute` / `_burst` | 60 / 20 | 匿名用户（未登录） |
| `rate_limit_user_per_minute` / `_burst` | 240 / 40 | 已登录用户 |
| `rate_limit_whitelist` | 空 | 白名单 IP/CIDR（逗号分隔），命中不限流 |

### 限流触发的表现

- HTTP **429**，响应头带 `Retry-After: <秒>`，响应体带 `retryAfterSeconds`；
- 前端弹出友好提示「**操作过于频繁，请 X 秒后重试**」，不是原始报错；
- 每次触发写入操作日志（模块`运维作业`，动作`接口限流拦截`），同一来源 1 分钟内只记一次，避免刷爆日志。

### 设计与降级约定

- 限流采用 **fail-open**：Redis 异常时放行并记 WARN，优先保证可用性；
- 备份失败上报接口的 `X-Internal-Token` 校验采用 **fail-closed**：token 为空时拒绝，避免变成开放的“消息炸弹”通道；
- 真实客户端 IP 只在「TCP 对端属于 `TRUSTED_PROXIES`」时才采信 XFF，防止伪造 IP 绕过限流（`server.forward-headers-strategy` 显式设为 `none`）。

---

## 七、备份与恢复（）

### 7.1 自动备份

`backup` 容器每天 **02:00**（`BACKUP_CRON` 可改）执行 `backup.sh`，产出：

```
${BACKUP_DIR}/db-backup-YYYYmmdd-HHMMSS.tar.gz   （权限 600，仅 root 可读）
   ├── database.sql       MySQL 全库导出（--single-transaction，不锁表）
   ├── attachments/       附件文件目录
   ├── config/.env        配置文件（缺它无法完整重建系统）
   └── manifest.txt       备份元信息
```

**关键保障（每条对应一类真实事故）：**
- 三层校验：文件非空 → `gzip -t` 完整性 → 归档内确实含 `database.sql`；
- **校验通过后才执行保留策略**——否则「某天备份失败 + 旧备份被删」会叠加成「一个能用的备份都不剩」；
- **失败必告警**：给全部超管发站内消息；连告警都发不出去时，往 `$BACKUP_DIR/ALERT-SEND-FAILED.log` 落一条本地记录，**绝不静默失败**；
- 保留天数 `BACKUP_RETENTION_DAYS` 默认 30 天；
- 成功默认**不推**消息（每天一条会在 30 天内堆成噪音），成功记录见操作日志；如需推送设 `BACKUP_NOTIFY_SUCCESS=true`。

**部署当天验证备份链路**（不必等到凌晨 2 点）：

```bash
# 在 .env 里临时加 BACKUP_RUN_ON_START=true，重启 backup 容器，观察日志
sudo docker compose up -d backup
sudo docker compose logs -f backup          # 看到「归档校验通过」即成功
ls -lh "$BACKUP_DIR"                        # 归档权限应为 -rw------- (600)
# 验证完把 BACKUP_RUN_ON_START 改回 false（或删除该行）
```

手动触发一次：

```bash
sudo docker compose exec backup /usr/local/bin/backup.sh
```

### 7.2 恢复

```bash
# 交互式（会二次确认，需输入大写 YES）
sudo bash scripts/restore.sh "$BACKUP_DIR/db-backup-20260919-020001.tar.gz"
# 演练 / 自动化（跳过确认）
sudo bash scripts/restore.sh <归档> --yes
# 同时释放归档中的 .env 到 .env.restored（不覆盖现有 .env）
sudo bash scripts/restore.sh <归档> --with-env
```

脚本执行顺序（每步不可省）：校验归档 → **停后端** → DROP 并重建数据库 → 恢复附件（旧目录改名保留，可选退） → 启动后端并等待健康 → 抽样验证（关键表行数 + Flyway 版本）。

恢复后**必须人工验证**：浏览器登录 → 抽查最近工单与备份时间点一致 → 随机下载一个附件确认能打开。

### 7.3 异地二次备份（群晖 Hyper Backup）

备份目录落在 NAS 本机仍有单点风险（磁盘/整机故障）。用 **Hyper Backup** 把 `${BACKUP_DIR}` 再同步到另一块盘或异地：

1. DSM → **Hyper Backup** → 新增 → 「本地文件夹和 USB」或「rsync / 远程 NAS」；
2. 源选 `${BACKUP_DIR}`（如 `/volume1/backups/ticket-system`）；
3. 目标选**另一块物理盘**的共享文件夹，或远端 NAS / 对象存储；
4. 计划与保留：建议每日一次，保留策略与本地一致（30 份）；
5. 首次完成后做一次「备份完整性检查」，确认可还原。

### 7.4 恢复演练（建议每季度一次）

> 未演练过的备份 = 没有备份。演练不会影响生产：在另一台机器或测试目录执行 restore，用 `--yes` 跳过确认。

演练检查清单：
- [ ] 归档能解压、`manifest.txt` 时间点正确；
- [ ] `database.sql` 含预期张数的 `CREATE TABLE`；
- [ ] 恢复后 `flyway_schema_history` 版本与备份时一致；
- [ ] 用备份中的账号能登录，附件能下载。

---

## 八、开机自启

### 8.1 群晖

DSM 对 `restart: unless-stopped` 的支持随套件版本而异，最稳的两种做法：

**方案 A（推荐）：Container Manager → 项目 → 新增**
把项目根目录（含 `docker-compose.yml` 的那层）选为 `.../ticket-system/deploy`，导入后由套件接管自启。

**方案 B：控制面板 → 任务计划 → 触发的任务 → 开机**
新增任务，用户选 `root`，执行命令：

```bash
cd /volume1/docker/ticket-system/deploy && /usr/local/bin/docker compose up -d
```

### 8.2 Linux · systemd

```bash
sudo cp deploy/systemd/ticket-system.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now ticket-system
```

常用命令：

```bash
sudo systemctl status ticket-system      # 查看状态
sudo systemctl restart ticket-system     # 重启全部服务
journalctl -u ticket-system -n 100       # 查看启动日志
```

> `WorkingDirectory` 在 unit 里写死为 `/opt/ticket-system/deploy`，换了部署路径请同步改 unit 中的两处路径。

---

## 九、日常运维

### 常用命令

```bash
sudo docker compose ps                        # 状态
sudo docker compose logs -f backend           # 跟随某服务日志
sudo docker compose logs --tail=200 nginx     # 最近 200 行
sudo docker compose restart backend           # 重启单服务
sudo docker compose down                      # 停止（保留数据，数据在 DATA_ROOT 不在卷里）
sudo docker compose up -d --remove-orphans    # 拉起全部
```

### 升级 / 回滚

镜像标签由 `IMAGE_TAG` 控制（默认 `latest`；也可填不可变标签 `commit-<短sha>` 用于锁定或回滚）。

#### 方式一：拉取预构建镜像（推荐）

CI（GitHub Actions，push `main` 触发）会把 backend / frontend / backup 三个镜像推到 GHCR，
这些包是 **public**，无需 `docker login`：

```bash
cd <deploy 目录>
docker compose pull
# ⚠️ 必须 --force-recreate：镜像内容变了但标签名（:latest）没变时，
#    compose 会判定「服务定义无变化」而跳过重建，容器仍跑旧镜像。
docker compose up -d --force-recreate backend frontend
# 确认容器真的换上了新镜像（created 应为当天）
docker image inspect -f '{{.Id}} created={{.Created}}' \
  ghcr.io/zimoliu789-coder/it-work-order-approval-system/backend:latest
```

随后 `docker compose ps` 等 backend 变 `(healthy)` 即可。**mysql / redis / nginx 不受影响**（数据都在 `${DATA_ROOT}`）。

> - 只重建 `backend` / `frontend`，**不要**用不带服务名的 `--force-recreate`（那会把数据库与缓存一并重建）。
> - 后端启动较慢（首次可达数分钟），期间 Nginx 对 backend 返回 503 属正常现象。

#### 方式二：源码本地构建

```bash
cd <仓库根目录>
# 若你手工改过 deploy/nginx/conf.d/ticket.conf（本地打过补丁），先收起本地改动，拉完再 drop；
# 没有本地改动可跳过这两条 stash。
git stash push -- deploy/nginx/conf.d/ticket.conf
git pull
git stash drop
cd deploy
docker compose up -d --build
```

#### ⚠️ 更新后必查：`COOKIE_SECURE`

**每次更新镜像后，第一件事就是确认 `.env` 的 `COOKIE_SECURE`。**
配错的症状是「登录接口返回 200，但立刻被弹回登录页」—— Cookie 带 `Secure` 标记时，
浏览器**只在 HTTPS 下**才保存它；用明文 HTTP 访问会被直接丢弃，随后所有鉴权请求 401。
**无痕窗口同样复现**（属性由服务端下发，与浏览器缓存无关）。

```bash
grep -n COOKIE_SECURE .env
docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' ticket-backend | grep -i COOKIE
```

| 你的访问方式 | 应设的值 |
|---|---|
| 前面挂了 HTTPS 反代 | `true`（默认，推荐） |
| 纯 `http://<IP>:<端口>` 联调 | **必须 `false`** |

改完**必须重建** backend 才生效（`docker restart` 不重读 `.env`）：

```bash
sed -i '/^COOKIE_SECURE=/d' .env && echo 'COOKIE_SECURE=false' >> .env
docker compose up -d --force-recreate backend
```

> 排查口诀：F12 → Network → `login` → 响应头 `Set-Cookie` 里出现 `Secure` 字样，
> 而地址栏是 `http://` —— 就是这里配错了。

#### 回滚

```bash
# 回到上一版（把 commit-<旧sha> 换成目标版本）
sed -i '/^IMAGE_TAG=/d' .env && echo 'IMAGE_TAG=commit-<旧sha>' >> .env
docker compose pull && docker compose up -d --force-recreate backend frontend
# 回到最新版
sed -i '/^IMAGE_TAG=/d' .env
docker compose pull && docker compose up -d --force-recreate backend frontend
```

> 数据库结构由 **Flyway** 在 backend 启动时自动迁移，**降级不会自动回滚 SQL**。跨大版本回滚前，务必先用 `restore.sh` 把库恢复到大版本升级前的备份点。

### 日志位置

| 内容 | 位置 |
|---|---|
| 容器 stdout | `docker compose logs <service>`（json-file 滚动，单文件 10 MB × 3） |
| Nginx 访问日志 | `${DATA_ROOT}/logs/nginx/` |
| 后端应用日志 | `${DATA_ROOT}/logs/` |
| 备份日志 | `docker compose logs backup` |

---

## 十、故障排查

| 现象 | 原因与处理 |
|---|---|
| 容器起不来，日志报 `.env` 变量为空 | `docker compose up` 报 `必须设置 XXX` → 回到第三节补 `.env`，或先跑 `preflight.sh` |
| **mysql 反复重启**，日志报 `Can't read dir of '/etc/mysql/conf.d/' (OS errno 13 - Permission denied)` | 宿主上 `deploy/` 对容器内 mysql 用户（uid 999）不可读 —— 经 Windows 共享 / 文件管理器拷来的目录常是 `0700`。以 root 执行 `chmod -R a+rX <deploy 目录>`，并确保上级目录可进入（如 `chmod o+rx /vol1/1000/docker`），再 `docker compose up -d`。`preflight.sh` 第 5 步已会自动处理 |
| `docker compose pull` 报 `manifest unknown` | `.env` 的 `IMAGE_TAG` 指向了仓库上不存在的标签。可用标签：`latest`（默认）/ `main` / `commit-<sha>` |
| **边缘 Nginx 容器反复重启**，日志报 `[emerg] "set" directive is not allowed here in /etc/nginx/conf.d/ticket.conf:20`，宿主端口无人监听（浏览器 `ERR_CONNECTION_REFUSED`） | `nginx.conf` 的 `include /etc/nginx/conf.d/*.conf;` 位于 `http {}` 块内 ⇒ conf.d 下文件的**顶层等价于 http 上下文**，而 `set` 只允许出现在 `server` / `location` / `if` 中。把 `set` 移进 `server {}` 即可（注意 `resolver` 允许在 http 上下文，可留在顶层）。判断容器是否真在跑看 `docker inspect ticket-nginx --format '{{.State.Status}} {{.RestartCount}}'`，`RestartCount` 不为 0 即崩溃循环 |
| **登录成功，但之后任何操作都被踢回登录页**（接口 401） | `.env` 里 `COOKIE_SECURE=true`，而你用**明文 HTTP** 访问（如 `http://192.168.1.60:8080`）—— 浏览器会直接丢弃带 `Secure` 标记的 Cookie，于是每个需要鉴权的请求都 401。纯 HTTP 联调请设 `COOKIE_SECURE=false` 后 `docker compose up -d backend`（须重建容器，`restart` 不生效），并**重新登录一次**。正式上线应改为前面挂 HTTPS 反代并把该值调回 `true` |
| backend 反复重启，健康检查不通过 | 多为 `${DATA_ROOT}/logs` 属主不对（后端以 `10001` 运行）。执行 `sudo chown -R 10001:10001 "$DATA_ROOT"/{logs,attachments,exports}` |
| `clientIp` 全是反代地址 / 所有用户算同一人 | `TRUSTED_PROXIES` 未覆盖反代网段 → 追加后 `docker compose up -d backend` |
| `scheme` 是 `http`（实际走 https） | 反代缺少 `X-Forwarded-Proto` 头（群晖需在「自定义标题」手工加） |
| 登录频繁收到 429 | 触发限流。等待 `Retry-After` 秒数；或超管在「参数设置」上调阈值；内网/超管可加 `rate_limit_whitelist` |
| 上传附件报 413 | 某层请求体上限过小。三层需对齐 50m：外层反代 / 边缘 Nginx（已配 `client_max_body_size 50m`）/ Spring multipart |
| 接口 503，响应体是 JSON「后端服务暂不可用」 | 边缘 Nginx 连不上 backend，`docker compose ps` 看 backend 是否 healthy，再 `logs --tail=100 backend` |
| 备份一直失败，日志说口令为空 | cron 不继承环境变量。`entrypoint.sh` 已把变量写进 crontab，若仍为空说明 `.env` 里 `MYSQL_ROOT_PASSWORD` 未设置 |
| 备份失败但没收到站内消息 | 检查 `INTERNAL_ALERT_TOKEN` 两端一致、`ALERT_URL` 可达；同时看 `$BACKUP_DIR/ALERT-SEND-FAILED.log` |
| **登录后一直停在「绑定手机号 / 邮箱」页，收不到验证码** | 该页只在「至少一个渠道真的能发出验证码」时才会出现。先确认「系统参数 → 通知与验证 → 邮件通知」里 SMTP 四项是否填齐（保存即生效）；若确认没配好却仍被拦，是浏览器还拿着旧标志 —— 退出重新登录一次。短信渠道当前**恒不可用**（网关未接入），验证码只写日志：`docker logs --tail=300 ticket-backend` 里找 `FORGOT-CODE` |
| 磁盘被容器日志写满 | 已在 compose 里配 json-file 滚动（10m×3）。若仍占满，检查 `${DATA_ROOT}` 所在盘容量 |

---

## 十一、目录结构

```
deploy/
├── README.md                     ← 本文件
├── .env.production.example       配置模板（全占位值）
├── docker-compose.yml            编排定义（跨平台）
├── mysql/conf.d/my.cnf           MySQL 调优（utf8mb4 / +08:00 / 连接不反查 DNS）
├── nginx/                        边缘 Nginx（唯一对宿主暴露的入口）
│   ├── nginx.conf                真实 IP 与限流 zone 定义
│   ├── conf.d/ticket.conf        站点路由 + 429/503 兜底
│   └── snippets/                 代理与静态资源片段
├── backup/                       备份镜像
│   ├── Dockerfile
│   ├── entrypoint.sh             固化环境变量到 crontab + 启动 cron
│   └── backup.sh                 三合一归档 + 三层校验 + 失败告警
├── scripts/
│   ├── preflight.sh              部署前体检
│   ├── smoke-test.sh             部署后冒烟（健康/IP/告警鉴权/限流）
│   └── restore.sh                一键恢复
├── reverse-proxy/
│   ├── synology-reverse-proxy.md 群晖 DSM 反代步骤
│   ├── linux-nginx.conf          宿主 Nginx + certbot 示例
│   └── linux-caddy.conf          Caddy 自动 HTTPS 示例
└── systemd/
    └── ticket-system.service     Linux 开机自启单元
```

---

## 十二、上线检查清单

**部署前**
- [ ] `.env` 的 7 个必填项已替换，无 `__CHANGE_ME__` 残留
- [ ] 密钥均由 `openssl rand` 生成，非人为编造
- [ ] `DATA_ROOT` 与 `BACKUP_DIR` 已分离（最好不同物理盘）
- [ ] `TRUSTED_PROXIES` 覆盖外层反代网段
- [ ] `preflight.sh` 退出码为 0

**部署后**
- [ ] `docker compose ps` 全部 healthy
- [ ] 直连 `/api/health` 返回 UP
- [ ] 经外层反代 `clientIp` 为真实 IP、`scheme` 为 `https`
- [ ] 超管首次登录被强制改密；`TICKET_TOKEN` Cookie 具 HttpOnly + Secure + SameSite
- [ ] 确认强制绑定闸门行为：SMTP 未配齐时对**所有人**放行；配齐后普通账号弹邮箱绑定、**内置超管不弹**
- [ ] 已对外说明「本期只绑邮箱」（短信网关未接入，短信渠道恒不可用）
- [ ] 连测 6 次错误密码，第 4~6 次收到 429 且前端提示带秒数
- [ ] `BACKUP_RUN_ON_START=true` 跑通一次备份，归档权限为 600
- [ ] 执行过一次恢复演练
- [ ] 已配置 Hyper Backup 异地同步
- [ ] 已配置开机自启

---

> **故障兜底原则**：任何一层出问题，先看 `docker compose ps` 与对应服务 `logs`。边缘 Nginx 会把 upstream 异常转成 503 JSON、限流超限转成 429 JSON，前端据此给用户可读提示，而不是白屏或原始报错。
