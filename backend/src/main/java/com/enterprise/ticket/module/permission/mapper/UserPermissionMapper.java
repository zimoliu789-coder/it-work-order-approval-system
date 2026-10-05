package com.enterprise.ticket.module.permission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.ticket.module.permission.entity.UserPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户级授权。
 */
@Mapper
public interface UserPermissionMapper extends BaseMapper<UserPermission> {

    /**
     * 某用户当前**有效**的附加权限码。
     *
     * <p>{@code revoked_at IS NULL} 是唯一判据 —— 撤销过的行必须留痕但不生效，
     * 因此不能靠「删行」来表达撤销（那会丢掉审计证据）。
     */
    @Select("""
            SELECT perm_code
              FROM user_permission
             WHERE user_id = #{userId}
               AND revoked_at IS NULL
            """)
    List<String> selectEffectiveCodes(@Param("userId") Long userId);

    /**
     * 批量取多个用户的有效权限码（列表页/导出用，避免 N+1）。
     *
     * <p>返回 {@code (user_id, perm_code)} 两列，调用方自己分组 ——
     * 用 MyBatis 的嵌套结果映射会把 SQL 与 VO 结构耦合起来，
     * 而这里只需要一次 IN 查询 + 一次内存分组。
     */
    @Select("""
            <script>
            SELECT user_id AS userId, perm_code AS permCode
              FROM user_permission
             WHERE revoked_at IS NULL
               AND user_id IN
               <foreach collection="userIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<java.util.Map<String, Object>> selectEffectiveCodesOf(@Param("userIds") List<Long> userIds);
}
