package com.enterprise.ticket.module.security.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * IP 封禁记录。
 *
 * <p>{@code expireAt} 为 null 表示**永久封禁**（只由人工解除）。自动封禁一律带过期时间 ——
 * 「自动且永久」是最危险的组合：一次误判（例如整栋办公楼共用一个出口 IP）
 * 会把一整片人永久挡在门外，而没人会想到去查一张自己不知道存在的表。
 *
 * <p>「是否仍生效」由 {@link #isEffective(LocalDateTime)} **懒判定**，不依赖定时任务：
 * 定时任务一旦停摆，封禁就变成永久的 —— 与上面那条风险是同一个。
 * 定时任务只负责把过期行标为失效（清理视图），不是解封的必要条件。
 */
@Data
@TableName("ip_block")
public class IpBlock {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String ip;

    private String reason;

    /** AUTO 自动 / MANUAL 人工 */
    private String source;

    /** 触发时的连续失败次数 */
    private Integer failCount;

    private LocalDateTime blockedAt;

    /** 解封时间；null = 永久（仅人工可解） */
    private LocalDateTime expireAt;

    private LocalDateTime unblockedAt;

    private Long unblockedBy;

    /** 是否仍生效（过期 / 人工解除后置 false） */
    private Boolean active;

    private LocalDateTime createdAt;

    /**
     * 在给定时刻是否仍然生效。
     *
     * <p>这就是「自动解封」的全部实现：不靠定时任务把状态改过来，
     * 而是在每次判定时比一下时间。好处是**解封永远不会因为任务停摆而失效** ——
     * 后者会造成「说好 30 分钟，结果永久封了」这种最难解释的故障。
     */
    public boolean isEffective(LocalDateTime now) {
        if (!Boolean.TRUE.equals(active)) {
            return false;
        }
        return expireAt == null || expireAt.isAfter(now);
    }
}
