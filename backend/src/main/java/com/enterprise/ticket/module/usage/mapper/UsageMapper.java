package com.enterprise.ticket.module.usage.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.enterprise.ticket.module.usage.dto.vo.UsageRecordVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * 使用记录查询（需求方三波·第一波·）
 *
 * <h2>为什么用 JOIN 一次查出，而不是「查工单再逐个补设备/人员信息」</h2>
 * <p>使用记录是一张<b>跨实体宽表</b>：一行要同时呈现设备、借用人与时间线。
 * 若先分页查工单再逐行补信息，就是典型的 N+1 —— 一页 20 行会产生 60+ 次查询。
 * 这些信息全部在同一个数据库里、且都是主键 JOIN，下沉到 SQL 一次拿完最省。
 *
 * <h2>条件为什么在 Java 里先「翻译」成主键，而不是在 SQL 里判断 scope 字符串</h2>
 * <p>把 {@code scope=DEVICE/USER + targetId} 在服务层换算为 {@code deviceId} / {@code applicantId}
 * 两个可空主键，SQL 只需要「非空即过滤」这一种判断。这样做的收益：
 * <ul>
 *   <li>避开 MyBatis OGNL 里单引号字符串比较的经典坑（{@code 'DEVICE'} 会被当作 char）；</li>
 *   <li>SQL 里没有业务字符串字面量，改枚举名不会静默漏掉某个分支。</li>
 * </ul>
 *
 * <h2>为什么不过滤 {@code device.deleted}</h2>
 * <p>本查询是<b>历史视图</b>：设备即便后来被软删除，它历史上的借用记录也必须能查得到
 * （：历史设备不允许物理删除、历史工单可查）。
 * 过滤掉会让「这台设备到底借给过谁」永久失去答案。
 */
@Mapper
public interface UsageMapper {

    @Select("""
            <script>
            SELECT o.id                AS orderId,
                   o.order_no          AS orderNo,
                   o.order_type        AS orderType,
                   o.device_id         AS deviceId,
                   d.device_name       AS deviceName,
                   d.asset_no          AS assetNo,
                   pc.category_name    AS primaryCategoryName,
                   d.brand             AS brand,
                   d.model             AS model,
                   o.applicant_id      AS applicantId,
                   u.display_name      AS applicantName,
                   g.dept_name         AS departmentName,
                   hu.display_name     AS handlerName,
                   o.use_type          AS useType,
                   o.status            AS status,
                   o.created_at        AS createdAt,
                   o.delivered_at      AS deliveredAt,
                   o.planned_end_time  AS plannedEndTime,
                   o.actual_end_time   AS actualEndTime,
                   o.auto_extend_count AS autoExtendCount,
                   o.borrow_timeout    AS borrowTimeout,
                   o.return_trigger    AS returnTrigger,
                   o.return_condition  AS returnCondition
              FROM borrow_order o
              JOIN device d          ON d.id = o.device_id
              JOIN employee  u          ON u.id = o.applicant_id
              LEFT JOIN device_category pc ON pc.id = d.primary_category_id
              LEFT JOIN department   g  ON g.id  = o.department_id
              LEFT JOIN employee          hu ON hu.id = o.actual_final_handler_id
             WHERE 1 = 1
             <if test="deviceId != null">   AND o.device_id = #{deviceId}   </if>
             <if test="applicantId != null">AND o.applicant_id = #{applicantId}</if>
             <if test="departmentId != null"> AND o.department_id = #{departmentId}</if>
             <if test="status != null">     AND o.status = #{status}        </if>
             <if test="useType != null">    AND o.use_type = #{useType}      </if>
             <if test="from != null">       AND o.created_at &gt;= #{from}   </if>
             <if test="to != null">         AND o.created_at &lt; #{to}      </if>
             <if test="keyword != null">
                 AND (o.order_no LIKE CONCAT('%', #{keyword}, '%')
                      OR d.device_name LIKE CONCAT('%', #{keyword}, '%')
                      OR d.asset_no LIKE CONCAT('%', #{keyword}, '%')
                      OR u.display_name LIKE CONCAT('%', #{keyword}, '%')
                      OR u.username LIKE CONCAT('%', #{keyword}, '%'))
             </if>
             ORDER BY o.created_at DESC, o.id DESC
            </script>
            """)
    IPage<UsageRecordVO> pageUsage(IPage<UsageRecordVO> page,
                                  @Param("deviceId") Long deviceId,
                                  @Param("applicantId") Long applicantId,
                                  @Param("departmentId") Long departmentId,
                                  @Param("keyword") String keyword,
                                  @Param("status") String status,
                                  @Param("useType") String useType,
                                  @Param("from") LocalDateTime from,
                                  @Param("to") LocalDateTime to);
}
