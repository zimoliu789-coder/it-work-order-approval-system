package com.enterprise.ticket.module.ha.dto;

import lombok.Data;

/**
 * 加入集群请求。
 *
 * <p>只有两个字段，是刻意的：说明是「管理员不用复制 .env 文件、不用手动跑脚本」。
 * 多一个字段就多一处「两边填得不一样」的可能，而主备配置一旦两边不一致，
 * 表现是「节点状态一直不对」，排查成本极高。
 *
 * <p>{@code joinToken} 就是主节点上的内部通道令牌（{@code INTERNAL_ALERT_TOKEN}）——
 * 复用它而不是另造一个「加入码」：两台机器本来就必须让这个令牌逐字一致
 * （心跳上报也用它），另造一个只会多一处需要对齐的地方。
 */
@Data
public class HaJoinClusterRequest {

    /** 主节点 IP */
    private String masterIp;

    /** 主节点的内部通道令牌 */
    private String joinToken;
}
