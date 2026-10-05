package com.enterprise.ticket.module.ad.service;

import com.enterprise.ticket.module.ad.dto.AdSyncResultVO;

/**
 * AD 用户同步服务（；）
 *
 * <p>手动（配置页「立即同步」）与定时（每天定点）两条入口共用同一实现 ——
 * 两条路径若各写一份，「手动同步正常、自动同步建出脏数据」这类分歧会长期潜伏。
 */
public interface AdUserSyncService {

    /**
     * 执行一次增量同步
     *
     * <p>增量语义严格按 定义：
     * <ul>
     *   <li>AD 有、本地无 → 新建（{@code auth_type=LDAP} + 默认角色 + 随机不可用口令）；</li>
     *   <li>AD 有、本地有 → 只在属性确实变化时更新（姓名 / 邮箱 / 部门 / 禁用状态）；</li>
     *   <li>AD 无、本地有（且本地是 AD 账号）→ 本地<b>禁用</b>，不物理删除（历史工单必须保留）。</li>
     * </ul>
     *
     * @return 分类计数结果
     * @throws com.enterprise.ticket.common.exception.BusinessException
     *         {@code AD_DISABLED}（未启用）、{@code AD_CONFIG_INCOMPLETE}（缺必填项）、
     *         {@code AD_SYNC_FAILED}（目录完全不可达）
     */
    AdSyncResultVO sync();
}
