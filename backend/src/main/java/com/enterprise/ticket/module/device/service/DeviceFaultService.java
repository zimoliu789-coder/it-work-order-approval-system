package com.enterprise.ticket.module.device.service;

import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.module.device.dto.DeviceFaultHandleRequest;
import com.enterprise.ticket.module.device.dto.DeviceFaultQuery;
import com.enterprise.ticket.module.device.dto.DeviceFaultRequest;
import com.enterprise.ticket.module.device.dto.vo.DeviceFaultVO;
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
     * 不带 {@code orderId} = 管理员 / 最终处理人在台账直接登记（设备 可用 → 维修中）。
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

    /** 维修完成：故障记录 → 已维修，设备 → 可用（ / ） */
    void markRepaired(Long faultId, DeviceFaultHandleRequest request);

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
