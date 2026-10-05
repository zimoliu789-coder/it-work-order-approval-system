package com.enterprise.ticket.module.applytype.service;

import com.enterprise.ticket.module.applytype.dto.ApplyConfigCreateRequest;

import com.enterprise.ticket.module.applytype.dto.ApplyTypeSaveRequest;
import com.enterprise.ticket.module.applytype.dto.vo.ApplyTypeOptionVO;
import com.enterprise.ticket.module.applytype.dto.vo.ApplyTypeVO;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.module.applytype.dto.vo.FlowPreviewVO;
import com.enterprise.ticket.module.applytype.entity.ApplyType;

import java.util.List;
import java.util.Map;

/**
 * 申请类型服务
 *
 * <h2>两类调用方，两套关注点</h2>
 * <ul>
 *   <li><b>管理端</b>（super_admin）：CRUD + 启停，关心「配置是否正确」；</li>
 *   <li><b>提交端</b>（任何在职员工）：只看「我能提哪些」，关心「有没有权限」。</li>
 * </ul>
 * 因此查询接口分成 {@link #listAll()}（全量，管理）与
 * {@link #listEnabledForCurrentUser()}（启用且当前用户有提交权限，提交页），
 * 避免把「权限过滤」这件事下放给前端 —— 前端过滤等于没过滤。
 */
public interface ApplyTypeService {

    /** 管理列表（全部状态，含停用；含关联模板名与工单使用量） */
    List<ApplyTypeVO> listAll();

    /** 详情（含 schema，供提交页渲染动态表单；也可供管理页查看关联表单内容） */
    ApplyTypeVO getDetail(Long id);

    /** 当前用户可提交的类型（启用 + 通过提交权限过滤），供「提交申请」页卡片区 */
    List<ApplyTypeOptionVO> listEnabledForCurrentUser();

    /** 新建申请类型 */
    Long create(ApplyTypeSaveRequest request);

    /**
     * 一步创建申请类型：表单 + 流程 + 类型，一次提交完成。
     *
     * <p>说明：「不用先建表单版本再关联，一步到位」。
     * 实现上是把原来要管理员手工走的三段（建表单 → 发布 → 建流程 → 发布 → 建类型）
     * 收在一个事务里，任一步失败整体回滚 —— 不会留下「有表单没类型」的半成品。
     *
     * @return 新建的申请类型 id
     */
    Long createFull(ApplyConfigCreateRequest request);

    /** 修改申请类型 */
    void update(Long id, ApplyTypeSaveRequest request);

    /** 启用 / 停用 */
    void updateStatus(Long id, String status);

    /** 删除（已被工单使用时拒绝，只能停用） */
    void delete(Long id);

    /**
     * 审批流程预览：用「当前表单数据」算一遍命中路径，告诉提交页
     * 「会走到哪些节点」「需要申请人自选哪些节点的审批人」。
     *
     * <p>提交页在表单变化时防抖调用。它<b>不是校验</b> —— 结果来自前端传来的表单数据，
     * 完全可伪造；真正的校验发生在 {@code OrderService#submitCustomOrder}。
     *
     * <p>权限口径与提交完全一致（走 {@link #requireSubmittable}）：
     * 能预览 = 能提交，不可提交的用户连预览都拿不到，避免它变成探测配置的旁路。
     */
    FlowPreviewVO flowPreview(Long id, Map<String, Object> formData);

    /**
     * 「申请人自选」候选人的**分页搜索**（ · W4-D）。
     *
     * <p>{@link #flowPreview} 下发的候选池只含首屏若干条（大范围下截断），
     * 本方法提供完整列表的翻页与关键字检索 —— 两者必须同时存在，
     * 只截断不给搜索通路会让排在后面的人真的选不到。
     *
     * <p>权限与 {@link #flowPreview} 同口径（{@code requireSubmittable}）：
     * 能预览 = 能搜。nodeKey 定位不到节点时返回空页（流程刚被改过属正常竞态，不该报错）。
     *
     * @param nodeKey 预览结果里的节点标识（必填）
     * @param keyword 姓名 / 登录名模糊匹配，可为空
     */
    PageResult<FlowPreviewVO.Candidate> chooseCandidates(Long id, String nodeKey, String keyword,
                                                        long page, long size);

    // ------------------------------------------------------------------
    // 跨模块契约
    // ------------------------------------------------------------------

    /**
     * 校验「当前登录用户此刻可以提交该类型」，并返回类型实体。
     *
     * <p>校验三件事：类型存在、处于启用状态、当前用户在提交权限范围内。
     * 供 {@code OrderService#submitCustomOrder} 复用 —— 提交接口必须自己做这层校验，
     * 不能依赖「前端只显示了他能看到的卡片」：直调接口绕开界面是最常见的越权路径。
     *
     * @throws com.enterprise.ticket.common.exception.BusinessException
     *         类型不存在（404）/ 已停用（400）/ 无提交权限（403）
     */
    ApplyType requireSubmittable(Long applyTypeId);
}
