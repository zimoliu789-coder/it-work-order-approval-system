package com.enterprise.ticket.module.ad.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * AD 用户同步结果（「同步结果统计」）
 *
 * <p>增量同步的四个去向必须<b>分开计数</b>：
 * <ul>
 *   <li>{@code created} —— AD 有、本地无 → 新建（分配默认角色）；</li>
 *   <li>{@code updated} —— 两边都有且属性有变化 → 更新姓名 / 邮箱 / 部门 / 状态；</li>
 *   <li>{@code disabled} —— AD 已删除或已禁用 → 本地标记禁用（<b>不物理删除</b>，
 *       因为该员工名下的历史工单必须保留，删了用户等于把工单变成无主数据）；</li>
 *   <li>{@code unchanged} —— 无变化（把「什么都没做」显式说出来，
 *       否则运维会怀疑同步是不是根本没跑）。</li>
 * </ul>
 * {@code failures} 只保留前若干条明细：一次同步失败几百条时，
 * 把全部堆栈式信息塞进响应体对排查没有帮助，真正的定位手段是服务端日志。
 */
@Data
public class AdSyncResultVO {

    /** 本次从 AD 拉回并成功解析的账号总数 */
    private int total;

    private int created;

    private int updated;

    /** 因 AD 侧已删除 / 已禁用而在本地被禁用的数量 */
    private int disabled;

    private int unchanged;

    /** 处理失败（单条异常）的数量 */
    private int failed;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startedAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime finishedAt;

    /** 一句话结论 */
    private String message;

    /** 失败明细（截断保留） */
    private List<String> failures = new ArrayList<>();

    /** 是否触发同步（总开关关闭时 false） */
    private boolean executed = true;
}
