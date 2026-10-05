# 群晖 DSM 反向代理接入（HTTPS 在 DSM 侧终止）

> 本文对应「应用容器只监听 80（HTTP），由外层反向代理处理 HTTPS」的部署方式。
> 目标：`https://ticket.你的域名.com` → DSM 反向代理 → `http://127.0.0.1:8080` → 边缘 Nginx 容器。
> 全程**不需要**为证书开放容器内的 443，也不需要把证书文件塞进镜像。

---

## 一、为什么用 DSM 反代而不是容器内做 TLS

| 关注点 | DSM 反代（推荐） | 容器内 TLS |
|---|---|---|
| 证书续期 | DSM 自动（Let's Encrypt） | 要自己挂证书 + 自己写续期钩子 |
| 私钥分布 | 只在 NAS 一处 | 多一份在容器卷里 |
| 端口冲突 | DSM 的 443 本来就开着 | 需要额外映射 443，常与 DSM 打架 |
| 排查 | DSM 图形界面看得见 | 只能翻容器日志 |

结论：把证书生命周期交给 DSM，容器只管跑业务。

---

## 二、准备工作

1. **确认应用已启动**，且只监听高位端口：

   ```bash
   sudo docker compose ps          # 在 deploy 目录执行
   curl -s http://127.0.0.1:8080/api/health
   ```

   期望看到 `{"code":"SUCCESS","data":{"status":"UP",...}}`。
   若此处不通，先解决再配反代 —— 反代只是转发，不会替你修好后端。

2. **确认域名解析**：把 `ticket.你的域名.com` 的 A 记录指向 NAS 的公网 IP
   （内网使用可指向 NAS 内网 IP，并跳过 Let's Encrypt 步骤改用内部 CA / 自签证书）。

3. **确认 8080 未被 DSM 占用**：
   控制面板 → 登录门户 → 确认「DSM」「Web Station」等未使用 8080。
   （DSM 默认占用的是 5000/5001 与 80/443，8080 通常空闲。）

---

## 三、创建反向代理（DSM 7.x）

**控制面板 → 登录门户 → 高级 → 反向代理 → 新增**

### 来源（用户看到的入口）

| 项 | 值 |
|---|---|
| 协议 | `HTTPS` |
| 主机名 | `ticket.你的域名.com` |
| 端口 | `443` |
| 启用 HTTP/2 | ✅ 勾选（浏览器并发更好） |

### 目标（转发到应用）

| 项 | 值 |
|---|---|
| 协议 | `HTTP` |
| 主机名 | `localhost` |
| 端口 | `8080` |

> ⚠️ 主机名填 `localhost` 而不是容器名：DSM 的反代跑在 NAS 宿主机上，
> 它访问的是 compose 映射到宿主机的 8080，**不认识** Docker 内部的服务名。

### 自定义标题（关键，别跳过）

点击「自定义标题」→「新增」旁边的下拉选 **`创建 → WebSocket`** 会添加 WebSocket 支持的头，
但**必须额外手工添加下面这个头**（DSM 不会自动加）：

| 标题名称 | 标题值 |
|---|---|
| `X-Forwarded-Proto` | `https` |

为什么必须加：应用侧要通过 `X-Forwarded-Proto` 才知道用户实际走的是 HTTPS。
缺失时的表现是「一切正常，但后端认为你在用 HTTP」——
最直接的后果是 Cookie 的 `Secure` 判定与后续任何绝对链接都按 http 生成。

> `X-Forwarded-For` 与 `X-Real-IP` 由 DSM 内置的 nginx 自动写入，**不需要**手工添加。
> 它们正是应用判定真实客户端 IP 的依据，因此 `TRUSTED_PROXIES` 必须包含 NAS 的内网地址
> （默认值已覆盖 `10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16`，通常无需修改）。

---

## 四、签发证书（Let's Encrypt）

**控制面板 → 安全性 → 证书 → 新增 → 添加新证书 → 从 Let's Encrypt 获取证书**

- 域名：`ticket.你的域名.com`
- 电子邮件：你的运维邮箱
- 勾选「设为默认证书」可选，但**务必**在该证书的「设置」里把
  `ticket.你的域名.com` 绑定到上一步创建的反向代理服务。

### 前置条件

- NAS 的 **80 端口必须能从公网访问**（Let's Encrypt 的 HTTP-01 校验需要）。
  DSM 的 80 通常被「Web Station / 登录门户」占用 —— 这没关系，
  只要把域名解析到该 NAS 且 80 可达即可完成校验。
- 若 80 无法对外开放，改用 DNS-01 校验（DSM 原生不支持，需在别处签发后导入证书）。
- 内网部署（域名只在内网解析）无法使用 Let's Encrypt，改用自签证书并让客户端信任根证书。

### 续期

DSM 会自动续期。可在「安全性 → 证书」看到到期时间。
**建议**：在日历上设一个到期前 30 天的人工检查提醒 —— 自动续期失败时，
DSM 只会在通知中心留一条容易被忽略的消息。

---

## 五、验证（逐条对照，别跳）

### 1. 后端是否识别到真实客户端 IP 与 HTTPS

```bash
curl -s https://ticket.你的域名.com/api/health
```

期望（关键字段）：

```json
{
  "code": "SUCCESS",
  "data": {
    "status": "UP",
    "clientIp": "你的真实出口IP",
    "scheme": "https",
    "secure": true,
    "trustedProxyRules": 5
  }
}
```

| 字段 | 不符合时的含义 | 处理 |
|---|---|---|
| `clientIp` 是 NAS 内网 IP | 反代没传 XFF，或 `TRUSTED_PROXIES` 没覆盖 NAS 网段 | 检查反代配置；在 `.env` 的 `TRUSTED_PROXIES` 中追加 NAS 网段后 `docker compose up -d backend` |
| `scheme` 是 `http` | 缺少 `X-Forwarded-Proto` 自定义标题 | 回到第三节补上 |
| `trustedProxyRules` 是 `0` | `TRUSTED_PROXIES` 配置为空 | 检查 `.env` |

### 2. 登录链路

浏览器访问 `https://ticket.你的域名.com`：

- 用超管 `administrator` 登录，应被强制跳转到修改密码页（首次登录）
- 打开 DevTools → Application → Cookies，确认 `TICKET_TOKEN` 同时有
  **HttpOnly ✅**、**Secure ✅**、**SameSite=Lax**

### 3. 限流是否生效

连续快速提交 6 次错误密码（或在 DevTools Console 里循环调用登录接口），
应在第 4~6 次开始收到 **429**，且提示带明确秒数（如「该账号登录请求过于频繁，请 58 秒后重试」）。

> 提醒：这会触发「连续失败 5 次锁定 15 分钟」。测试请用专门的测试账号，别拿 `administrator` 试。

---

## 六、常见问题

**Q：浏览器报 `ERR_TOO_MANY_REDIRECTS`（重定向循环）**
DSM 反代里若把来源协议也配成 HTTP 且同时启用了「自动跳转 HTTPS」，
可能与应用的 401 跳转叠加。确认来源协议是 HTTPS，且目标协议是 HTTP。

**Q：接口返回 503 且响应体是 JSON 的「后端服务暂不可用」**
这是边缘 Nginx 的兜底错误页，说明它连不上 backend 容器。
执行 `docker compose ps` 看 backend 是否 healthy；不健康则 `docker compose logs --tail=100 backend`。

**Q：上传附件报 413**
DSM 反代的请求体上限。在反向代理的「自定义标题」里加：
`Proxy-buffer-size: 128k`、`Proxy-max-temp-file-size: 1024m`，
并确认边缘 Nginx 的 `client_max_body_size 50m`（本仓库已配）。

**Q：NAS 重启后容器没起来**
DSM 的 Container Manager 对 `restart: unless-stopped` 的支持取决于套件版本。
最稳的做法是在 Container Manager 的「项目」里导入本项目的 `docker-compose.yml`，
或配置「控制面板 → 任务计划 → 触发的任务 → 开机」执行：
`cd /volume1/docker/ticket-system/deploy && /usr/local/bin/docker compose up -d`
