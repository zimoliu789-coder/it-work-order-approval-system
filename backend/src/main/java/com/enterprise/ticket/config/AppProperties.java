package com.enterprise.ticket.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 业务自定义配置绑定（对应 application.yml 中的 {@code app.*}）。
 */
@Data
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Jwt jwt = new Jwt();
    private Security security = new Security();
    private Cors cors = new Cors();
    private SuperAdmin superAdmin = new SuperAdmin();
    private Demo demo = new Demo();
    private Attachment attachment = new Attachment();
    private Export export = new Export();
    private Site site = new Site();
    private Ops ops = new Ops();
    private Upgrade upgrade = new Upgrade();
    private Ha ha = new Ha();
    private Backup backup = new Backup();
    private ProdInit prodInit = new ProdInit();

    /**
     * JWT 配置（：优先使用 HttpOnly、Secure、SameSite 安全 Cookie；：JWT_SECRET 由 .env 注入）
     */
    @Data
    public static class Jwt {
        /** 签名密钥，生产必须由 JWT_SECRET 环境变量注入，长度 >= 32 字节 */
        private String secret;
        /** 默认有效期（分钟），运行期以 system_config.jwt_expire_minutes 为准 */
        private int expireMinutes = 720;
        /** Cookie 名称 */
        private String cookieName = "TICKET_TOKEN";
        /** 是否仅 HTTPS 传输，生产置 true */
        private boolean cookieSecure = false;
        /** SameSite 策略，默认 Lax（CSRF 第一层防护） */
        private String cookieSameSite = "Lax";
        /** Cookie 作用路径 */
        private String cookiePath = "/";
    }

    /**
     * 安全相关配置
     */
    @Data
    public static class Security {
        /** CSRF 第二层防护：非 GET 请求必须携带该请求头 */
        private String csrfHeaderName = "X-Requested-With";
        private String csrfHeaderValue = "XMLHttpRequest";
        /** 免认证白名单 */
        private List<String> permitAll = new ArrayList<>();
        /** 强制改密状态下仍可访问的接口 */
        private List<String> forceChangePasswordWhitelist = new ArrayList<>();

        /**
         * 未绑定手机 / 邮箱时仍可访问的接口（ ）。
         *
         * <p>与 {@link #forceChangePasswordWhitelist} 分开配置而不是共用一份：两者的
         * 最小必要集并不相同 —— 强制改密阶段用户已能进系统，无需再放行「绑定联系方式」；
         * 而绑定阶段用户需要先改完密码（所以它必然包含改密的几条）。
         * 合成一份会让「改密后仍被要求绑定」这类顺序问题重新变得不可表达。
         */
        private List<String> contactBindWhitelist = new ArrayList<>();

        /**
         * 可信反向代理网段（：Nginx / 群晖反代）。
         *
         * <p>只有来自这些地址的请求，其 {@code X-Forwarded-For} / {@code X-Real-IP} /
         * {@code X-Forwarded-Proto} 才会被采信；其余一律以 TCP 对端地址为准。
         *
         * <p><b>默认空 —— 这是 fail-closed 的安全默认值。</b>本系统为内网直连部署
         * （Windows 非 Docker，无反向代理）。若默认把 {@code 10/8、172.16/12、192.168/16}
         * 这些内网网段当作可信代理，则<b>任何一台内网客户端</b>（其 TCP 对端恰好落在这些网段）
         * 都能自选 {@code X-Forwarded-For} 伪造身份，从而绕过 IP 限流、IP 封禁，并污染审计 IP。
         *
         * <p>确有反向代理时，必须用 {@code TRUSTED_PROXIES} 环境变量<b>精确</b>指定
         * 那一个代理地址（支持单个 IP 与 CIDR，逗号分隔），例如 {@code 127.0.0.1/32}。
         * 未配置时启动日志会给出 WARN 提示。
         */
        private List<String> trustedProxies = new ArrayList<>();
    }

    /**
     * 跨域配置：生产由 Nginx 同源代理，allowedOrigins 为空表示不开启跨域
     */
    @Data
    public static class Cors {
        private String allowedOrigins;
    }

    /**
     * 初始超级管理员配置（：生产环境不得硬编码默认密码）
     *
     * <h2>不再自动创建固定账号（本次改造）</h2>
     * <p>改造前：启动时若库中无 super_admin，则按 {@code SUPER_ADMIN_USERNAME}（默认
     * {@code administrator}）自动建号 —— 登录名写死、密码来自环境变量。
     *
     * <p>现在改为<b>两种入口</b>：
     * <ol>
     *   <li><b>初始化向导（推荐）</b>：库中没有 super_admin 时，前端引导到 {@code /setup}，
     *       由管理员现场设定登录名与密码；</li>
     *   <li><b>环境变量无人值守</b>：同时配置了 {@code SUPER_ADMIN_USERNAME} 与
     *       {@code SUPER_ADMIN_INIT_PASSWORD} 时，启动自动建号（用于自动化部署）。
     *       两者<b>都不再有任何默认值</b>。</li>
     * </ol>
     *
     * <p>超管的登录名在初始化那一刻被<b>固化</b>到 {@code system_config.super_admin_username}，
     * 之后不可修改、不可被重置（护栏见 {@code BuiltinAdmin} + {@code UserServiceImpl}）。
     */
    @Data
    public static class SuperAdmin {
        /**
         * 超管登录名。**默认留空** —— 不再写死 {@code administrator}。
         *
         * <p>留空时：库中已有超管则使用已固化的登录名；库中没有超管则走初始化向导。
         * 仅当同时提供了 {@code SUPER_ADMIN_INIT_PASSWORD} 时才会用它自动建号。
         */
        private String username = "";
        /** 超管展示名（仅自动建号时使用；向导由管理员自填） */
        private String displayName = "超级管理员";
        /** 自动建号用的初始密码；留空 = 不自动建号（改走向导） */
        private String initPassword;
    }

    /**
     * 生产初始化模式（本次新增）—— 一次性清空演示数据。
     *
     * <p>用于「先按 dev 跑通、再切生产」或「交付给客户前清库」：
     * 打开后启动时清空演示员工 / 部门 / 工单 / 设备 / 演示申请数据，
     * <b>保留</b>表结构、系统参数、权限目录与预置申请类型的表单与流程定义。
     *
     * <p>⚠️ 一次性：执行成功后在 {@code system_config} 里落一个完成标记，
     * 之后即便环境变量仍在也<b>不再执行</b> —— 否则忘记摘掉开关会让每次重启都清掉新数据。
     */
    @Data
    public static class ProdInit {
        /** 是否启用生产初始化清库（环境变量 {@code APP_PROD_INIT=true} 打开）。默认关闭。 */
        private boolean enabled = false;
    }

    /**
     * 演示数据配置（仅 dev profile 生效，见 {@code DemoDataInitializer}）
     *
     * <p>用于本地/评审环境一键得到「部门 + 最终处理部门 + 审批流程 + 演示员工」，
     * 便于走查 的配置界面；生产环境不加载该初始化器。
     */
    @Data
    public static class Demo {
        /** 是否初始化演示数据 */
        private boolean enabled = true;
        /** 演示员工初始密码；为空则只初始化分组/小组，不创建员工 */
        private String userInitPassword;

        /**
         * 是否初始化**演示申请配置**（演示「采购申请」类型 + 演示审批流程）。
         *
         * <p>默认 {@code false}。这些数据在**每次启动**都会被重建，与需求「删掉所有演示数据」
         * 直接冲突：只把库里的删掉、不关掉这里，下次启动就会自动还原
         * （V41 就是这么撞上「类型编码已存在」的）。
         *
         * <p>申请类型现已由 {@code PresetApplyInitializer} 按预置目录播种。
         * 需要重置演示环境（例如给流程设计器做演示）时，把
         * {@code app.demo.apply-config-enabled} 置为 true 再重启即可。
         */
        private boolean applyConfigEnabled = false;
    }

    /**
     * 附件上传配置（：存 NAS 本地磁盘，禁止大文件入库）
     *
     * <p>四项约束缺一不可：
     * <ol>
     *   <li>{@code storageRoot}——落盘根目录，开发指向项目内 {@code data/attachments}，
     *       生产指向 NAS 挂载点（容器内路径），由 {@code ATTACHMENT_STORAGE_ROOT} 注入；</li>
     *   <li>{@code maxSizeMb}——单文件上限，与 Spring 的 multipart 上限形成双层防护
     *       （multipart 拦在容器层，本项拦在业务层并给出规范错误码）；</li>
     *   <li>{@code allowedExtensions}——文档类白名单；</li>
     *   <li>{@code imageExtensions}——照片类附件（归还照片 / 故障照片）只能取此交集。</li>
     * </ol>
     */
    @Data
    public static class Attachment {
        /** 附件存储根目录（相对路径按进程工作目录解析；生产建议用绝对路径挂载 NAS） */
        private String storageRoot = "./data/attachments";
        /** 单文件大小上限（MB） */
        private int maxSizeMb = 10;
        /** 单条业务记录最多附件数（防单工单附件无限增长） */
        private int maxPerBiz = 10;
        /** 允许的扩展名（小写、不含点） */
        private List<String> allowedExtensions = new ArrayList<>(List.of(
                "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv",
                "png", "jpg", "jpeg", "gif", "bmp", "webp", "zip", "rar", "7z"));
        /** 图片类扩展名（照片类附件只能取此交集） */
        private List<String> imageExtensions = new ArrayList<>(List.of(
                "png", "jpg", "jpeg", "gif", "bmp", "webp"));
    }

    /**
     * Excel 导出配置
     *
     * <p>三项约束：
     * <ol>
     *   <li>{@code storageRoot}——异步导出文件的落盘根目录。与附件<b>分开</b>的原因：
     *       附件是用户上传的原始资料（长期保留），导出文件是系统生成的临时产物（有生命周期），
     *       混在一起会让「按过期时间清理」误伤附件；</li>
     *   <li>{@code asyncThreshold}——行数超过该值改为异步生成并站内消息通知（规范明确 10000）；</li>
     *   <li>{@code expireDays}——异步导出文件的保留天数，过期后下载返回明确错误码，
     *       磁盘文件由清理任务回收。</li>
     * </ol>
     */
    @Data
    public static class Export {
        /** 导出文件存储根目录（与附件分离，便于按生命周期清理） */
        private String storageRoot = "./data/exports";
        /** 行数阈值：超过此值改为异步生成 + 站内消息通知（ 定为 10000） */
        private int asyncThreshold = 10000;
        /** 单次导出允许的最大行数（防止把整库拖进内存；超出提示用户先筛选） */
        private int maxRows = 200000;
        /** 异步导出文件保留天数 */
        private int expireDays = 7;
        /**
         * 单个工作表的列数上限（仅自定义表单导出取用）。
         *
         * <p><b>为什么列也要有上限</b>：自定义表单导出的列 =「本批工单引用过的表单版本
         * 的字段并集」，而一个申请类型历史上可能有 v1…vN 多版表单，每版都可以新增字段。
         * 没有上限时，一个用了三年的类型足以产出几百列 —— 既没人看得懂，
         * 也会明显抬高内存与写出耗时（{@code SXSSFWorkbook} 的列宽表随列数线性增长）。
         * 超限时拒绝并提示按申请类型 / 时间缩小范围，而不是悄悄产出一张不可读的表。
         *
         * <p>默认 120：远大于正常表单的字段数（实测 7–25），又能挡住病态累积。
         */
        private int maxColumns = 120;
    }

    /**
     * 站点品牌配置（：系统名称与 logo 可配置）
     *
     * <p>{@code storageRoot}——上传的 logo 图片落盘根目录。
     * <b>刻意与附件目录分开</b>：附件目录会被 {@code AttachmentOrphanCleanupJob}
     * 按「文件名不在 attachments 表里」判为孤儿并删除，而 logo 文件不对应任何业务记录，
     * 放进附件目录会被定时任务当作垃圾清掉 —— 表现为「logo 过几小时自己消失」，
     * 属于最难排查的一类缺陷。
     */
    @Data
    public static class Site {
        /** logo 图片存储根目录（与附件 / 导出分离，避免被孤儿清理误删） */
        private String storageRoot = "./data/site";
        /** logo 图片单文件大小上限（MB） */
        private int logoMaxSizeMb = 10;
        /** logo 允许的图片扩展名（小写、不含点） */
        private List<String> logoExtensions = new ArrayList<>(List.of("png", "jpg", "jpeg"));
    }

    /**
     * 运维作业配置（本轮 Docker 部署 + 限流加固新增）
     *
     * <p>备份由独立容器内的 cron 执行，脚本跑在容器里、
     * 拿不到应用内部的消息服务，因此需要一个<b>内部告警入口</b>把结果回传给应用，
     * 由应用向超管发站内消息 —— 这正是「备份失败不能静默」的落地方式。
     */
    @Data
    public static class Ops {
        /**
         * 内部告警共享密钥（{@code INTERNAL_ALERT_TOKEN} 注入）。
         *
         * <p>为空时内部告警接口一律拒绝（fail-closed）——
         * 宁可「备份失败但发不出消息」，也不能留下一个谁都能调用、
         * 向全部超管灌消息的公开接口。
         */
        private String internalAlertToken;
    }

    /**
     * 应用内数据库自动备份配置（P0）。
     *
     * <h2>与 {@code deploy/backup} 备份容器的关系</h2>
     * <p>两者<b>互补，但不要同时开</b>：侧车做「数据库 + 附件 + 配置」三合一，
     * 面向 Docker 生产；本配置驱动的应用内备份只做<b>数据库</b>，面向非容器环境
     * （Windows 裸机 / 单机直跑）—— 那正是此前完全没有自动备份的场景。
     * 同时开启会让同一份数据存两处，且两条链路各自的「成功」会让人分不清
     * 哪一份才是权威归档。因此 {@code backup_enabled} 默认关闭。
     *
     * <h2>为什么 mysqldump 路径可配</h2>
     * <p>Windows 上 MySQL 客户端很少在 PATH 里（默认装在 {@code Program Files} 下），
     * 而容器/包管理器安装的又常有版本目录前缀。留空时按 PATH 查找；
     * 配了绝对路径则会在<b>备份开始前</b>校验其存在，把「找不到客户端」这类失败
     * 提前到一眼能看懂的位置，而不是让 mysqldump 启动时才吐出
     * 「系统找不到指定的文件」这种看不出配了哪个路径的信息。
     */
    @Data
    public static class Backup {

        /**
         * 备份文件存放目录的<b>默认值</b>；系统参数 {@code backup_dir} 留空时生效。
         *
         * <p>指向 NAS 挂载点是推荐用法（需求原话「备份文件存 NAS」）。
         * 之所以把「可改的入口」放在系统参数里而不是这里：换挂载点属于运维日常，
         * 不该要求改配置文件并重启。
         */
        private String dir = "./data/backup";

        /** mysqldump 可执行文件路径；留空表示使用系统 PATH 中的 mysqldump */
        private String mysqldump = "";

        /**
         * 单次导出超时（秒），默认 30 分钟。
         *
         * <p>超时后进程会被强制结束并判 FAILED —— 有明确终态比无限等待好：
         * 一条永远 RUNNING 的记录会把之后所有备份（含定时）全部挡掉。
         */
        private int timeoutSeconds = 1800;
    }

    /**
     * 在线一键升级配置。
     *
     * <h2>为什么 {@code enabled} 默认 false</h2>
     * <p>这个模块的接口能<b>替换服务器上的可执行文件与静态资源目录</b>，
     * 等价于「拿到这台机器的代码执行能力」。默认开启意味着任何一个超管账号被盗，
     * 攻击面就直接从「改数据」升级到「执行任意代码」。
     * 因此取向是 fail-closed：<b>要升级，必须先显式在部署配置里打开</b>，
     * 并且通常会同时提供 {@link #applyCommand}（把「替换 + 重启」这一步
     * 交给受控的 root 编排脚本）。关闭状态下接口直接返回「未启用」，
     * 而不是偷偷降级成「只校验不应用」—— 后者会让人以为已经升级了。
     *
     * <h2>为什么 applyCommand 允许为空</h2>
     * <p>为空时后端只做到「校验 → 备份 → 落 staging」，停在
     * {@code READY_TO_APPLY} 并明确告知「等待外部应用」。这不是降级，而是一种
     * <b>正式形态</b>：在没有 systemd / 没有 Docker 的环境（开发机、评审环境）里，
     * 由人手动把 staging 内容搬过去即可，而整条链路依然被完整校验与留痕。
     */
    @Data
    public static class Upgrade {
        /** 是否启用在线升级（生产默认关闭，需在部署配置里显式打开） */
        private boolean enabled = false;

        /**
         * 升级工作根目录。其下固定分四个子目录：
         * <pre>
         *   staging/&lt;taskNo&gt;/     新产物（解压后的 manifest.json + backend.jar + frontend/dist/**）
         *   backup/&lt;taskNo&gt;/      本次升级前的旧产物副本
         *   state/current.json    当前已应用版本描述（由外部脚本写，后端读来展示「当前版本」）
         *   state/result/&lt;taskNo&gt;.json  外部应用结果回执（后端启动时读）
         *   tmp/                  上传临时文件（校验通过后即删）
         * </pre>
         */
        private String storageRoot = "./data/upgrade";

        /**
         * 「当前生效产物」目录 —— 备份的来源。
         *
         * <p>在容器化部署里，它应当指向一个<b>同时挂进容器的宿主目录</b>
         * （例如 {@code /opt/ticket/current}，挂载为 backend 容器的 app jar 与
         * frontend 容器的 html 根）。指向不存在的目录不算错误：首次部署本来就没有
         * 「上一版产物」可备份，此时备份步骤会跳过并记一条提示。
         */
        private String currentArtifactDir = "./data/upgrade/current";

        /** 升级包单文件大小上限（MB）。与 multipart 上限形成双层防护 */
        private int packageMaxSizeMb = 300;

        /**
         * 解压后总大小上限（MB）—— 防「zip 炸弹」。
         *
         * <p>一个 1MB 的 zip 可以解出几十 GB（经典 42.zip 手法）。只限制压缩包大小
         * 完全挡不住它，因此必须在**解压过程中累计**并提前中断。
         */
        private long extractMaxSizeMb = 800;

        /** 包内条目数上限（同样防 zip 炸弹：几十万个小文件足以打爆 inode） */
        private int maxEntries = 5000;

        /**
         * 外部应用命令模板。支持两个占位符：
         * <pre>
         *   {taskNo}     升级业务号
         *   {stagingDir} staging 目录的绝对路径
         * </pre>
         * 例：{@code sudo -n /opt/ticket/bin/upgrade-apply.sh {taskNo} {stagingDir}}。
         *
         * <p>为空 ⇒ 后端停在 READY_TO_APPLY，不发起外部进程。
         */
        private String applyCommand = "";

        /** 外部命令的启动等待上限（秒）。只等它「起得来」，不等它跑完 */
        private int applyCommandTimeoutSeconds = 60;

        /** 是否允许手动回滚（从 backup 还原 currentArtifactDir） */
        private boolean rollbackEnabled = true;

        /**
         * APPLYING 状态超过该分钟数仍无回执 ⇒ 判为失败。
         *
         * <p>覆盖的是「外部脚本崩了、连结果文件都没写」这一类：任务会永远停在
         * APPLYING 并占住活跃唯一键，让后续所有升级都被挡住。
         */
        private int applyTimeoutMinutes = 30;

        /** 升级记录列表默认返回条数上限 */
        private int historyLimit = 50;
    }

    /**
     * 主备双机热备配置。
     *
     * <h2>为什么 {@code dryRun} 默认 true</h2>
     * <p>本模块的「手动切换主备 / 立即同步」会调用 {@code deploy/ha/scripts/} 下的
     * 真实运维脚本，而这些脚本的作用是<b>操纵虚拟 IP 的归属</b> ——
     * 执行成功意味着线上流量立刻从一台机器切到另一台。
     * 若默认就是「真执行」，那么任何一次误点、任何一个脚本 bug、
     * 以及<b>开发/评审环境里对生产配置的复现</b>，都会造成一次真实的业务中断。
     *
     * <p>因此取向是 fail-safe：默认只<b>组装并返回将要执行的命令</b>，
     * 让维护人员先看清命令、确认无误，再显式把 {@code HA_DRY_RUN=false} 配上。
     * 这与 {@code app.upgrade.enabled} 默认 false 是同一思路 ——
     * <b>会改变系统运行形态的能力，默认都不开启。</b>
     *
     * <h2>为什么 {@code deployDir} 留空不报错</h2>
     * <p>没配目录不等于配置错误：绝大多数单机部署根本没有 {@code deploy/ha}。
     * 此时页面照常可用（能看状态、能看指引、能生成配置片段），
     * 只有「手动切换」「立即同步」这两个<b>真实运维动作</b>会被明确拒绝
     * （见 {@code ErrorCode#HA_DEPLOY_DIR_MISSING}）——
     * 而不是让整个主备配置页报错打不开。
     */
    @Data
    public static class Ha {
        /**
         * 主备部署资产目录（{@code deploy/ha} 在服务器上的绝对路径）。
         *
         * <p>留空或目录不存在时，所有真实运维动作用明确错误回应，绝不假装成功。
         * 例：{@code /opt/ticket/deploy/ha}。
         */
        private String deployDir = "";

        /**
         * 演练模式：true（默认）时只组装并返回将执行的命令，不真正执行脚本。
         *
         * <p>生产上要真正执行切换，必须显式配置 {@code HA_DRY_RUN=false}。
         */
        private boolean dryRun = true;

        /** 脚本执行等待上限（秒）。超时即杀掉并返回失败，不无限等待 */
        private int scriptTimeoutSeconds = 30;

        /**
         * 心跳扫描间隔（毫秒）。
         *
         * <p>默认 10 秒 —— 与需求文档 [174] 行的心跳超时默认值同量级：
         * 扫描周期必须明显小于超时阈值，否则「超时判定」的精度由扫描周期决定，
         * 一个 10 秒的阈值配一个 60 秒的扫描周期，实际断连感知会慢到 70 秒。
         */
        private long heartbeatScanIntervalMs = 10_000L;

        /** 一次上报/输出截断长度（防止脚本异常输出把响应体撑爆） */
        private int maxOutputChars = 8000;
    }
}
