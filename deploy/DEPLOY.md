# 设备借用工单系统 · 主备双机高可用部署手册（DEPLOY.md）

> **适用范围**：需要「单机故障时业务不中断」的生产环境。
>
> **与单机版的关系**：本手册是**增量**，不是替代 ——
> 单机部署（群晖 NAS / 单台 Linux，`deploy/docker-compose.yml` + `deploy/README.md`）**保持原样可用**，
> 二者按规模择一。HA 版只是把单机版的每一层换成「双份 + 仲裁」，业务代码与前端产物完全一致。
>
> **一句话定位**：
> 单机版回答「怎么把它跑起来」；本手册回答「**其中一台机器拔电后，用户为什么感觉不到**」。

---

## 目录

1. [架构与故障域](#一架构与故障域)
2. [前置条件](#二前置条件)
3. [双机准备](#三双机准备)
4. [MySQL 主主双向复制](#四mysql-主主双向复制)
5. [Redis 哨兵](#五redis-哨兵)
6. [NAS 共享存储](#六nas-共享存储)
7. [keepalived VIP 与边缘入口](#七keepalived-vip-与边缘入口)
8. [启动与验证](#八启动与验证)
9. [切换演练](#九切换演练)
10. [日常运维与滚动升级（ 前提）](#十日常运维与滚动升级批次-g-前提)
11. [在线一键升级（）](#十一在线一键升级批次-g)
12. [故障排查](#十二故障排查)
13. [上线检查清单](#十三上线检查清单)
14. [主备配置可视化页与脚本的对应关系](#十四主备配置可视化页与脚本的对应关系)

---

## 一、架构与故障域

### 1.1 拓扑

```
                          客户端
                            │
                            ▼
                  ┌───────────────────┐
                  │  外层反代（TLS）   │  ← 群晖 DSM / 宿主 Nginx / Caddy
                  │  指向 VIP_WEB      │     （接入方式见 README 第五章）
                  └─────────┬─────────┘
                            ▼
                  VIP_WEB:${WEB_PORT}          ← keepalived 在这两个地址间漂移
                            │
        ┌───────────────────┴───────────────────┐
        ▼                                       ▼
┌────────────────────────┐              ┌────────────────────────┐
│ 节点 A（初始 MASTER）   │              │ 节点 B（初始 BACKUP）   │
│  ├ nginx(edge)         │              │  ├ nginx(edge)         │
│  ├ backend  (无状态)    │              │  ├ backend  (无状态)    │
│  ├ frontend (静态)      │              │  ├ frontend (静态)      │
│  ├ redis    (主→从)     │◄─ 哨兵 26379 ─┤  ├ redis    (从→主)     │
│  └ mysql    (主主)      │◄─ 双向复制 ──►│  └ mysql    (主主)      │
└───────────┬────────────┘              └───────────┬────────────┘
            │                                        │
            └──────────► NAS 共享存储 ◄──────────────┘
                  attachments / exports / backup

                  VIP_DB 独立漂移，供「后端容器」与「备份容器」连接数据库
```

### 1.2 三层各自的冗余方式（关键设计对照）

| 层次 | 单机版 | HA 版 | 冗余机制 | 切换时间 |
|------|--------|-------|---------|---------|
| **入口** | Nginx 容器 | 双 Nginx + keepalived | VRRP VIP 漂移 | 10~15 秒 |
| **应用** | 单 backend | 双 backend 同时服务 | 无状态 + VIP 把流量带过去 | 无需切换（本来就在跑） |
| **会话/缓存** | 单 Redis | Redis 主从 + 哨兵 | 哨兵自动主从切换 | 5~30 秒 |
| **数据库** | 单 MySQL | MySQL 主主双向复制 | keepalived + GTID 自动追平 | 10~15 秒 |
| **附件** | 本机磁盘 | NAS 共享（同一挂载点） | 两机同路径可见 | 无（一直可见） |

### 1.3 两个 VIP 为什么相互独立

`VIP_WEB` 与 `VIP_DB` 是**两个独立的 `vrrp_instance`**，各有自己的优先级与健康检查。
它们可能同时落在同一台机，也可能**分离**（Web 在 A、DB 在 B）。

这是**刻意**的设计：故障域更小。举例 ——
A 机的后端进程 OOM 被 kill 后重启中：

- 若两个 VIP 绑在一起整体切换 → **整站短暂不可用**（连只读页面都打不开）；
- 分离后 → Web VIP 仍在 A（Nginx 还在，只是后端没起来，健康检查会把它判失败），
  DB VIP 可继续在 B 上服务其它已连接的请求 → **降级但不全挂**，且排查时一眼能看出是哪一层的问题。

代价是**运维时要分别查看两个 VIP 的归属**，`scripts/switchover.sh status` 会一次打印两个。

### 1.4 明确「不解决什么」

诚实说明边界，避免上线后产生错误预期：

- **不防机房/网络整体故障**：两台机在同一机房同一网段时，交换机或断电会同时带走两台。
  真跨机房需要 VIP 跨子网（keepalived 单播跨网段需要路由器支持 VRRP 或改用 DNS 轮询 + 分布式锁），本手册不覆盖。
- **不防数据误删**：主主复制是**同步级冗余**，`DROP TABLE` 会立刻复制到对端。
  数据保护依靠 `deploy/backup`（），复制解决的是**可用性**，不是**可恢复性**。
- **不防「写冲突」造成的业务级脏数据**：主主双写若两端同时改同一行，MySQL 行级锁 + 自增错开能保证
  **不撞主键**，但「后写的覆盖先写的」这一语义冲突需要应用层的乐观锁/幂等来兜（本系统在工单状态机上已做）。
  HA 版默认**同一时刻只有一端接收写流量**（经 VIP），因此这一风险在实践中很低。

### 1.5 员工是通过什么地址访问的：域名 + VIP

这是整套 HA 对**最终用户**唯一可见的部分，也是最容易被误解的部分。

```
员工的浏览器
     │  https://ticket.example.com        ← 员工只认这个域名（书签 / 桌面快捷方式）
     ▼
  DNS（A 记录） ──► VIP_WEB（例：172.20.0.100）   ← 解析到【虚拟 IP】，不是任何一台机器的真实 IP
     ▼
  keepalived 持有 VIP 的那台（当前主节点）
     ▼
  边缘 Nginx :WEB_PORT → backend / frontend
```

三条要点：

1. **员工侧绑定的是域名，且该域名的 A 记录必须指向 `VIP_WEB`。**
   把域名解析到「节点 A 的真实 IP」是部署时最常见的错误：VIP 漂移到 B 之后，
   域名仍然指向已经宕掉的 A —— 切换在技术上确实发生了，用户却完全用不了。
2. **主节点宕机时 VIP 自动飘到备节点**（10~15 秒，见  / ），
   域名与 A 记录**保持不变**，员工最多感受到一次短暂的等待；
   不需要改配置，也不需要换地址重新登录。
3. **「切换后不用重新登录」的前提是两台机器的 `JWT_SECRET` 完全一致**（）。
   不一致时表现为「一切换就把所有人踢回登录页」——
   这正是用户最不能接受的体验，也是部署时最容易漏掉的一项。

> **为什么用 VIP 而不是「DNS 双 A 记录 / 轮询」？**
> 漂移发生在网络层，域名始终指向 `VIP_WEB` 这一个地址，因此**与 DNS 缓存时间无关**。
> 若改用双 A 记录靠 DNS 做切换，客户端缓存与 TTL 会把切换时间拉成「快的几秒、慢的几十分钟」，
> 而且无法保证只切一次、也无法保证同一个会话前后打到同一台机器上。

---

## 二、前置条件

### 2.1 硬件与系统

| 项 | 要求 | 说明 |
|----|------|------|
| 机器数量 | 2 台 | 建议**另加 1 台轻量机**跑第三个哨兵（见 ），共 3 台 |
| CPU / 内存 | 每台 ≥ 4 核 / 8GB | 与单机版同规格；双跑不翻倍（每台只承担部分流量） |
| 磁盘 | 每台 ≥ 100GB SSD | 数据实际在 NAS + 本地磁盘双写日志 |
| 系统 | Linux（Ubuntu 22.04+ / RHEL 9+）或群晖 DSM 7 | HA 不推荐群晖做节点：VRRP 与内核调参受限 |
| **NAS** | 支持 NFSv4.1 或 SMB 3.0 | **必须**，附件/导出/备份都放上面 |
| 时间同步 | 两台必须对时 | `chrony`/`ntpd`。时钟偏差会导致 MySQL 复制延迟显示异常与 JWT 校验抖动 |

### 2.2 端口

**两台机器各需放行**（宿主防火墙 / 云安全组），且**只允许对端节点与运维网段访问**：

| 端口 | 用途 | 开放范围 |
|------|------|---------|
| `${WEB_PORT}`（默认 8080） | 边缘 Nginx ← VIP_WEB | 外层反代 / 运维网段 |
| `3306` | MySQL ← 对端复制 + 本机容器经 VIP 连接 | **仅对端节点 IP** |
| `6379` | Redis ← 对端复制 + 本机容器经 VIP | **仅对端节点 IP** |
| `26379` | Redis 哨兵 ← 对端哨兵 + 后端服务发现 | **仅对端节点 IP** |
| VRRP 协议号 112 | keepalived 单播 VRRP | **仅对端节点 IP** |

> ⚠️ **`3306`/`6379` 绝不能暴露到公网**。这是 HA 与单机版最重要的安全差异：
> 单机版把数据库藏在 Docker 内网、网络层不可达；HA 版为跨主机寻址**必须发布端口**，
> 安全边界就从「网络隔离」变成了「防火墙规则正确」——请务必逐条核对。

### 2.3 工具链

两台上都要有：

```bash
docker --version            # ≥ 24
docker compose version      # ≥ v2.20（需支持 profiles 与 depends_on condition）
keepalived -v               # ≥ 2.0（apt/yum install keepalived）
curl --version
```

`jq` 非必需，但排障会方便很多。

---

## 三、双机准备

### 3.1 节点规划

| 节点 | `HA_NODE_NAME` | `HA_NODE_IP` | `HA_NODE_PRIORITY` | MySQL `server-id` | 自增偏移 | Redis 初始角色 | 备份 profile |
|------|---------------|--------------|--------------------|-------------------|---------|--------------|-------------|
| A | `ticket-a` | 例 `10.0.0.11` | `150` | 1 | 1 | 主 | ✅ 启用 |
| B | `ticket-b` | 例 `10.0.0.12` | `100` | 2 | 2 | 从 | ❌ 不启用 |

VIP 示例：`VIP_WEB=10.0.0.20`、`VIP_DB=10.0.0.21`。

> **为什么两个 IP 还要分「优先级」**：谁是活跃节点由优先级决定，A 常态是主、B 常态是备。
> 优先级拉开 50，是为了让「健康检查失败降权 -30」这一动作足以在需要时触发切换，
> 又不至于让两台机在同一次抢占里反复拉锯。

### 3.2 获取代码

```bash
# 两台上同样操作
cd /opt
git clone <仓库地址> ticket-system      # 或用你现有的同步方式
cd ticket-system/deploy/ha
```

> **镜像来源（免本地编译）**：应用镜像 `backend` / `frontend` / `backup` 已由 GitHub Actions 自动构建并推送到
> **GHCR（GitHub Container Registry）**，支持 `linux/amd64` + `linux/arm64` 双架构，**飞牛 / 群晖等 NAS 可直接拉取**。
> 部署只需：clone 仓库 → 进入仓库目录 → 复制并填写 `.env` → `docker compose pull` → `docker compose up -d`，
> 会自动从 GHCR 拉取镜像，**无需本地编译**。
> （单机部署用 `deploy/docker-compose.yml`，见仓库 README §6.2；本手册为双机高可用，用 `deploy/ha/docker-compose.ha.yml`。）
> ⚠️ 镜像包已设为 **Public**，可匿名拉取，无需 `docker login`。
> ⚠️ **别把 `.github/workflows/docker-build.yml` 当 compose 用**：那是 GitHub Actions 流水线，
> 交给 `docker compose` 会报 `invalid interpolation format for env.IMAGE_NAME ... ${{ github.repository }}`。
> ⚠️ 本编排依赖仓库内配套目录（`nginx/`、`mysql/conf.d/`、`backup/`）与 `.env`，
> **必须把整个仓库放到机器上**，只复制一个 compose 文件会因找不到这些路径而启动失败。
> ⚠️ 因为保留了 `build` 段作本地编译 fallback，请**先 `docker compose pull`**，否则本地无镜像时 compose 会尝试相对路径编译。

### 3.3 生成本地 `.env.ha`

```bash
cp .env.ha.example .env.ha
chmod 600 .env.ha
```

`.env.ha` 是**单机版 `.env` 的超集**：单机版全部变量原样保留，末尾追加 HA 专属变量。
**唯一两台不同的段落是「一、节点身份」**（共 8 个变量）：

```bash
# ---------- 节点 A（10.0.0.11）----------
HA_NODE_NAME=ticket-a
HA_NODE_ROLE=node-a
HA_NODE_IP=10.0.0.11
HA_PEER_IP=10.0.0.12
HA_NODE_PRIORITY=150
MYSQL_NODE_CONF=node-a.cnf
REDIS_REPLICAOF=
REDIS_ANNOUNCE_IP=10.0.0.11

# ---------- 节点 B（10.0.0.12）----------
HA_NODE_NAME=ticket-b
HA_NODE_ROLE=node-b
HA_NODE_IP=10.0.0.12
HA_PEER_IP=10.0.0.11
HA_NODE_PRIORITY=100
MYSQL_NODE_CONF=node-b.cnf
REDIS_REPLICAOF=10.0.0.11 6379
REDIS_ANNOUNCE_IP=10.0.0.12
```

其余变量（VIP、Redis 哨兵、NAS、密钥、备份 cron）**两台必须完全一致**。

### 3.4 必须手工核对的三组「跨机一致性」

这三处不一致**都不会在启动时报错**，只会在真正切换的那一刻集中爆发 —— 这是双机部署最容易踩的坑：

```bash
# ① JWT_SECRET 必须一致
#    不一致 → A 签发的令牌在 B 验签失败，切换后备机像是「把所有人踢下线」
# ② INTERNAL_ALERT_TOKEN 必须一致
#    不一致 → 备份失败告警被对端以 401 拒绝（fail-closed），告警静默丢失
# ③ MYSQL_ROOT_PASSWORD / REDIS_PASSWORD 必须一致
#    不一致 → 主主复制握手失败、哨兵切换后连不上新主
```

`scripts/preflight-ha.sh` 会打印 `JWT_SECRET` 与 `INTERNAL_ALERT_TOKEN` 的 **sha256 前 12 位指纹**，
在两台机器上各跑一次、肉眼比对即可，**无需把密钥明文摆在一起**。

### 3.5 生成密钥的正确姿势

```bash
# 在一台机器上生成，然后把【同一份】值复制到另一台（不要各生成一份！）
openssl rand -base64 48        # JWT_SECRET（≥32 字节，应用会拒绝更短的）
openssl rand -base64 32        # INTERNAL_ALERT_TOKEN
openssl rand -base64 24        # MYSQL_REPL_PASSWORD（复制专用账号口令）
openssl rand -base64 24        # REDIS_PASSWORD
openssl rand -base64 24        # MYSQL_ROOT_PASSWORD
```

> `MYSQL_REPL_USER` 权限仅 `REPLICATION SLAVE`，与「被盗即全库失守」的 root 口令分开保存。

### 3.6 VIP 与网卡

```bash
ip -4 addr show                  # 确认业务网卡名（群晖常见 ovs_eth0；Linux 常见 eth0）
ip route get 8.8.8.8             # 确认出接口
```

把网卡名填入 `HA_VRRP_IFACE`。**建议**为 `VIP_WEB`、`VIP_DB` 各预留一个 IP：
它们**不能**与 A/B 两节点的真实 IP 相同（`preflight-ha.sh` 会检查）。

### 3.7 `TZ` 与时间同步

```bash
timedatectl set-timezone Asia/Shanghai
timedatectl set-ntp true
timedatectl status               # 确认 System clock synchronized: yes
```

---

## 四、MySQL 主主双向复制

### 4.1 配置文件的分层

`deploy/ha/mysql/conf.d/` 下三份文件，**加载顺序由文件名前缀决定，顺序敏感**：

| 文件 | 挂载为 | 内容 | 为什么要分层 |
|------|--------|------|-------------|
| `../mysql/conf.d/my.cnf` | `00-base.cnf` | utf8mb4 / `+08:00` / `skip-name-resolve` 等基础调优 | 复用单机版，避免两处维护 |
| `10-replication-common.cnf` | `10-replication-common.cnf` | log-bin / GTID / ROW / `log_replica_updates` | **两机共用**，改机制只改一份 |
| `mysql/conf.d/node-{a,b}.cnf` | `zz-node-identity.cnf` | `server-id` + `auto_increment_offset` | **两机不同**，`zz-` 前缀保证最后加载、覆盖前值 |

> **为什么不合并成一份**：机制参数（ROW/GTID）两机一致，身份参数（server-id/偏移）两机不同。
> 合并后每次改机制都要在**两份文件里改同样的两处**，漏改其中一份的后果是**复制静默失效**
> （`SHOW REPLICA STATUS` 仍显示 IO/SQL 都是 Yes，但数据不再同步）—— 这种故障极难发现。

### 4.2 自增为什么必须错开

```ini
# node-a.cnf
server-id               = 1
auto_increment_increment = 2
auto_increment_offset    = 1        # 生成 1, 3, 5, 7 …

# node-b.cnf
server-id               = 2
auto_increment_increment = 2
auto_increment_offset    = 2        # 生成 2, 4, 6, 8 …
```

原因：主主拓扑下两个节点**都可能写入**（例如 A 挂掉期间 B 接管、A 恢复后两队各自收到请求）。
若两边自增都从 1 开始，同一时刻插入的两条记录会拿到**相同主键**，
复制到对端时撞主键 → **复制线程直接中断**，需要人工介入修复。

错开之后，两个节点生成的主键天然不重叠，代价只是主键出现空洞（1,3,5 与 2,4,6），
**这个代价远小于一次复制中断**。

### 4.3 GTID 与「恢复后自动追平」

```ini
gtid_mode                    = ON
enforce_gtid_consistency     = ON
binlog_format                = ROW          # GTID 的硬性前提（STATEMENT 格式不允许）
log_replica_updates          = ON           # ★ 链式复制关键，见下
relay_log_recovery           = ON
```

`setup-replication.sh` 建立复制时使用：

```sql
CHANGE REPLICATION SOURCE TO
  SOURCE_HOST='<对端IP>',
  SOURCE_PORT=3306,
  SOURCE_USER='repl',
  SOURCE_PASSWORD='***',
  SOURCE_AUTO_POSITION=1,        -- ★ 自动追平的核心
  GET_SOURCE_PUBLIC_KEY=1;       -- ★ MySQL 8 caching_sha2_password 非 SSL 连接所需
```

- **`SOURCE_AUTO_POSITION=1`（GTID 自动定位）**：
  复制起点按「已执行事务集合」自动协商，而不是靠人工记录的 binlog 文件名+位点。
  备机宕机数日后重启、或发生过主从切换，都能**自动从断点继续**。
  若用传统位点方式，备机停机超过 `binlog_expire_logs_seconds`（默认 7 天）后，
  起点 binlog 已被清理，就**只能重建备机** —— 这是 GTID 在本场景最大的价值。
- **`GET_SOURCE_PUBLIC_KEY=1`**：MySQL 8 默认认证插件是 `caching_sha2_password`，
  非 SSL 连接下必须允许向主节点索取 RSA 公钥来加密口令，
  否则报 `Authentication plugin 'caching_sha2_password' reported error`。

> **`log_replica_updates=ON` 是最隐蔽的一处坑**：漏配时表现为
> 「A→B 正常、B→A 静默不通」，而 `SHOW REPLICA STATUS` 仍显示两个 Yes。
> 原因是 B 从 A 收到的事务没有写进 B 自己的 binlog，因此无法再转发给 A。
> 本手册通过**共用配置文件**从根上避免了漏配。

### 4.4 主主复制是「双写」还是「单写」

本系统采用**单写**语义（推荐）：同一时刻只有一个节点接收写流量（经 `VIP_DB`），
主主复制在这里的作用是**热备 + 快速切换**，而不是「两端同时双写」。

好处：
- 不存在「同一行两端同时改」的语义冲突；
- 自增错开只是**兜底**（防止故障期间的意外并发写撞主键），不是主设计；
- 切换回来后无需数据对账。

### 4.5 建立复制（两台各执行一次）

```bash
cd /opt/ticket-system/deploy

# ① 先启动容器（含 mysql），主节点追加 --profile backup
docker compose --env-file .env.ha -f docker-compose.ha.yml up -d --build

# ② 两台都执行：本机 ← 对端
bash scripts/setup-replication.sh
```

脚本做的是「本机 → 对端」这一半，因此**两台都执行完才是双向**。
它是**幂等**的：复制断开、口令轮换、误配之后，重跑即可修好，无需手工敲 SQL。

### 4.6 校验

```bash
# 在两台上各执行，两台都应为 Yes / Yes
docker exec -it ticket-mysql mysql -uroot -p'<口令>' -e "SHOW REPLICA STATUS\G" \
  | grep -E 'Replica_IO_Running|Replica_SQL_Running|Seconds_Behind_Source|Last_.*_Error'
```

**双向复制的端到端验证**（只在一台建表、另一台看得到，才叫通了）：

```bash
# 在 A 执行
docker exec -it ticket-mysql mysql -uroot -p'<口令>' \
  -e "CREATE DATABASE IF NOT EXISTS repl_probe; CREATE TABLE IF NOT EXISTS repl_probe.t(id INT PRIMARY KEY); INSERT INTO repl_probe.t VALUES (1);"

# 在 B 执行 —— 应能查到那一行
docker exec -it ticket-mysql mysql -uroot -p'<口令>' -e "SELECT * FROM repl_probe.t;"

# 反向再来一次（B 插、A 查），然后清理
docker exec -it ticket-mysql mysql -uroot -p'<口令>' -e "DROP DATABASE repl_probe;"
```

> ⚠️ **清理一定要用 `DROP DATABASE`/`DELETE`，绝不要用 `TRUNCATE` + 复制的方式做验证** ——
> 本系统演示数据以 `administrator`（id=1）为锚点，误清会破坏演示环境。

---

## 五、Redis 哨兵

### 5.1 为什么后端零改动

`backend` 的 `RedisConfig` 使用的是 Spring Boot **自动装配**的 `RedisConnectionFactory`。
只要环境变量 `SPRING_DATA_REDIS_SENTINEL_NODES` 非空，
Spring Boot 就会**自动切换**到哨兵连接工厂（`RedisSentinelConfiguration`）。

因此 **`application-prod.yml` 与 Java 代码一行都不用改** ——
这是把哨兵做成「纯配置层能力」的关键，也是「后端无状态、可双跑」的一部分。

```yaml
# docker-compose.ha.yml 中注入，无需改代码
SPRING_DATA_REDIS_SENTINEL_MASTER: ${REDIS_SENTINEL_MASTER:-mymaster}
SPRING_DATA_REDIS_SENTINEL_NODES:  ${REDIS_SENTINEL_NODES}     # ip:26379,ip:26379,ip:26379
SPRING_DATA_REDIS_SENTINEL_PASSWORD: ${REDIS_SENTINEL_PASSWORD}
SPRING_DATA_REDIS_PASSWORD:          ${REDIS_PASSWORD}          # 数据节点口令
```

### 5.2 `announce-ip` 必须是宿主 IP

```conf
sentinel announce-ip <REDIS_ANNOUNCE_IP>      # = 该节点宿主的业务 IP
sentinel announce-port 26379
```

哨兵之间、哨兵与后端之间是**跨主机**通信。
若把 `announce-ip` 填成容器内网地址（如 `172.18.0.5`），
出了这台机器就**不可路由** —— 切换后客户端会从哨兵拿到一个**连不上的主节点地址**，
表现为「哨兵说切换成功了，但应用一直连不上」。
`docker-compose.ha.yml` 里已把 `REDIS_ANNOUNCE_IP` 设为必填（`${VAR:?}`）。

### 5.3 哨兵数量必须是奇数且 ≥ 3

哨兵判定「主节点客观下线」需要**多数票**（quorum）。

| 哨兵总数 | quorum | 单机故障时能否自动切换 |
|---------|--------|---------------------|
| 2（两机各一） | 2 | ❌ 故障机自己也有一票，剩下 1 票 < 2 |
| 2（两机各一） | 1 | ⚠️ 能切换，但**可能脑裂**（分区时两边各认为自己是主） |
| **3**（两机 + 第三台/第三个容器） | 2 | ✅ 推荐 |

**实践建议**：本手册的 compose 在两台机上各起一个哨兵（共 2 个），
**生产环境请再加第三个哨兵**（可跑在轻量机、甚至第三台 NAS 上的容器里），
把 `REDIS_SENTINEL_QUORUM=2`、`REDIS_SENTINEL_NODES` 补成三个地址。

> 若暂时只有两台机，请把 `REDIS_SENTINEL_QUORUM` 设为 `1`
> 并**明确接受脑裂风险**：宁可自动切换（把脑裂当作可容忍的短期状态），
> 也不要「整机故障时不切换」。选择哪一边，取决于业务是更怕「不可用」还是更怕「双主」。

### 5.4 哨兵的配置只在首次生成

`redis/sentinel-entrypoint.sh` 只在 `$DATA_ROOT/sentinel/sentinel.conf` **不存在时**生成配置。
原因：**哨兵运行期会重写自己的配置文件**（记录当前主节点、纪元号等）。
若每次容器启动都覆盖，哨兵会退回「初始主」认知，
从而在每次容器重建后**触发一次错误的二次切换**。

需要**重置**拓扑认知时才删：

```bash
rm -f ${DATA_ROOT}/sentinel/sentinel.conf
docker compose --env-file .env.ha -f docker-compose.ha.yml up -d --force-recreate redis-sentinel
```

### 5.5 校验

```bash
# 查看哨兵认知的主节点地址（应指向当前真实的 Redis 主节点宿主 IP）
docker exec -it ticket-sentinel redis-cli -p 26379 -a '<哨兵口令>' \
  SENTINEL get-master-addr-by-name mymaster

# 查看主从信息
docker exec -it ticket-redis redis-cli -a '<口令>' INFO replication \
  | grep -E 'role|connected_slaves|master_host|master_link_status'
```

---

## 六、NAS 共享存储

### 6.1 为什么附件必须共享

应用是**双机双跑、VIP 漂移**的。若附件存在本机磁盘：

> 用户在 A 机上传的文件，**漂移到 B 机后下载会 404** ——
> 而且这个 404 **只在切换之后才出现**，事后极难联想到「附件没共享」。

因此两台机必须把**同一个 NAS 共享目录**挂到**同一个容器内路径**：

```yaml
# 两机一致
- ${SHARED_ROOT}/attachments:/data/attachments
- ${SHARED_ROOT}/exports:/data/exports
```

日志**留本机**（`${DATA_ROOT}/logs`）—— 两台同时写同一个日志文件会产生交错与截断。

### 6.2 与系统参数的优先级（易踩！）

系统参数 `storage_attachment_path` 若被设置过，会**优先于**环境变量 `ATTACHMENT_STORAGE_ROOT`。

**本手册推荐：HA 部署下不要设置该参数**，让存储路径完全由环境变量决定
（`ATTACHMENT_STORAGE_ROOT=/data/attachments`，指向 NAS 挂载点）。

否则会出现「在参数页改了路径、文件却仍写在本机」——
现象是 A 机上传正常、漂移到 B 机 404，与「没挂 NAS」一模一样，但原因完全不同。

```bash
# 确认当前生效值
docker exec -it ticket-backend env | grep -E 'ATTACHMENT_STORAGE_ROOT|EXPORT_STORAGE_ROOT'
# 若参数库里被设过，需清掉（走系统参数页，而不是直接改库）
```

### 6.3 挂载 NAS

```bash
# 两台上各执行（需 root）
sudo bash scripts/mount-nas.sh
# 卸载：sudo bash scripts/mount-nas.sh --umount
```

脚本按 `SHARED_TYPE` 分支：

**NFS**（默认选项 `vers=4.1,hard,timeo=600,retrans=2,_netdev`）

- **`hard` 而非 `soft`**：软挂载（`soft`）在 NAS 抖动时会向应用返回 `EIO`，
  表现为「附件随机读失败」—— 比短暂卡顿难排查得多。硬挂载会持续重试。
- `_netdev`：声明依赖网络，避免开机时网络未就绪就挂载。

**CIFS**

- 口令写入 `/etc/ticket-cifs.credentials`（600 权限），**不放 `-o` 参数里**：
  `mount` 的命令行会被同机其它用户通过 `ps` 看到，也会进入 shell 历史。
- `uid=10001,gid=10001`：让 CIFS 侧呈现的文件属主**正好等于容器内运行用户的 uid**，
  否则容器写附件时会被判为「他人文件」而拒绝。

### 6.4 开机自动挂载（持久化）

`mount-nas.sh` 只做**本次**挂载。开机自动挂载需写入 `/etc/fstab`：

```bash
# NFS
echo '10.0.0.30:/volume1/ticket-shared  /mnt/ticket-shared  nfs  vers=4.1,hard,timeo=600,retrans=2,_netdev  0 0' | sudo tee -a /etc/fstab

# CIFS
echo '//10.0.0.30/ticket-shared  /mnt/ticket-shared  cifs  credentials=/etc/ticket-cifs.credentials,vers=3.0,uid=10001,gid=10001,file_mode=0640,dir_mode=0750,_netdev  0 0' | sudo tee -a /etc/fstab

# 验证（先 dry-run，不要直接 reboot）
sudo mount -a && findmnt /mnt/ticket-shared
```

### 6.5 备份也必须落共享盘

`BACKUP_DIR` 指向 NAS 上的独立目录（与生产数据**物理分离**，且可被异地同步带走）。
**本机磁盘故障正是双机高可用要防的场景之一** —— 备份若只在故障机本地，等于没有备份。

### 6.6 校验

```bash
# 两台上都应看到同一个挂载源
findmnt -n -o SOURCE,FSTYPE /mnt/ticket-shared

# 跨机可见性验证：A 机写、B 机读
echo "ha-probe-$(date +%s)" | sudo tee /mnt/ticket-shared/attachments/.probe >/dev/null
# 在 B 机上
cat /mnt/ticket-shared/attachments/.probe
sudo rm -f /mnt/ticket-shared/attachments/.probe
```

---

## 七、keepalived VIP 与边缘入口

### 7.1 安装（两台各执行一次）

```bash
cd /opt/ticket-system/deploy/ha
sudo bash scripts/install-keepalived.sh
```

脚本做的事：

1. 读 `.env.ha`，把 `keepalived/keepalived.conf.tmpl` 渲染成 `/etc/keepalived/keepalived.conf`（含
   `notify_master` / `notify_backup` / `notify_fault` 三个钩子，指向 `notify-ha.sh`）；
2. 安装健康检查脚本 `check-ticket.sh` 与**切换通知脚本** `notify-ha.sh`（均 `700 root:root`，
   满足 `enable_script_security`）；
3. 生成 `/etc/keepalived/ticket-ha.env`（供检查脚本读 `WEB_PORT`，供通知脚本读 `HA_DIR`
   以定位 `scripts/ha-heartbeat.sh`）；
4. `keepalived -t` 语法自检 → 启动并设为开机自启。

> **切换通知是怎么连到系统的**：keepalived 判定状态变化 → 调 `notify-ha.sh` →
> 它再用 `--event SWITCHOVER` 调 `scripts/ha-heartbeat.sh` → POST 到本机
> `/api/internal/ha/report` → 页面「最后切换时间」更新并通知全部超管。
> 因此 `deploy/ha` 目录**安装后不可移动**（`HA_DIR` 记在 `ticket-ha.env` 里）。
> `notify_fault` 只记日志、**不上报**「切换发生」—— 本机放弃 VIP 时接管方尚未确定，
> 谎报会把「本机不健康」说成「对端已接管」；真正的切换由接管方自己的 `notify_master` 上报。

> **为什么要模板渲染而不是维护两份手写配置**：两份手写配置 90% 内容相同，
> 任何改动（换网卡、调超时、改 VIP）都要改两处，**漏改一处不报错**，
> 表现为「一台正常、另一台行为诡异」。模板 + 单一数据源（`.env.ha`）从根上消除这个风险。

### 7.2 两侧都写 `state BACKUP`

```conf
vrrp_instance VI_WEB {
    state BACKUP          # ★ 两侧都写 BACKUP
    nopreempt             # ★ 不抢占
    priority 150          # A=150 / B=100
    ...
}
```

配合 `nopreempt`，避免「主机修好后**抢回** VIP」造成的**第二次中断** ——
对用户而言，一次故障只应打断一次。谁是主由 `priority` 决定，发生漂移后不会自动回切。

> ⚠️ **`nopreempt` 仅在 `state BACKUP` 下有效**；写成 `MASTER` 会被 keepalived 忽略并告警。
> 要「挪回」VIP，需在对端执行 `switchover.sh to-peer`（见 ）。

### 7.3 单播 VRRP

```conf
unicast_src_ip <本机IP>
unicast_peer { <对端IP> }
```

默认的 VRRP 多播（`224.0.0.18`）常被交换机 IGMP snooping 或云安全组拦掉，
现象是「两台都认为自己是主」从而**同时持有同一个 VIP（脑裂）**。
单播只在一对已知地址之间通信，可控且便于在防火墙上**精确放行**。

### 7.4 健康检查：探什么、怎么探

```conf
vrrp_script chk_ticket {
    script "/etc/keepalived/check-ticket.sh"
    interval 5
    timeout 4
    weight -30            # 失败降权 30（150→120、100→70）
    fall 2                # 连续 2 次失败才判故障
    rise 2                # 连续 2 次成功才判恢复
}
```

`check-ticket.sh` 探测的是：

```sh
curl -fsS --max-time 3 "http://127.0.0.1:${WEB_PORT}/api/health"
```

**为什么经 Nginx 打后端、而不是单探 Nginx**：

> 单探 Nginx 会在「Nginx 活着但后端已死」时仍判健康 ——
> 用户拿到一堆 503，VIP 却**不切换**。

经 Nginx → 后端 `health` 的完整链路探测，才能覆盖「应用不可用」这一真正需要切换的场景。

**`--max-time 3` 必须小于 keepalived 的 `timeout 4`**：
脚本被 keepalived 强杀时，日志里只剩「脚本超时」，看不到真正的失败原因，排障会被误导。

另外 `check-ticket.sh` 支持一个**维护标记**：

```bash
# 存在 /etc/keepalived/MAINT 时一律判不健康（用于计划内维护 / 切换演练）
touch /etc/keepalived/MAINT
```

### 7.5 两个 `virtual_router_id` 必须避开同网段其它集群

```bash
HA_VRRP_ROUTER_ID_WEB=51
HA_VRRP_ROUTER_ID_DB=52
```

同网段内 `virtual_router_id` 撞车，会让**两套 keepalived 集群互相抢 VIP**，
现象是「VIP 反复漂移、网站间歇性不可用」，而且**日志上看不出是邻居家的集群干的**。

### 7.6 VRRP PASS 认证上限 8 字符

```bash
HA_VRRP_AUTH_PASS=xxxxxxxx    # ≤ 8 字符！
```

keepalived 的 `PASS` 认证**上限 8 字符，超长会被静默截断**。
两台机若一条被截断、一条没有（例如长度刚好跨过边界），
就会出现「只有一台认得对方」→ 双方互相忽略报文 → **脑裂**。
`install-keepalived.sh` 与 `preflight-ha.sh` 都会在 > 8 时直接报错。

### 7.7 重载配置

```bash
# 改完 .env.ha 后，重跑安装脚本即可（它会重新渲染并 restart）
sudo bash scripts/install-keepalived.sh

# 只重载不重启（改的只是一些运行参数时）
sudo systemctl reload keepalived

# 查看状态与日志
systemctl status keepalived
journalctl -u keepalived -f
ip -4 addr show <iface> | grep inet        # 看 VIP 在不在本机
```

---

## 八、启动与验证

### 8.1 完整启动顺序（两台都执行）

```bash
cd /opt/ticket-system/deploy/ha

# 1) 挂载 NAS（必须在起容器之前，见  的说明）
sudo bash scripts/mount-nas.sh

# 2) 部署前体检：变量 / 跨机一致性 / 共享存储 / 权限 / 工具链 / 对端连通性
bash scripts/preflight-ha.sh

# 3) 启动容器
#    节点 A（主，含备份）：
docker compose --env-file .env.ha -f docker-compose.ha.yml --profile backup up -d --build
#    节点 B（备，不含备份）：
docker compose --env-file .env.ha -f docker-compose.ha.yml up -d --build

# 4) 等各容器 healthy 后，建立 MySQL 主主复制（两台都执行）
bash scripts/setup-replication.sh

# 5) 接入 VIP 漂移（两台都安装完，再做切换演练）
#    同时装上切换通知钩子 —— 切换发生后通知管理员靠它（见 ）
sudo bash scripts/install-keepalived.sh

# 6) 安装心跳上报 —— 页面上「节点状态 / 最后心跳 / 同步延迟」全部由它更新
#    不装这一步，页面会一直显示「未知」，切换也不会产生任何通知
sudo bash scripts/install-ha-heartbeat.sh
```

### 8.2 验证（按顺序，不要跳）

```bash
# ① 容器状态：mysql / redis / redis-sentinel / backend / frontend / nginx 全部 healthy
docker compose --env-file .env.ha -f docker-compose.ha.yml ps

# ② 后端健康 + 经 Nginx 的完整链路
curl -sS http://127.0.0.1:8080/api/health

# ③ VIP 归属（在持有 VIP 的机器上执行）
sudo bash scripts/switchover.sh status

# ④ 从【外层反代所在机器】或任一客户端，经 VIP 访问
curl -sS http://<VIP_WEB>:8080/api/health

# ⑤ MySQL 双向复制（两台上都应为 Yes/Yes）
docker exec -it ticket-mysql mysql -uroot -p'<口令>' -e "SHOW REPLICA STATUS\G" \
  | grep -E 'Running|Behind|Last_.*_Error'

# ⑥ Redis 哨兵认知的主节点
docker exec -it ticket-sentinel redis-cli -p 26379 -a '<哨兵口令>' \
  SENTINEL get-master-addr-by-name mymaster

# ⑦ 附件跨机可见（ 的探针）
# ⑧ 浏览器走外层反代的 https://域名/ 登录，确认 Cookie 三属性
#    DevTools → Application → Cookies → TICKET_TOKEN：HttpOnly ✅ / Secure ✅ / SameSite=Lax
# ⑨ 心跳上报在跑（装了  第 6 步之后）
systemctl is-active ticket-ha-heartbeat
journalctl -u ticket-ha-heartbeat -n 5 --no-pager
#    然后打开「系统设置 → 主备配置」刷新：节点状态应为「运行中 / 待命」，
#    「最后心跳」开始跳动。若仍是「未知」，见  的排查表
```

### 8.3 双机一致性验收

| 检查项 | 期望 | 命令 |
|--------|------|------|
| 两个 VIP 都有归属（各一台） | 一个在 A、一个在 B，或都在 A | `switchover.sh status`（两机各跑一次） |
| MySQL 双向复制 | 两台均 `IO=Yes, SQL=Yes` | `SHOW REPLICA STATUS` |
| Redis 主从 | 一主一从，`master_link_status:up` | `INFO replication` |
| 附件共享 | 两机 `findmnt` 源相同，探针可跨机读 |  |
| 密钥一致 | 两机指纹一致 | `preflight-ha.sh` 第二节 |
| 会话无状态 | 登录后刷新/换机访问不掉线 | 浏览器 |
| 心跳上报 | 两机服务均 active，页面节点状态不是「未知」 | `systemctl is-active ticket-ha-heartbeat` |

---

## 九、切换演练

### 9.1 用「维护标记」演练，而不是停 keepalived

```bash
# 在【当前持有 VIP 的那台】执行
sudo bash scripts/switchover.sh to-peer    # 交出 VIP，交给对端
#   · 创建 /etc/keepalived/MAINT → 健康检查判失败 → 降优先级 → 对端接管
#   · 约 10~15 秒后脚本会校验 VIP 是否已离开本机

sudo bash scripts/switchover.sh status     # 确认归属
sudo bash scripts/switchover.sh back       # 取消标记（本机恢复可竞选）
```

> **为什么不直接停 keepalived**：停进程演练的是「一个特例」；
> 维护标记演练的是**完整判定链路**（健康检查 → 降权 → 对端接管），
> 更接近真实故障，也能验证 `check-ticket.sh` 本身是否有效。

### 9.2 演练观察清单

| 观察点 | 期望 | 说明 |
|--------|------|------|
| 浏览器访问 | 约 15 秒内恢复 | 若超 30 秒，检查 `advert_int` 与 `fall×interval` |
| 已登录会话 | **保持登录** | 会话在 Redis、令牌在 Cookie，切换不失效 |
| 正在提交的请求 | 可能失败一次，重试成功 | 这是合理的：切换瞬间连接会断 |
| 附件下载 | 正常 | 验证 NAS 共享生效 |
| MySQL 复制 | 切换后仍 Yes/Yes | 检查 `Seconds_Behind_Source` 是否回落到 0 |
| Redis 哨兵 | 未触发切换（若只是 Web 漂移） | Redis 与 Web 是**独立**的故障域 |

### 9.3 特殊场景：Redis 主节点所在机故障

此时会看到**两级切换同时发生**：
1. `VIP_WEB` 漂移（keepalived）；
2. Redis 哨兵把从节点提升为主（5~30 秒）。

**注意**：Session 存于 Redis，哨兵切换期间**部分临时锁会失效**（属预期内）。
用户体感通常是「卡一下，然后继续用」，若表现为「被登出」，检查：
- 两机 `JWT_SECRET` 是否一致（不一致会验签失败 → 看起来像被登出）；
- `REDIS_SENTINEL_NODES` 是否两机都配了全部哨兵地址。

### 9.4 恢复回原状（把 VIP 挪回 A）

由于 `nopreempt`，A 修复后**不会自动抢回**。需在 B 上执行：

```bash
# 在 B 执行：把 VIP 交还给 A
sudo bash scripts/switchover.sh to-peer
```

---

## 十、日常运维与滚动升级（ 前提）

### 10.1 日常巡检（建议每日 / 每周）

```bash
# 每日
sudo bash scripts/switchover.sh status                  # VIP 归属
systemctl is-active ticket-ha-heartbeat                 # 心跳上报是否在跑（页面上的节点状态靠它）
journalctl -u ticket-ha-heartbeat -n 3 --no-pager
docker compose --env-file .env.ha -f docker-compose.ha.yml ps
docker exec ticket-mysql mysql -uroot -p'<口令>' -e "SHOW REPLICA STATUS\G" | grep -E 'Running|Behind'
findmnt -n -o SOURCE,FSTYPE /mnt/ticket-shared

# 每周
bash scripts/preflight-ha.sh                            # 全量体检（含跨机指纹比对）
ls -lh <BACKUP_DIR> | tail                              # 备份产物与时间
```

### 10.2 备份策略（HA 版）

- **只在主节点启用 `backup` profile**：数据经主主复制本就一致，
  双份备份只是浪费 NAS 空间与 IO，且同名归档会在同秒生成时相互覆盖。
- 备机 cron 错峰（`HA_BACKUP_CRON_BACKUP_NODE=30 3 * * *`），
  避免与附件保留期清理（每日 03:00）同时扫全表。
- 备份容器 `MYSQL_HOST=${VIP_DB}`，**VIP 漂移后备份自动跟随**新的数据库主节点，无需改脚本。
- 主机长期故障时，可在备机临时启用该 profile 顶上：
  ```bash
  docker compose --env-file .env.ha -f docker-compose.ha.yml --profile backup up -d backup
  ```

### 10.3 恢复

沿用单机版 `scripts/restore.sh`（见 README ）。
**HA 场景下的额外注意**：恢复会覆盖数据库，
因此恢复前**必须先停掉两端的写流量**（可先 `switchover.sh to-peer` 把业务集中到一端，
再停另一端；或直接在应用层停服），否则恢复的数据会立刻被复制覆盖。

### 10.4 滚动升级顺序（★  的执行前提）

> 沙箱/离线环境无法真实重启进程，因此**真机上的升级顺序**由本节规定，
>  的「在线一键升级」脚本将按此顺序编排。

**核心原则：先备机，后主机；升级完一端、验证通过，再升另一端。全程不中断服务。**

```bash
# 前置：确认当前 VIP 归属（假设都在 A，A 是活跃节点、B 是备）
sudo bash scripts/switchover.sh status

# ---------- 第 1 步：把 VIP 挪到 B，让 A 变成「离线可操作」 ----------
# 在 A 执行（把 VIP 交给 B）
sudo bash scripts/switchover.sh to-peer
# 验证业务仍可用（此刻由 B 提供服务）
curl -sS http://<VIP_WEB>:8080/api/health

# ---------- 第 2 步：升级 A（此刻 A 不承载流量）----------
# 在 A 执行：拉取新代码/新镜像 → 重建容器
cd /opt/ticket-system && git pull
cd deploy/ha
docker compose --env-file .env.ha -f docker-compose.ha.yml up -d --build backend frontend nginx
# 若含数据库变更，Flyway 会在 backend 启动时自动迁移（注意：迁移应向前兼容，见下）

# ---------- 第 3 步：验证 A 的新版本 ----------
docker compose --env-file .env.ha -f docker-compose.ha.yml ps
curl -sS http://127.0.0.1:8080/api/health

# ---------- 第 4 步：把 VIP 挪回 A，让 B 变成「离线可操作」 ----------
# 在 B 执行（把 VIP 交还给 A）
sudo bash scripts/switchover.sh to-peer
curl -sS http://<VIP_WEB>:8080/api/health          # 由 A 提供服务

# ---------- 第 5 步：升级 B（与第 2 步相同操作，在 B 上执行）----------
# 在 B 执行
cd /opt/ticket-system && git pull
cd deploy/ha
docker compose --env-file .env.ha -f docker-compose.ha.yml up -d --build backend frontend nginx

# ---------- 第 6 步：验收 ----------
# 两端版本一致、复制正常、两 VIP 归属符合预期
for h in <A_IP> <B_IP>; do curl -sS "http://$h:8080/api/health"; done
```

**滚动升级的三个硬约束**：

1. **数据库迁移必须向前兼容（expand-contract）**：
   升级一端时，另一端仍跑旧代码连同一个库。
   因此 `V__` 迁移只能**加**（加表、加列、加索引），
   **不能**在同一个版本里「改名 / 删列 / 改类型」—— 后者要拆成两次发布
   （先加新列双写 → 全端升级完 → 再删旧列）。
2. **不能同时升级两端**：那样等于「单机升级 + 一次停机」，失去了 HA 的意义。
3. **升级顺序不可颠倒**：必须先把 VIP 从「要升级的机器」挪走再动它，
   否则升级过程中该机器既在承载流量、又在重建容器 → 用户可见的失败窗口。

### 10.5 回滚

```bash
# 镜像级回滚：把 .env.ha 的 IMAGE_TAG 改回旧版本号，然后用旧镜像重建
docker compose --env-file .env.ha -f docker-compose.ha.yml up -d --build backend frontend nginx

# 代码级回滚
cd /opt/ticket-system && git checkout <上一个版本 tag>
```

> **数据库迁移不可自动回滚**。若新版本已执行了破坏性迁移，回滚代码不等于回滚数据 ——
> 这正是第 1 条约束要求「迁移必须向前兼容」的原因。

### 10.6 计划内维护

```bash
# 若需要一次性停掉某个 VIP 的对外服务（例如换证书）
sudo touch /etc/keepalived/MAINT      # 本机主动让位（健康检查判失败）
# 维护完成后
sudo rm -f /etc/keepalived/MAINT
```

---

## 十一、在线一键升级（）

> 顺序规则见 （**先备机、后主机；动谁之前先把 VIP 从它身上挪走**）。
> 本节说明工具链怎么用、以及哪些事它**做不到**。
>
> 四个环节的分工：
>
> | 环节 | 由谁完成 | 产物 |
> |------|---------|------|
> | 打包 | `deploy/scripts/make-package.sh` | `ticket-<version>.zip` |
> | 校验 / 备份 / 落 staging | 后端 `/system/upgrade` 页（仅超管） | 任务记录 + `staging/<taskNo>` + `backup/<taskNo>` |
> | 替换产物 / 重启 / 失败回滚 | `deploy/ha/scripts/upgrade-apply.sh` | 结果回执 `state/result/<taskNo>.json` |
> | 双机编排（先备机后主机） | `deploy/ha/scripts/rolling-upgrade.sh` | 两端升级 + VIP 归属报告 |

### 11.1 升级包格式（不可变更的契约）

zip 的**根目录**下只放这三样，多一个文件、少一层目录、或条目名带上 `./` 前缀都会被拒：

```
manifest.json              清单：version / buildTime / files[]
backend.jar                后端产物（Spring Boot fat jar，文件名固定）
frontend/dist/**           前端构建产物
```

`manifest.json` 里的 `files[]` 必须**恰好**覆盖包内除它自己以外的全部文件。
后端做的是**双向**一致性校验：声明了却没在包里 → 拒；在包里却没声明 → **也拒**。
只做单向会让「悄悄多塞一个文件」成为绕过手段 —— 声明项的哈希全对，多出来的那个照样会被解压出去。

`version` 必须匹配 `^[0-9A-Za-z][0-9A-Za-z._-]{0,63}$`。
不允许空格 / 斜杠 / 引号，是因为版本号会被外部 shell 脚本拼进路径与参数。

> **向前兼容要求**：`manifest.json` 允许出现未知字段（后端配了 `ignoreUnknown`）。
> 这条是必需的 —— 新打包脚本迟早会往清单里加字段（构建流水号、提交号……），
> 而升级的旧后端只认识老字段。不允许未知字段的话，一个完全正常的新包会因为
> 「多了个字段」被旧后端拒绝，而那恰恰是最需要升级成功的时刻。

### 11.2 打包

```bash
# 前端与后端产物都需要是【本次要发布的那一版】
cd frontend && npm run build && cd ..
cd backend  && "<mvn.cmd>" -DskipTests package && cd ..

bash deploy/scripts/make-package.sh 1.5.0
#   → deploy/packages/ticket-1.5.0.zip
#   输出包大小与包 SHA-256，请记下后者
```

打包器会自动：挑 `backend/target` 下最大的 jar（Spring Boot repackage 后 fat jar 最大）→
统一改名为 `backend.jar` → 逐文件算 SHA-256 写进清单 → 打 zip →
**解开自查一遍**条目是否全在白名单内（把「上传后才被拒」提前到打包机上暴露）。

### 11.3 上传与「就绪待应用」

以超管登录 → 系统管理 → **在线升级** → 拖入 zip → 「上传并校验」。

后端依次做：大小上限 → zip 合法性 → 逐条目路径安全（Zip Slip）→ 白名单 →
条目数与解压体积上限（zip 炸弹）→ manifest 可解析且版本号合法 →
**双向**一致性 → 每文件 SHA-256 与大小匹配 → 备份当前产物 → 解压到 `staging/<taskNo>`。

全过程通过后状态停在 **`READY_TO_APPLY`（已就绪待应用）**。
**这是后端职责的终点、运维视角的起点** —— 后端不会去替换自己正在运行的 jar
（Windows 上文件被占用直接失败；Linux 上替换成功但已加载的类不会重新载入，仍须重启）。

> 上传后请把页面显示的**包 SHA-256** 与打包时输出的那行逐字符比对。
> 不一致说明传输过程出了问题 —— 换一个包重传，而不是重试同一个包。

### 11.4 应用：交给进程之外的脚本

`app.upgrade.apply-command` 指向 `upgrade-apply.sh`：

```bash
# 关键：让脚本成为【独立单元】，而不是后端进程的子进程（原因见下方 ⚠️）
UPGRADE_APPLY_COMMAND='systemd-run --unit=ticket-upgrade-{taskNo} --collect --no-block /opt/ticket/bin/upgrade-apply.sh {taskNo} {stagingDir} {backupDir}'
```

可用占位符：`{taskNo}` `{backupDir}` `{stagingDir}`（替换后自动加引号）。
只写命令**本体** —— Windows 上后端会自动加 `cmd.exe /c`，类 Unix 上加 `/bin/sh -c`。

> ⚠️ **systemd cgroup 陷阱（务必读）**
> 后端以 systemd 服务运行时（默认 `KillMode=control-group`），它派生的子进程
> **仍在同一个 cgroup 里**。重启服务时 systemd 会把整个 cgroup 一起杀掉 ——
> 包括这条升级脚本自己，结果是「脚本被自己发起的重启杀死，替换做到一半」。
> 因此必须用 `systemd-run --unit=... --no-block` 让脚本脱离当前 cgroup。
> `--no-block` 同时让 `systemd-run` 立即返回，不会把后端请求线程拖住。
>
> Docker 部署时用 `docker compose up -d --force-recreate` 重建容器，
> 容器由 compose 的重启策略托管，不存在这个问题。

### 11.5 结果回执与启动对账

脚本把结论写到 `state/result/<taskNo>.json`：

```json
{"taskNo":"20261001103000-a1b2c3d4","result":"SUCCESS","message":"…","finishedAt":"2026-10-01T11:02:33"}
```

`result` 取值：`SUCCESS` / `ROLLED_BACK` / `FAILED`。
**成功与失败都必须写** —— 漏写会让任务一直停在 `APPLYING` 直到超时，
而后端早已重启完成，管理员看到的是「升级中」的假象。

后端在**启动时**对账（`UpgradeRecoveryRunner`），依据两条**互相独立**的证据：

1. 结果回执 `state/result/<taskNo>.json`；
2. `state/current.json` 的版本是否已等于 `targetVersion`。

第 2 条覆盖的是「脚本在写回执前就被重启杀掉」这一经典情形。
两者都没有、且已超过 `apply-timeout-minutes` 才判 `FAILED`（否则会误杀正在跑的脚本）。
另外，停在处理中状态（`PENDING`/`VALIDATING`/`BACKING_UP`/`STAGING`）且已过期的**僵尸任务**
也会被判 `FAILED` —— 否则它会永远占住活跃唯一键，让后续升级全部被拒。

> **共享存储要求**：`app.upgrade.storage-root` 必须落在两台共享的 NAS 路径上
> （与附件同盘）。这样 staging 与**结果回执**对两端都可见，
> 对端后端重启后能直接读到本机脚本写的回执，启动对账才不会误判超时。

### 11.6 回滚

```bash
sudo bash /opt/ticket/bin/upgrade-apply.sh <taskNo> <stagingDir> <backupDir>   # 脚本自动回滚
# 或在页面上点「回滚」（后端直接还原当前产物目录）
```

脚本的回滚是三步且顺序固定：
① 把**当前**产物复制到 `backup/<taskNo>-pre-rollback/` 留存现场；
② 清空当前产物目录；③ 把备份复制回去。
任何一步失败，第 ① 步留存的那份都能人工还原。

> **回滚后仍需重启**：还原的是磁盘文件，运行中的进程加载的仍是旧字节码。
> 页面上会明确提示这一点。
>
> **首次部署没有备份是正常的**：那时还没有「上一版产物」，备份步骤会跳过。
> 此时 `upgrade-apply.sh` 会自己把当前产物 `cp -a` 到 `state/pre-apply-<taskNo>/`，
> 否则「首次上线」这一次升级就是无退路的。

### 11.7 双机滚动升级

```bash
# 在【任意一台】执行，脚本自己判断谁持有 VIP
sudo bash /opt/ticket/ha/scripts/rolling-upgrade.sh <taskNo> <stagingDir> [<backupDir>]
```

执行顺序（与  同一套规则）：

1. 分发升级包到对端（`UPGRADE_SYNC_MODE=shared` 时 staging 在 NAS 上共享，跳过复制）；
2. **升级备机**（此刻不承载流量，零风险）；
3. 把 VIP 从主机交给刚升级好的备机；
4. **升级（原）主机**（此刻它已不承载流量）；
5. 验收。

> **包若是坏的，第 2 步就会失败退出**，此时**一次 VIP 切换都没发生**，用户完全无感。
> 这正是把「先升备机」放在「先切 VIP」前面的原因（ 的示例是先切后升）。

**演练**：在任意一台机器上先看一遍决策顺序，不需要 keepalived：

```bash
DRY_RUN=1 UPGRADE_FORCE_ROLE=holder  HA_PEER_IP=<对端IP> HA_NODE_IP=<本机IP> HA_NODE_NAME=ticket-a   bash scripts/rolling-upgrade.sh <taskNo> <stagingDir>
DRY_RUN=1 UPGRADE_FORCE_ROLE=standby HA_PEER_IP=<对端IP> HA_NODE_IP=<本机IP> HA_NODE_NAME=ticket-a   bash scripts/rolling-upgrade.sh <taskNo> <stagingDir>
```

`UPGRADE_FORCE_ROLE` **只能与 `DRY_RUN=1` 同用**（脚本会强制校验）：
真实升级里若允许指定角色，等于绕过了「VIP 确实挪走了」这道校验，而那正是它存在的意义。

### 11.8 环境变量速查

`.env.ha`（后端以 `UPGRADE_*` 注入）：

| 变量 | 默认 | 说明 |
|------|------|------|
| `UPGRADE_ENABLED` | `false` | 总开关。**生产必须显式打开**；关闭时所有升级接口直接拒绝 |
| `UPGRADE_STORAGE_ROOT` | `./data/upgrade` | 升级工作区（staging / backup / state / tmp）。HA 下必须在共享 NAS 上 |
| `UPGRADE_CURRENT_ARTIFACT_DIR` | `./data/upgrade/current` | 当前生效产物目录（备份来源）。刻意与 storage-root 分离 |
| `UPGRADE_PACKAGE_MAX_SIZE_MB` | `300` | 升级包大小上限（与 multipart 上限形成双层防护） |
| `UPGRADE_EXTRACT_MAX_SIZE_MB` | `800` | 解压后总大小上限（zip 炸弹）。**必须在读取过程中累计并提前中断** |
| `UPGRADE_MAX_ENTRIES` | `5000` | 包内文件条目数上限（防 inode 打爆） |
| `UPGRADE_APPLY_COMMAND` | 空 | 外部应用命令。**留空 = 停在 READY_TO_APPLY 交给运维手动应用**（这是合法形态） |
| `UPGRADE_ROLLBACK_ENABLED` | `true` | 页面「回滚」按钮可用性 |
| `UPGRADE_APPLY_TIMEOUT_MINUTES` | `30` | 超过此时长仍未见回执/版本变化 → 启动对账判 `FAILED` |
| `UPGRADE_HISTORY_LIMIT` | `50` | 历史列表条数上限 |

`upgrade-apply.sh` 另用：

| 变量 | 默认 | 说明 |
|------|------|------|
| `UPGRADE_ARTIFACT_DIR` | **必填** | 当前生效产物目录（宿主路径） |
| `UPGRADE_STATE_DIR` | **必填** | 后端 state 目录，用于写结果回执 |
| `UPGRADE_DEPLOY_MODE` | `none` | `docker` / `systemd` / `none` |
| `UPGRADE_RESTART_CMD` | 空 | `deploy-mode=none` 时的重启命令 |
| `UPGRADE_HEALTH_URL` | `http://127.0.0.1:8080/api/health` | 健康检查地址 |
| `UPGRADE_HEALTH_TIMEOUT` / `_INTERVAL` | `180` / `5` | 健康检查总超时 / 间隔（秒） |
| `UPGRADE_COMPOSE_DIR` / `_FILES` / `_SERVICES` | — | docker 模式：compose 工作目录 / 文件 / 需重建的服务 |
| `UPGRADE_SYSTEMD_UNIT` | — | systemd 模式：服务单元名 |
| `DRY_RUN` | `0` | `1` = 只打印计划，不做任何写操作 |

`rolling-upgrade.sh` 另用 `UPGRADE_SYNC_MODE`（`shared` / `ssh` / `none`）、
`UPGRADE_PEER_SSH`、`UPGRADE_PEER_STAGING_DIR`、`VIP_SETTLE_SECONDS`、`UPGRADE_RESTORE_VIP`。

### 11.9 这道闸门能防什么、不能防什么

**能防**：传输 / 存储过程中产生的损坏（大文件传输中断、磁盘坏块、被中间环节重新编码）。
这是真实且高频的一类，后果是「换上一个起不来的进程」，而且往往发生在深夜没人盯着的时候。
**校验和是唯一能在替换前发现它的手段。**

**不能防**：一个已经拿到「上传升级包」权限的人 —— 上传者正是有权限执行升级的人，
他完全可以自己造一个格式完备、哈希自洽的包。所以包整体 SHA-256 的价值是
**审计留痕**（事后能回答「当时上的是哪一个包」），而不是防篡改。

真正的边界在三处，缺一不可：

1. `UPGRADE_ENABLED` **默认关闭**；
2. 权限码 `system:upgrade:execute` **只归超管**（不在 admin 默认权限集里）；
3. 「替换 + 重启」交给**受控的 root 编排脚本**，而不是让应用进程自己动手。

### 11.10 升级检查清单

**升级前**

- [ ] `UPGRADE_ENABLED=true`，`UPGRADE_STORAGE_ROOT` 指向共享 NAS（两台一致）
- [ ] `UPGRADE_APPLY_COMMAND` 已配置且用 `systemd-run --unit=... --no-block`（systemd 部署）
- [ ] 数据库迁移**向前兼容**（expand-contract：只能加表/加列/加索引，不能改名/删列/改类型）
- [ ] 打包输出的 SHA-256 已记录；包内 `version` 与发布单一致
- [ ] `sudo bash scripts/preflight-ha.sh` 与备份任务刚刚通过

**升级中**

- [ ] 按  /  的顺序：**先备机、后主机**，一端验证通过再动另一端
- [ ] 每一步之后 `sudo bash scripts/switchover.sh status` 确认 VIP 归属符合预期
- [ ] 页面上传后的包 SHA-256 与打包输出**逐字符一致**

**升级后**

- [ ] 两端 `state/current.json` 的 `version` 一致，且等于目标版本
- [ ] `SHOW REPLICA STATUS` 两向 `Running: Yes`、`Seconds_Behind_Source` 归零
- [ ] 浏览器用真实账号走一遍提交 / 审批 / 归还 / 导出
- [ ] `operation_logs` 里能看到 `UPGRADE_*` 动作（谁在什么时候上了哪个包）
- [ ] `staging/` 与 `backup/` 按需清理（备份建议保留一个发布周期）

---

## 十二、故障排查

### 12.1 按现象查

| 现象 | 最可能的原因 | 排查命令 |
|------|-------------|---------|
| 网站间歇性不可用、VIP 反复漂移 | `virtual_router_id` 与同网段其它集群撞车 / 脑裂 | `journalctl -u keepalived -f`；`tcpdump -i <iface> vrrp` |
| 两台**都**持有同一个 VIP | 单播配置错误 / PASS 口令被截断（>8 字符）/ 防火墙拦了 VRRP | 比对两机 `keepalived.conf`；检查 `HA_VRRP_AUTH_PASS` 长度 |
| 切换后**所有人掉线** | 两机 `JWT_SECRET` 不一致 | `preflight-ha.sh` 第二节比对指纹 |
| 切换后**部分附件 404** | NAS 未挂 / 两机挂的不是同一个共享 / `storage_attachment_path` 参数覆盖了环境变量 | 、 |
| 切换后**连不上数据库** | VIP 漂移了但后端连的是旧地址的缓存 DNS / 防火墙未放行 3306 | `docker exec ticket-backend env \| grep MYSQL` |
| `SHOW REPLICA STATUS` 两个 Yes 但数据不同步 | `log_replica_updates` 未开（链式复制） | `SELECT @@log_replica_updates` 应为 1 |
| 复制报主键冲突 | 两机自增未错开 / 有过直接写非主节点 | `SELECT @@auto_increment_increment, @@auto_increment_offset` |
| 复制报 `caching_sha2_password` 错误 | 缺 `GET_SOURCE_PUBLIC_KEY=1` | 重跑 `setup-replication.sh` |
| Redis 哨兵切换后应用连不上 | `REDIS_ANNOUNCE_IP` 填了容器地址 | `SENTINEL get-master-addr-by-name mymaster` 应返回**宿主 IP** |
| 哨兵整机故障不切换 | 哨兵数 < 3 |  |
| 上传附件报权限错误 | NAS 上文件属主不是 uid 10001 | `ls -ln <SHARED_ROOT>/attachments`；重跑 `mount-nas.sh` |

### 12.2 日志位置

```bash
docker compose --env-file .env.ha -f docker-compose.ha.yml logs -f backend
docker compose --env-file .env.ha -f docker-compose.ha.yml logs -f mysql
journalctl -u keepalived -n 100 --no-pager
${DATA_ROOT}/logs/nginx/access.log                # 宿主机上的 Nginx 日志
docker exec ticket-sentinel redis-cli -p 26379 -a '<口令>' INFO sentinel
```

### 12.3 复制中断的快速修复

`setup-replication.sh` 是幂等的 —— 大多数复制问题**重跑它即可收敛**：

```bash
# 两台各执行一次
bash scripts/setup-replication.sh
```

若重跑仍失败，按报错定位：

```bash
docker exec -it ticket-mysql mysql -uroot -p'<口令>' -e "SHOW REPLICA STATUS\G" | grep -i error
# 典型：主键冲突 → 需人工跳过或数据对齐（属数据层面的问题，先备份再处理）
#      认证失败 → 检查 MYSQL_REPL_PASSWORD 两机是否一致
```

### 12.4 脑裂的事后处理

若确认发生过脑裂（两台都当过主、各自写入了不同数据），
**不要盲目直接建立复制**（会互相覆盖）。正确顺序：

1. **先停业务写入**（`switchover.sh to-peer` 不行就停容器）；
2. 用 `mysqldump` 导出两端，**人工比对差异**；
3. 选定一端为唯一真相源，把另一端的差异数据补过去；
4. 再 `RESET REPLICA ALL` + `setup-replication.sh` 重建复制；
5. 复盘 `virtual_router_id` / 单播 / PASS 长度 / 防火墙这几处。

---

## 十三、上线检查清单

### 部署前

- [ ] 两台机器时间同步已开启（`timedatectl` 显示 synchronized）
- [ ] 两节点 IP / 网卡名 / 优先级已确认，VIP 与节点 IP 不冲突
- [ ] `.env.ha` 两侧仅「节点身份」段不同，其余**逐字一致**
- [ ] `JWT_SECRET` ≥ 32 字节，且两机指纹一致
- [ ] `INTERNAL_ALERT_TOKEN` 两机指纹一致
- [ ] `MYSQL_ROOT_PASSWORD` / `REDIS_PASSWORD` / `MYSQL_REPL_PASSWORD` 两机一致
- [ ] `HA_VRRP_AUTH_PASS` ≤ 8 字符
- [ ] 两个 `virtual_router_id` 已与同网段其它集群核对，无撞车
- [ ] 防火墙：3306 / 6379 / 26379 / VRRP(112) 仅对端节点与运维网段放行，**未暴露公网**
- [ ] NAS 已挂载且写入 `/etc/fstab`，两机挂载源相同
- [ ] `preflight-ha.sh` 通过（0 失败）
- [ ] keepalived 已安装在两台上，且开机自启

### 部署后

- [ ] 所有容器 healthy（mysql / redis / redis-sentinel / backend / frontend / nginx）
- [ ] MySQL 双向复制两台均 `IO=Yes / SQL=Yes`，`Seconds_Behind_Source` 归零
- [ ] 端到端复制验证已做（A 建表 B 可见、B 插入 A 可见，随后清理）
- [ ] Redis 哨兵 `get-master-addr-by-name` 返回**宿主 IP**
- [ ] 附件跨机探针验证通过（A 写、B 读）
- [ ] 两个 VIP 归属符合预期（`switchover.sh status` 两机各跑一次）
- [ ] 经 `VIP_WEB` 的浏览器访问正常，Cookie 三属性（HttpOnly / Secure / SameSite=Lax）齐备
- [ ] 首次登录触发强制改密 → 强制绑定联系方式（双闸门）行为正常
- [ ] 主节点 `backup` profile 已启用，备机未启用

### 演练与运维

- [ ] 切换演练做了一次（`to-peer` → 浏览器验证 → `back`），恢复时间符合预期
- [ ] 已演练「Redis 主节点所在机故障」这一复合场景（）
- [ ] 备份产物确已落 NAS，且能在另一台机器上看到
- [ ] 恢复流程（`restore.sh`）已做演练（建议每季度一次）
- [ ] **滚动升级顺序已固化：先备机后主机（）**，数据库迁移遵守 expand-contract
- [ ] 值班人员已掌握 `switchover.sh` 三个子命令与  排障表

---

## 十四、主备配置可视化页与脚本的对应关系

页面位置：**系统设置 → 主备配置**（`/system/ha`）。

权限码 `ha:view`（看）/ `ha:manage`（做），**只归超级管理员**，不下发给业务管理员 ——
这个页面能把虚拟 IP 从一台机器挪到另一台，等价于决定全公司的系统由哪台机器提供服务。

> 这一节的用途：**看清页面上的每一个按钮，在服务器上究竟动了什么。**
>  的痛点正是「维护人员不会写 `.env.ha`、不知道该执行哪个脚本」——
> 页面负责生成片段与给出顺序，真正的动作仍然发生在目标机器上。

### 14.1 页面元素 ↔ 接口 ↔ 服务器侧资产

| 页面元素 | 后端接口 | 在服务器上对应什么 |
|---------|---------|------------------|
| 状态总览（开关 / 当前角色 / 访问地址 / 部署资产 / 执行模式） | `GET /api/ha/overview` | 读 `ha_config` + `ha_node`，并对 `deploy/ha` 做**探针**（只看文件在不在，不执行） |
| 第 1 步「启用主备与虚拟 IP」保存 | `PUT /api/ha/config` | **只写库**。不会再自动去改目标机上的 keepalived 配置 |
| 第 2 步「添加备节点」 | `POST /api/ha/nodes` | **只写库**。登记之后系统开始等该节点上报心跳 |
| 节点列表（状态 / 最后心跳） | `GET /api/ha/overview` | 由 `ha-heartbeat.sh` 上报驱动（见 14.2） |
| 数据同步（状态 / 延迟 / 最后同步时间） | 同上 | 由 `ha-heartbeat.sh` 查 `SHOW REPLICA STATUS` 后上报 |
| 「立即同步」 | `POST /api/ha/sync` | `scripts/setup-replication.sh`（**不是** switchover.sh） |
| 「本机让出 / 切回本机 / 查询维护标记」 | `POST /api/ha/switchover?action=…` | `scripts/switchover.sh to-peer / back / status` |
| 第 3 步「部署指引」（环境变量片段 / keepalived 配置） | `GET /api/ha/guide` | 由 `keepalived/keepalived.conf.tmpl` 渲染；`deploy/ha` 不在时只出环境变量片段 |
| （无按钮）切换发生后通知管理员 | `POST /api/internal/ha/report`（内部通道） | `keepalived/notify-ha.sh`，由 keepalived 的 notify 钩子调用 |

**为什么「保存配置」不直接去改目标机上的 keepalived**：
后端进程没有目标机器的 root 通道，也不该凭数据库里的一行值改写线上网络配置。
真实生效必须走第 3 步（`preflight-ha.sh` → `setup-replication.sh` → `install-keepalived.sh`
+ `install-ha-heartbeat.sh`），**由人在目标机器上以 root 执行** ——
这一步刻意不做自动化：改错的代价是整站不可达。

### 14.2 心跳与切换的两条链路

```
【心跳 / 同步状态】—— 每 5 秒一条
  ha-heartbeat.sh（systemd 单元 ticket-ha-heartbeat）
      │  ① 读本机网卡 → 是否持有 VIP_WEB → 角色 MASTER / STANDBY
      │  ② 每 60 秒查一次 SHOW REPLICA STATUS → IN_SYNC / LAGGING / FAILED
      ▼
  POST http://127.0.0.1:8080/api/internal/ha/report      ← 【本机环回，不走 VIP】
      ▼
  ha_node（状态 / 最后心跳）  +  ha_config（同步状态 / 延迟 / 最后同步时间）

【切换事件】—— 切换的那一刻
  keepalived 状态变化（notify_master / notify_backup）
      ▼
  /etc/keepalived/notify-ha.sh  →  ha-heartbeat.sh --event SWITCHOVER --role …
      ▼
  同一条内部通道 → ha_config.last_switch_at + 站内消息 / 邮件通知全部超管
```

两条链路**都打本机环回地址**：心跳要证明的是「本机后端活着」。
若走 VIP，本机后端已经死了而 VIP 恰好在对端时，请求会由对端后端应答 ——
脚本以为上报成功，页面上一切正常，而实际上这台机器已经完全不可用。

> ⚠️ `deploy/ha` 目录安装后**不可移动或删除**：`notify-ha.sh` 要借它定位
> `scripts/ha-heartbeat.sh`（路径记录在 `/etc/keepalived/ticket-ha.env` 里）。
> 移动后切换通知会静默失效 —— 只有 `journalctl -t ticket-ha` 里会留下一行找不到脚本的日志。

### 14.3 排障：页面上四种「不对劲」的对应查法

| 现象 | 先查这里 |
|------|---------|
| 节点状态一直「未知」、最后心跳为空 | `systemctl status ticket-ha-heartbeat`；再手工跑 `bash scripts/ha-heartbeat.sh --once` 看报错。**最常见的是两台机器的 `INTERNAL_ALERT_TOKEN` 不一致 → 上报被 401 拒绝** |
| 上报返回 403 且响应里是 `CSRF_HEADER_MISSING` | **不要查令牌**，这是请求头少了一个：服务端 `CsrfHeaderFilter` 对所有非 GET 请求都要求 `X-Requested-With: XMLHttpRequest`，**内部通道不在豁免名单里**。脚本已同时带 `X-Requested-With` 与 `X-Internal-Token`（与 `deploy/backup/backup.sh` 同一约定），若被改坏就照此补回 |
| 节点在「运行中」与「异常」之间反复闪 | 心跳间隔 ≥ 页面上的「心跳超时」阈值。正确做法是**把阈值调大**（默认 10 秒偏紧，生产建议 30 秒），而不是只把脚本间隔调大 |
| 同步延迟永远是「—」 | 说明本次上报没带同步信息。心跳脚本查不到复制状态时会**如实留空**（`docker compose ps` 看 mysql 是否在跑、`MYSQL_ROOT_PASSWORD` 是否配了）；它**不会**用 `0` 或「失败」冒充 |
| 切换发生了但没收到通知 | `journalctl -t ticket-ha`；并确认 `install-keepalived.sh` 是**装了 notify 钩子的那版**：`grep notify /etc/keepalived/keepalived.conf` |

### 14.4 演练模式（`HA_DRY_RUN`）

页面上的「本机让出 / 切回本机 / 立即同步」在 `HA_DRY_RUN=true`（**默认**）下
只返回**将要执行的命令**，不会真的执行 —— 响应里 `dryRun=true`，
页面会明确写「未执行」并把命令展示出来供复制到目标机器上手工跑。

沙箱环境、以及服务器上尚未配置 `app.ha.deploy-dir` 的环境，都属于这一形态。

要在页面上真的执行，必须在目标机器上显式配置 `HA_DRY_RUN=false`。
**这是有意的安全默认值**：一个「点一下就把生产流量换到另一台机器」的按钮，
默认状态就应该是「不真的做」。

> 另有一个必须知道的边界：**心跳上报不受演练模式影响**。
> 它是只读的可观测性（读网卡、查复制状态、发一个 POST），
> 不改变任何东西，因此在演练模式下照常工作 —— 否则页面在演练期会一片「未知」，
> 反而没法用来确认准备是否就绪。

---

## 附：HA 资产清单

```
deploy/ha/                          （共 20 个文件）
├── .env.ha.example                 # 环境变量模板（单机版 .env 的超集，含 UPGRADE_* 与心跳节奏）
├── docker-compose.ha.yml           # 主备双机编排（两台共用一份）
├── mysql/conf.d/
│   ├── 10-replication-common.cnf   # 两机共用：log-bin / GTID / ROW / log_replica_updates
│   ├── node-a.cnf                  # 身份：server-id=1、自增偏移=1
│   └── node-b.cnf                  # 身份：server-id=2、自增偏移=2
├── redis/
│   ├── redis-entrypoint.sh         # 数据节点：按 REDIS_REPLICAOF 决定初始主从
│   └── sentinel-entrypoint.sh      # 哨兵：首次生成 sentinel.conf（含 announce-ip）
├── keepalived/
│   ├── keepalived.conf.tmpl        # 模板：2 个 vrrp_instance（VI_WEB / VI_DB）+ VI_WEB 的 notify 钩子
│   ├── check-ticket.sh             # 健康检查：经 Nginx 探后端 /api/health
│   └── notify-ha.sh                # 【】切换通知：由 notify 钩子调用，上报切换事件
├── systemd/
│   └── ticket-ha-heartbeat.service # 【】心跳上报单元（常驻 / 开机自启 / 崩溃自动拉起）
└── scripts/
    ├── mount-nas.sh                # 挂载 NAS（NFS / CIFS 分支）
    ├── preflight-ha.sh             # 部署前体检（六段，含跨机指纹比对）
    ├── setup-replication.sh        # 建立 MySQL 主主复制（幂等，两台各跑一次）
    ├── install-keepalived.sh       # 渲染并安装 keepalived + 通知脚本（两台各跑一次）
    ├── switchover.sh               # 切换演练 / 手动切换（to-peer / back / status）
    ├── ha-heartbeat.sh             # 【】心跳 / 同步状态 / 切换事件上报（页面数据的来源）
    ├── install-ha-heartbeat.sh     # 【】安装心跳上报单元（两台各跑一次）
    ├── upgrade-apply.sh            # 【】单节点：替换产物 → 重启 → 健康检查 → 失败回滚
    └── rolling-upgrade.sh          # 【】双机编排：先备机、后主机（含 DRY_RUN 演练）
```

复用的单机版资产（**未修改**，HA 与单机两套拓扑行为一致）：

```
deploy/
├── mysql/conf.d/my.cnf             # 基础调优（被挂为 00-base.cnf）
├── nginx/                          # 限流 zone / 真实 IP / 429·503 JSON 兜底
├── backup/                         # 备份镜像与脚本
└── scripts/
    ├── preflight.sh                # 部署前体检
    ├── smoke-test.sh               # 一键冒烟
    ├── restore.sh                  # 恢复（HA 场景见 ）
    └── make-package.sh             # 【】打升级包（含结构自检与 SHA-256 输出）
```

---

> **相关文档**
> - 单机部署：`deploy/README.md`
> - 一键冒烟：`deploy/scripts/smoke-test.sh`
> - 在线一键升级：见 （打包 → 上传校验 → 就绪待应用 → 应用/回滚 → 双机滚动）