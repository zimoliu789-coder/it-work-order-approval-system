package com.enterprise.ticket.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.user.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 员工 Mapper
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 手写 SQL 时必须显式列出的列清单。
     *
     * <p>注意：此处必须显式列出列名并给 {@code is_dimission} 起别名 {@code dimission}。
     * 实体属性名为 {@code dimission}（配 {@code @TableField("is_dimission")}），而 MyBatis 的
     * 自动映射不识别 {@code @TableField}：手写 {@code SELECT *} 时列 {@code is_dimission}
     * 会推导出属性 {@code isDimission}，与实体注册的属性 {@code dimission} 不匹配而被静默丢弃，
     * 导致登录时的离职校验永远失效。MyBatis-Plus 生成的 SQL（getById 等）自带别名，不受影响。
     *
     * <p>抽成常量而不是在每个查询里复制一遍：新增列时只改一处 ——
     * 漏改某一处的后果是「这个查询悄悄少了一个字段」，属最难发现的一类缺陷
     * （V26 新增 {@code phone} 时正是靠这一点避免了漏改）。
     * 注解属性的值必须是编译期常量，而 {@code static final String} 的拼接
     * 恰好是编译期常量表达式，因此下面的 {@code @Select} 可以直接拼它。
     */
    String COLUMNS = "id, username, real_name, display_name, email, phone, department, password_hash, "
            + "role, auth_type, ldap_dn, ad_object_guid, ad_synced_at, department_id, leader_id, "
            + "force_change_password, enabled, is_dimission AS dimission, dimission_at, last_login_at, "
            + "token_version, created_at, updated_at";

    /**
     * 按登录名查询。
     *
     * <p>登录名<b>全局唯一</b>（库侧唯一索引），因此这里必然是 0 或 1 行。
     */
    @Select("SELECT " + COLUMNS + " FROM users WHERE username = #{username} LIMIT 1")
    User selectByUsername(@Param("username") String username);

    /**
     * 按手机号查询（V26；找回密码与绑定唯一性校验用）。
     *
     * <p>只回答「这个号码归谁」；「排除自己」由服务层另行处理 ——
     * 手机号唯一性由 {@code uk_users_phone} 兜底，查询侧不必再叠加条件。
     */
    @Select("SELECT " + COLUMNS + " FROM users WHERE phone = #{phone} LIMIT 1")
    User selectByPhone(@Param("phone") String phone);

    /**
     * 按邮箱查询（V26；同上）。邮箱列早在  就存在，V26 才补上唯一索引。
     */
    @Select("SELECT " + COLUMNS + " FROM users WHERE email = #{email} LIMIT 1")
    User selectByEmail(@Param("email") String email);

    /**
     * 按姓名查询（可能 0 行、1 行或多行）。
     *
     * <p><b>刻意返回列表而不是 {@code LIMIT 1} 的单条</b>：明确允许重名
     * （「可以重复，公司可能有多个张伟」）。用 {@code LIMIT 1} 会把「重名」
     * 伪装成「查到了」，用户输入「张伟」时系统会随机挑一个账号走后续流程 ——
     * 而重名场景下正确的行为是**明确报错让用户改用数字登录名**。
     * 因此这里把「有多条」这个事实完整交回服务层判定。
     */
    @Select("SELECT " + COLUMNS + " FROM users WHERE real_name = #{realName} ORDER BY id")
    List<User> selectByRealName(@Param("realName") String realName);

    /**
     * 当前已使用的最大「纯数字登录名」（ ：自动编号的起点）。
     *
     * <p>{@code CAST … AS UNSIGNED} 前<b>必须先经 {@code REGEXP} 过滤</b>：库里存在两个
     * 非纯数字登录名（内置超管 {@code administrator}、AD 域账号名），直接 CAST 会命中
     * MySQL {@code ERROR 1292 Truncated incorrect DOUBLE value}，让整条查询报错。
     *
     * <p>刻意<b>不加逻辑删除条件</b>：登录名与设备编号同口径，<b>不可复用</b> ——
     * 已删除账号占用的编号若被重新分配，唯一索引会直接抛冲突。
     *
     * <p>{@code COALESCE} 兜住空库：此时 MAX 为 NULL，返回 10000 作为「下一个是 10001」的基准。
     */
    @Select("SELECT COALESCE(MAX(CAST(username AS UNSIGNED)), 10000) FROM users "
            + "WHERE username REGEXP '^[0-9]+$'")
    long selectMaxNumericUsername();

    /**
     * 「AD 账号被删除 / 禁用」的强制下线：只改 enabled 与 token_version，不动其它字段。
     *
     * <p>为什么刷新 enabled 还不够：JWT 是无状态的，已签发的 Token 在有效期内仍然有效。
     * 域控把账号一禁，本地若不把 {@code token_version} +1，那个人的在途会话
     * 还能继续用满 12 小时 —— 「离职即失效」这条安全承诺就落空了。
     *
     * @return 受影响行数；0 表示账号不存在或已处于禁用状态
     */
    @Update("UPDATE users SET enabled = 0, token_version = token_version + 1, updated_at = NOW() "
            + "WHERE id = #{userId} AND enabled = 1")
    int disableForAdRemoval(@Param("userId") Long userId);

    @Select("SELECT COUNT(1) FROM users WHERE username LIKE CONCAT(#{prefix}, '%')")
    int countByUsernamePrefix(@Param("prefix") String prefix);

    /**
     * 查询全部「管理端接收人」：启用且在职的 super_admin / admin。
     *
     * <p>用途：设备工单对账告警、系统级异常通知等需要「通知所有管理员」的场景。
     * 只取 id：调用方只需要接收人列表，没必要把密码哈希等字段读出来。
     *
     * <p>刻意不查角色表（sys_role）判断「谁算管理员」—— 这是<b>结构性</b>问题
     * （谁是内置管理员），不是权限问题。若将来把某个自定义角色也纳入告警接收人，
     * 应该显式改这里并注明，而不是让它随权限配置漂移。
     */
    @Select("SELECT id FROM users WHERE role IN ('super_admin', 'admin') "
            + "AND enabled = 1 AND is_dimission = 0 ORDER BY id")
    List<Long> selectAdminIds();

    /**
     * 查询全部「超级管理员接收人」：启用且在职的 super_admin。
     *
     * <p>与 {@link #selectAdminIds()} 的区别只在于是否包含 admin。运维/基础设施级告警
     * （如备份失败）只发给超管：这类问题需要的基础设施权限（改 .env、进 NAS、看容器日志）
     * 普通业务管理员并不具备，群发只会产生「收到但处理不了」的消息噪音。
     */
    @Select("SELECT id FROM users WHERE role = 'super_admin' AND enabled = 1 AND is_dimission = 0 ORDER BY id")
    List<Long> selectSuperAdminIds();
}
