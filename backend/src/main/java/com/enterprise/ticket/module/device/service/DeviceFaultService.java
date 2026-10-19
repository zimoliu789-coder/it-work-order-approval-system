package com.enterprise.ticket.module.device.service;

import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.module.device.dto.DeviceFaultHandleRequest;
import com.enterprise.ticket.module.device.dto.DeviceFaultQuery;
import com.enterprise.ticket.module.device.dto.DeviceFaultRequest;
import com.enterprise.ticket.module.device.dto.vo.DeviceFaultVO;
import com.enterprise.ticket.module.device.dto.vo.FaultDeviceLookupVO;
import com.enterprise.ticket.module.device.dto.vo.FaultDeviceOptionVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 设备故障服务
 *
 * <p>三条上报入口共用同一张 {@code device_fault} 表，状态流转遵循 / ：
 * <pre>
 *   （无工单登记）设备 AVAILABLE ──上报──▶ MAINTENANCE ──维修完成──▶ AVAILABLE
 *                                        └────────报废（仅 AVAILABLE / MAINTENANCE）──▶ SCRAPPED
 *   （工单内上报）设备保持 IN_USE，故障记录待维修；归还登记「故障」时设备 → MAINTENANCE
 * </pre>
 */
public interface DeviceFaultService {

    /**
     * 上报设备故障
     *
     * <p>带 {@code orderId} = 借用人在使用中上报（设备保持使用中，不改变设备状态）；
     * 不带 {@code orderId} = 无工单登记（设备 可用 → 维修中）。
     *
     * <p><b>V57 / 第 10 项</b>：无工单登记已对普通员工开放（「公共设备」模式），
     * 前提是设备处于「可用」—— 使用中的设备必须走工单路径，否则设备状态会绕过
     * 借用单被改成维修中，那台设备的工单再也收不回来。
     *
     * @return 故障记录 id
     */
    Long report(DeviceFaultRequest request);

    /** 故障记录分页（仅 super_admin / admin） */
    PageResult<DeviceFaultVO> page(DeviceFaultQuery query);

    /** 某设备的故障历史（设备台账展开查看；仅 super_admin / admin） */
    List<DeviceFaultVO> listByDevice(Long deviceId);

    /**
     * 故障上报「可选设备」列表（ / 需求方管理端增强）。
     *
     * <p>按角色取数，供故障报修表单的设备选择器使用：
     * <ul>
     *   <li>普通用户 —— 仅返回本人「使用中」工单对应的设备，每项带 {@code orderId}；</li>
     *   <li>管理员 / 超管 —— 返回「可用」设备（台账直接登记）+ 全部「使用中」工单设备（可代报）。</li>
     * </ul>
     */
    List<FaultDeviceOptionVO> selectableDevices();

    /**
     * 报修「我的设备」（V57 / 第 10 项模式 1）：**我负责的 ∪ 我正在用的**。
     *
     * <p><b>两类的并集，缺一不可</b>：
     * <ul>
     *   <li>{@code owner_id = 我} —— 我是这台资产的负责人（长期固定），
     *       「我桌上的显示器坏了」靠它；</li>
     *   <li>我有一笔「使用中」借用单 —— 「我今天借的笔记本坏了」靠它。
     *       这类设备的负责人通常是 IT，不是我；<b>只查负责人会让普通员工看到一个空列表</b>，
     *       报修入口等于不存在。</li>
     * </ul>
     *
     * <p>负责人为空、也非我在用的设备**不出现在这里**，只能走「公共设备」入口（第 10 项要求）。
     * 每项都带 {@code reportable} / {@code reason}，让「我名下但正被别人用着 / 已报废」的设备
     * 也列出来、但明确不可报 —— 直接不列会让人以为设备丢了。
     */
    List<FaultDeviceLookupVO> ownedDevices();

    /**
     * 报修「公共设备」（V57 / 第 10 项模式 2）：按资产编号查询，**不校验负责人**。
     *
     * @param assetNo 资产编号（扫码枪扫出来的就是它）
     * @return 查到时 {@code found=true} 并带上可报修判定；查不到时 {@code found=false}
     *         （**不抛错**：查错编号是这个入口的日常动作，不该每次弹一个红色错误）
     */
    FaultDeviceLookupVO lookupByAssetNo(String assetNo);

    /** 维修完成：故障记录 → 已维修，设备 → 可用（ / ） */
    void markRepaired(Long faultId, DeviceFaultHandleRequest request);

    /**
     * 接单（V50）：待处理 → 处理中，并记下接单人。
     *
     * <p>权限口径：管理员，或落在「处理流程」配置的通知范围内的人 ——
     * 不要求 {@code device:fault:manage}（那个码只发给管理员，而接单的是一线维修人员）。
     */
    void accept(Long faultId);

    /**
     * 判定无法修复（V50）：这是维修结论，不是资产决策 ——
     * 设备保持「维修中」，由资产管理员另行决定报废或调拨。
     */
    void markUnrepairable(Long faultId, DeviceFaultHandleRequest request);

    /** 故障标记报废：故障记录 → 已报废，设备 → 已报废（ / ；使用中禁止报废） */
    void scrap(Long faultId, DeviceFaultHandleRequest request);

    /**
     * 归还登记「故障」时自动建档（「归还时登记故障：设备 → MAINTENANCE，关联故障记录」）。
     *
     * <p>由归还流程调用。若同一工单/设备下已存在「待维修」记录（借用人此前已上报过），
     * 则不重复建档，避免同一次故障出现两条记录。
     *
     * @return 新建的故障记录 id；未建档（已存在待维修记录）时返回 {@code null}
     */
    Long recordReturnFault(Long deviceId, Long orderId, Long reporterId, String description, LocalDateTime occurredAt);
}
