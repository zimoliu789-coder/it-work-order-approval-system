package com.enterprise.ticket.module.usage.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.common.constant.OrderStatus;
import com.enterprise.ticket.common.constant.OrderType;
import com.enterprise.ticket.common.constant.UseType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.module.role.service.RoleService;
import com.enterprise.ticket.module.usage.dto.UsageQuery;
import com.enterprise.ticket.module.usage.dto.vo.UsageRecordVO;
import com.enterprise.ticket.module.usage.mapper.UsageMapper;
import com.enterprise.ticket.module.usage.service.UsageService;
import com.enterprise.ticket.security.LoginUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 使用记录服务实现（需求方三波·第一波·）
 *
 * <h2>权限与数据范围</h2>
 * <p>接口层用 {@code usage:view} 控制「能不能进这个模块」；进入之后，看到哪些数据
 * 还要按角色的 {@code data_scope} 收窄：
 * <ul>
 *   <li>{@code ALL} —— 不限（超管 / 管理员默认如此，与改造前行为一致）；</li>
 *   <li>{@code GROUP} —— 只返回<b>本部门</b>名下的借用记录（按工单上固化的分组快照过滤，
 *       而不是员工当前分组，否则调岗会把历史记录一起搬走）；</li>
 *   <li>{@code SELF} —— 只返回与自己相关的记录。</li>
 * </ul>
 * 收窄在<b>服务层</b>做而不是靠前端传参：前端传来的 {@code targetId} 只是「想看谁的」，
 * 最终能看多少由服务端按登录态决定 —— 否则把 URL 里的 id 一改就能越权翻全公司的记录。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsageServiceImpl implements UsageService {

    /** 单页上限，防止一次拉全表 */
    private static final long MAX_PAGE_SIZE = 100L;

    private static final String SCOPE_DEVICE = "DEVICE";
    private static final String SCOPE_USER = "USER";

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_TIME_T = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final UsageMapper usageMapper;
    private final RoleService roleService;

    @Override
    public PageResult<UsageRecordVO> page(UsageQuery query) {
        long current = Math.max(query.getPage(), 1L);
        long size = Math.min(Math.max(query.getSize(), 1L), MAX_PAGE_SIZE);

        Long deviceId = null;
        Long applicantId = null;
        String scope = trimToNull(query.getScope());
        if (scope != null) {
            if (!SCOPE_DEVICE.equals(scope) && !SCOPE_USER.equals(scope)) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "视角只能是 DEVICE 或 USER");
            }
            if (query.getTargetId() == null) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "按设备 / 员工查看时必须指定目标 ID");
            }
            if (SCOPE_DEVICE.equals(scope)) {
                deviceId = query.getTargetId();
            } else {
                applicantId = query.getTargetId();
            }
        }

        // 数据权限收窄（见类注释）：在「用户已经指定的视角」之上再叠加，两者取交集
        Long departmentId = null;
        LoginUser currentUser = SecurityUtils.getCurrentUser();
        if (currentUser != null && !currentUser.isSuperAdmin()) {
            String dataScope = roleService.dataScopeOf(currentUser.getRole());
            if (RoleService.SCOPE_SELF.equals(dataScope)) {
                // 只本人：直接覆盖视角，忽略前端传入的 targetId
                applicantId = currentUser.getId();
                deviceId = null;
            } else if (RoleService.SCOPE_GROUP.equals(dataScope)) {
                departmentId = currentUser.getDepartmentId();
            }
        }

        String status = trimToNull(query.getStatus());
        if (status != null && !OrderStatus.isValid(status)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "工单状态不合法：" + status);
        }
        String useType = trimToNull(query.getUseType());
        if (useType != null && UseType.of(useType) == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "借用类型不合法：" + useType);
        }

        LocalDateTime from = parseStart(query.getStartTime(), "开始时间");
        LocalDateTime to = parseEndExclusive(query.getEndTime(), "结束时间");
        if (from != null && to != null && !from.isBefore(to)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "开始时间必须早于结束时间");
        }

        IPage<UsageRecordVO> page = new Page<>(current, size);
        IPage<UsageRecordVO> result = usageMapper.pageUsage(page, deviceId, applicantId, departmentId,
                escapeLike(trimToNull(query.getKeyword())), status, useType, from, to);
        List<UsageRecordVO> records = result.getRecords();
        records.forEach(this::enrich);
        return PageResult.of(result);
    }

    /** 补齐中文标签：把「编码」翻译成界面可直接展示的文案，避免前端各维护一份映射 */
    private void enrich(UsageRecordVO vo) {
        vo.setStatusLabel(OrderStatus.labelOf(vo.getStatus()));
        vo.setOrderTypeLabel(OrderType.labelOf(vo.getOrderType()));
        vo.setUseTypeLabel(UseType.labelOf(vo.getUseType()));
    }

    /**
     * 关键词转义：{@code %} 与 {@code _} 在 LIKE 里是通配符。
     *
     * <p>用户搜「A_001」时若原样传入，下划线会匹配任意单字符，把「A1001」「A-001」也捞出来。
     * MySQL 默认转义符是反斜杠，因此加前缀即可；同时把反斜杠自身也转义，避免出现悬空转义符。
     */
    private String escapeLike(String keyword) {
        if (keyword == null) {
            return null;
        }
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** 起始时间：支持 {@code yyyy-MM-dd} 与 {@code yyyy-MM-dd HH:mm:ss} */
    private LocalDateTime parseStart(String raw, String fieldName) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            if (value.length() == 10) {
                return LocalDate.parse(value, DATE_ONLY).atStartOfDay();
            }
            return parseDateTime(value);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, fieldName + "格式不正确，应为 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss");
        }
    }

    /**
     * 结束时间：<b>左闭右开</b>。
     *
     * <p>传日期（yyyy-MM-dd）时自动加一天：用户选「结束 9 月 19 日」的意图是
     * 「把 19 日整天的数据都包含进来」，若直接用 19 日 00:00:00 比较会漏掉当天全部记录。
     * 这也是 报表统一采用 {@code [from, to)} 的原因。
     */
    private LocalDateTime parseEndExclusive(String raw, String fieldName) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            if (value.length() == 10) {
                return LocalDate.parse(value, DATE_ONLY).plusDays(1).atStartOfDay();
            }
            return parseDateTime(value);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, fieldName + "格式不正确，应为 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss");
        }
    }

    private LocalDateTime parseDateTime(String value) {
        if (value.contains("T")) {
            return LocalDateTime.parse(value, DATE_TIME_T);
        }
        return LocalDateTime.parse(value, DATE_TIME);
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
