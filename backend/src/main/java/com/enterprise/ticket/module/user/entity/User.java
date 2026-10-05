package com.enterprise.ticket.module.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 员工表
 *
 * <p>注意：所有业务表外键必须使用 user_id，不得使用员工姓名。
 *
 * <p>字段命名说明：数据库列 {@code is_dimission} 对应的 Java 字段命名为 {@code dimission}，
 * 并显式声明 {@code @TableField}，避免 Lombok 对 {@code isXxx} 前缀字段生成
 * {@code getIsXxx()} 造成 MyBatis-Plus 属性名解析歧义。
 */
@Data
@TableName("users")
public class User {

    /** 主键 user_id，不可变 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录账号（员工姓名），全局唯一；重名自动加 _2 后缀 */
    private String username;

    /**
     * 姓名（员工真实姓名）——「姓名唯一」这条业务规则的校验锚点（2026-09-18 小迭代新增）。
     *
     * <p>为什么不复用 {@code display_name}： 明确允许 {@code display_name} 重复
     * （「张伟」和「张伟_2」的显示名可以相同），因此它无法承担唯一性校验；
     * 而「姓名唯一」是需求方对新增 / 导入入口的硬要求，需要独立的、语义明确的落点。
     *
     * <p>唯一性由服务层「预检 + 捕获」保证（见 {@code UserServiceImpl}），
     * 库侧只建普通索引 —— 若建唯一索引，与 允许重名、以及历史数据中
     * 已经存在的同名 {@code display_name} 直接冲突，会让迁移失败。
     */
    private String realName;

    /** 显示名称 */
    private String displayName;

    /**
     * 邮箱（ 新增）。
     *
     * <p>本地可由管理员填写，AD 账号则由同步任务按属性映射（默认 {@code mail}）写入。
     * 单开一列而不是塞进 display_name：邮箱在 AD 里是<b>权威且会被同步覆盖</b>的字段，
     * 混进展示名会导致「同步一次显示名被改成邮箱」。
     */
    private String email;

    /**
     * 手机号（V26 新增）。
     *
     * <p>与 {@link #email} 一样<b>全局唯一</b>（库侧唯一索引 {@code uk_users_phone}），
     * 用于找回密码的短信渠道与「首次登录强制绑定联系方式」。
     *
     * <p>为空（NULL）表示未绑定 —— 唯一索引对 NULL 不判等，因此「大多数账号还没绑」
     * 不会互相冲突；而一旦绑定了某个号码，第二个账号再绑同一号码就会撞唯一键。
     * 这条约束刻意落在数据库（而不是只靠服务层预检）：预检在并发下会漏，
     * 唯一索引是最后一道、且无法被绕过的事实源。
     */
    private String phone;

    /** 部门（ 新增）。来源同上，AD 默认映射 {@code department} */
    private String department;

    /** Argon2id 密码哈希；auth_type=LDAP 时为空 */
    private String passwordHash;

    /** 角色：super_admin / admin / user */
    private String role;

    /** 认证来源：LOCAL / LDAP */
    private String authType;

    /** 域用户唯一标识 DN */
    private String ldapDn;

    /**
     * AD 对象唯一标识 objectGUID（十六进制字符串， 新增）
     *
     * <p>用途是<b>可追溯</b>：当 AD 里发生账号改名（改名后 sAMAccountName 变了，
     * 但 objectGUID 不变）时，运维可以据此确认「本地新建的那个账号
     * 与之前被禁用的账号是同一个人的前后身份」，而不是把它当成两个陌生人。
     * 刻意不用它做自动改名匹配 —— 那会在「AD 管理员把两个人配成同一个 GUID」
     * 这类异常数据下静默改写本地账号。
     */
    private String adObjectGuid;

    /** 最后一次从 AD 同步的时间；LOCAL 账号恒为 NULL（ 新增） */
    private LocalDateTime adSyncedAt;

    /** 所属部门ID； 允许为空 */
    private Long departmentId;

    /**
     * 直属领导 user_id（；可为空 = 未配置）。
     *
     * <p>供流程的「申请人直属领导」审批规则使用。领导离职/停用时**不清空本字段**
     * （保留历史），提交工单时解析到失效领导即走超管兜底。
     */
    private Long leaderId;

    /**
     * 直属主管是否被**手工覆盖**（，V31 新增）。
     *
     * <p>{@code false}（默认）= 跟随所在部门的部门主管：部门主管变更时，
     * 该员工的 {@link #leaderId} 会被自动同步为新主管。
     * <p>{@code true} = 管理员在员工详情里手工指定过，「部门主管变更」**绝不改写**它 ——
     * 这正是「副组长管具体人」这类真实场景的落点，覆盖掉等于把管理员的手工配置吃掉。
     */
    @TableField("leader_override")
    private Boolean leaderOverride;

    /** 是否强制修改密码 */
    private Boolean forceChangePassword;

    /** 账号是否启用 */
    private Boolean enabled;

    /** 是否离职（数据库列 is_dimission） */
    @TableField("is_dimission")
    private Boolean dimission;

    /** 离职时间 */
    private LocalDateTime dimissionAt;

    /** 最后登录时间 */
    private LocalDateTime lastLoginAt;

    /**
     * 令牌版本号（需求方三波·第一波·）
     *
     * <p>JWT 无状态，服务端不保存会话。若没有版本号，「修改密码」后旧 Token 在
     * 有效期内（默认 720 分钟）仍可继续使用 —— 密码泄露场景下改密等于白改。
     *
     * <p>本字段写入 Token 的 {@code ver} 声明，每次请求与库中值比对：
     * 改密 / 管理员重置 / 强制下线时 +1，该用户所有已签发 Token 立即失效。
     */
    private Integer tokenVersion;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
