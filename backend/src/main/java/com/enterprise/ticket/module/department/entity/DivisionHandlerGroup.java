package com.enterprise.ticket.module.department.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一级分部 ↔ 处理小组 绑定（V53）
 *
 * <h2>它解决什么问题</h2>
 * <p>V53 之前「最终处理部门」是全局唯一的（{@code department.handler_group = 1} 且
 * {@code OrderServiceImpl} 用 {@code LIMIT 1} 取），于是**北京分部的单子必然落到上海那个组**。
 * 本表把「哪个处理小组负责哪个分部」显式存下来，配合
 * {@code DepartmentService#handlerDepartmentOf} 的逐级向上查找，让分配跟着组织走。
 *
 * <h2>为什么一个分部只有一行（{@code UNIQUE(division_id)}）</h2>
 * <p>一个分部同时归两个 IT 组管，会导致「工单到底派给谁」没有确定答案 ——
 * 与其在运行期随机选一个，不如在配置期就拦住。反过来
 * {@code handler_department_id} <b>不唯一</b>：一个 IT 组服务多个分部是常态。
 *
 * <h2>与 {@code department.handler_default} 的分工</h2>
 * <ul>
 *   <li>{@code handler_group = 1}：这个部门<b>是不是</b>处理小组（可多个）；</li>
 *   <li>本表：这个处理小组<b>负责哪个分部</b>；</li>
 *   <li>{@code handler_default = 1}：全都匹配不上时<b>兜底用哪个</b>（最多 1 个）。</li>
 * </ul>
 */
@Data
@TableName("division_handler_group")
public class DivisionHandlerGroup {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 一级分部 id（{@code department.depth = 1}） */
    private Long divisionId;

    /** 处理小组 id（该部门必须 {@code department.handler_group = 1}） */
    private Long handlerDepartmentId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
