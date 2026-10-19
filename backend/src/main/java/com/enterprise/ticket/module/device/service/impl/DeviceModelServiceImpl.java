package com.enterprise.ticket.module.device.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.api.PageResult;
import com.enterprise.ticket.common.constant.DeviceModelStatus;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.device.dto.DeviceModelSaveRequest;
import com.enterprise.ticket.module.device.dto.DeviceModelStatusRequest;
import com.enterprise.ticket.module.device.dto.vo.DeviceModelOptionVO;
import com.enterprise.ticket.module.device.dto.vo.DeviceModelSyncResultVO;
import com.enterprise.ticket.module.device.dto.vo.DeviceModelVO;
import com.enterprise.ticket.module.device.entity.Device;
import com.enterprise.ticket.module.device.entity.DeviceModel;
import com.enterprise.ticket.module.device.mapper.DeviceMapper;
import com.enterprise.ticket.module.device.mapper.DeviceModelMapper;
import com.enterprise.ticket.module.device.service.DeviceModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备型号字典实现（R3 / 批次 3）。
 *
 * <h2>三条设计取舍</h2>
 * <ol>
 *   <li><b>查重与唯一索引必须同口径</b>：表是 {@code utf8mb4_general_ci} ⇒ 唯一索引大小写不敏感，
 *       服务层查重直接用 {@code eq(modelName, name)}（同样大小写不敏感）。
 *       如果服务层用 {@code LOWER()} 归一、库里却不归一，就会出现「预检说没重复、插入撞唯一索引」
 *       的 500。这里统一按 ci 语义，并额外捕获 {@link DuplicateKeyException} 兜底并发。</li>
 *   <li><b>编辑不清空字段</b>：{@code update-strategy = not_null} 下 {@code updateById} 会跳过 null，
 *       于是「把品牌删掉」永远不生效。编辑一律用显式 {@code lambdaUpdate().set(...)}。</li>
 *   <li><b>停用不拆关联</b>：状态只是「能不能再被选中」，{@code device.model_id} 与历史工单快照
 *       都不动。因此停用端点没有任何级联副作用，也就不需要「先确认没有设备在用」这种前置条件
 *       （列表里的 {@code deviceCount} 只是提示，不构成阻断）。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceModelServiceImpl implements DeviceModelService {

    /** 单页上限，与设备台账保持一致口径 */
    private static final long MAX_PAGE_SIZE = 200L;

    /** 下拉上限：型号总量是「百级」，给足 500 避免借用页下拉被截断 */
    private static final int MAX_OPTION_SIZE = 500;

    private final DeviceModelMapper deviceModelMapper;
    private final DeviceMapper deviceMapper;

    @Override
    public PageResult<DeviceModelVO> page(long page, long size, String keyword, String status) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        String trimmed = StringUtils.hasText(keyword) ? keyword.trim() : null;
        DeviceModelStatus st = null;
        if (StringUtils.hasText(status)) {
            st = DeviceModelStatus.of(status);
            if (st == null) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "型号状态取值不合法：" + status);
            }
        }
        IPage<DeviceModel> result = deviceModelMapper.selectPage(new Page<>(safePage, safeSize),
                Wrappers.<DeviceModel>lambdaQuery()
                        .and(trimmed != null, w -> w.like(DeviceModel::getModelName, trimmed)
                                .or().like(DeviceModel::getBrand, trimmed))
                        .eq(st != null, DeviceModel::getStatus, st == null ? null : st.name())
                        .orderByDesc(DeviceModel::getId));
        return PageResult.of(result, this::toVO);
    }

    @Override
    public List<DeviceModelOptionVO> enabledOptions(String keyword, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), MAX_OPTION_SIZE);
        String trimmed = StringUtils.hasText(keyword) ? keyword.trim() : null;
        List<DeviceModel> models = deviceModelMapper.selectList(Wrappers.<DeviceModel>lambdaQuery()
                .eq(DeviceModel::getStatus, DeviceModelStatus.ENABLED.name())
                .and(trimmed != null, w -> w.like(DeviceModel::getModelName, trimmed)
                        .or().like(DeviceModel::getBrand, trimmed))
                // 按名称排序而不是 id：下拉是给人找的，字典序比录入顺序好找得多
                .orderByAsc(DeviceModel::getModelName)
                .last("LIMIT " + safeLimit));
        return models.stream().map(model -> {
            DeviceModelOptionVO vo = new DeviceModelOptionVO();
            vo.setId(model.getId());
            vo.setModelName(model.getModelName());
            vo.setBrand(model.getBrand());
            return vo;
        }).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(DeviceModelSaveRequest request) {
        String name = requireModelName(request.getModelName());
        assertNameAvailable(name, null);
        DeviceModel entity = new DeviceModel();
        entity.setModelName(name);
        entity.setBrand(trimToNull(request.getBrand()));
        entity.setStatus(DeviceModelStatus.ENABLED.name());
        entity.setRemark(trimToNull(request.getRemark()));
        try {
            deviceModelMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 预检之后仍可能被并发插入抢先，兜底成同一错误码，避免 500
            throw duplicate(name);
        }
        log.info("型号字典新增 id={} name={}", entity.getId(), name);
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, DeviceModelSaveRequest request) {
        DeviceModel existing = requireModel(id);
        String name = requireModelName(request.getModelName());
        assertNameAvailable(name, id);
        try {
            deviceModelMapper.update(null, Wrappers.<DeviceModel>lambdaUpdate()
                    .eq(DeviceModel::getId, id)
                    .set(DeviceModel::getModelName, name)
                    // 显式 set 而非 updateById：not_null 策略下 null 会被跳过，
                    // 那样「清空品牌 / 清空备注」永远不生效
                    .set(DeviceModel::getBrand, trimToNull(request.getBrand()))
                    .set(DeviceModel::getRemark, trimToNull(request.getRemark())));
        } catch (DuplicateKeyException e) {
            throw duplicate(name);
        }
        log.info("型号字典修改 id={} {} → {}", id, existing.getModelName(), name);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, DeviceModelStatusRequest request) {
        DeviceModel existing = requireModel(id);
        DeviceModelStatus target = DeviceModelStatus.of(request.getStatus());
        if (target == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "型号状态取值不合法：" + request.getStatus());
        }
        if (target.name().equals(existing.getStatus())) {
            // 幂等：重复点「停用」不报错。前端重复点击很常见，报错只会让人以为出问题了
            return;
        }
        deviceModelMapper.update(null, Wrappers.<DeviceModel>lambdaUpdate()
                .eq(DeviceModel::getId, id)
                .set(DeviceModel::getStatus, target.name()));
        log.info("型号字典 id={}（{}）状态 {} → {}", id, existing.getModelName(), existing.getStatus(), target.name());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DeviceModelSyncResultVO syncFromLedger() {
        // 只处理「有文本、还没关联」的行：已关联的不动（可能已被人为整理过）
        List<Device> pending = deviceMapper.selectList(Wrappers.<Device>lambdaQuery()
                .isNull(Device::getModelId)
                .isNotNull(Device::getModel)
                .ne(Device::getModel, ""));

        Map<String, List<Long>> byName = new LinkedHashMap<>();
        int skipped = 0;
        for (Device device : pending) {
            String name = device.getModel() == null ? null : device.getModel().trim();
            if (name == null || name.isEmpty()) {
                // 纯空白文本：没有可归入字典的内容，也不算错误（原始文本仍在台账里）
                skipped++;
                continue;
            }
            byName.computeIfAbsent(name, key -> new ArrayList<>()).add(device.getId());
        }

        int createdCount = 0;
        int boundCount = 0;
        for (Map.Entry<String, List<Long>> entry : byName.entrySet()) {
            // eq 在 ci 排序规则下大小写不敏感：库里已有「ThinkPad X1」时，
            // 这里传「thinkpad x1」也能命中，不会重复建条目
            DeviceModel exist = deviceModelMapper.selectOne(Wrappers.<DeviceModel>lambdaQuery()
                    .eq(DeviceModel::getModelName, entry.getKey())
                    .last("LIMIT 1"));
            Long modelId;
            if (exist != null) {
                modelId = exist.getId();
            } else {
                DeviceModel created = new DeviceModel();
                created.setModelName(entry.getKey());
                created.setStatus(DeviceModelStatus.ENABLED.name());
                try {
                    deviceModelMapper.insert(created);
                } catch (DuplicateKeyException e) {
                    // 极少数并发（同一刻有人手工新增了同名型号）：重查一次即可，不回滚整批
                    DeviceModel again = deviceModelMapper.selectOne(Wrappers.<DeviceModel>lambdaQuery()
                            .eq(DeviceModel::getModelName, entry.getKey())
                            .last("LIMIT 1"));
                    if (again == null) {
                        throw e;
                    }
                    modelId = again.getId();
                    boundCount += bindDevices(entry.getValue(), modelId);
                    continue;
                }
                createdCount++;
                modelId = created.getId();
            }
            boundCount += bindDevices(entry.getValue(), modelId);
        }
        log.info("型号字典从台账补齐完成：新建 {} 条，回填 {} 台设备，跳过 {} 台（空白型号）",
                createdCount, boundCount, skipped);

        DeviceModelSyncResultVO vo = new DeviceModelSyncResultVO();
        vo.setCreatedCount(createdCount);
        vo.setBoundCount(boundCount);
        vo.setSkippedCount(skipped);
        return vo;
    }

    /** 把一批设备指向同一型号；返回受影响行数 */
    private int bindDevices(List<Long> deviceIds, Long modelId) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            return 0;
        }
        // 条件里再带一次 isNull：若期间有人手工整理过，不覆盖人工结果
        return deviceMapper.update(null, Wrappers.<Device>lambdaUpdate()
                .in(Device::getId, deviceIds)
                .isNull(Device::getModelId)
                .set(Device::getModelId, modelId));
    }

    private DeviceModel requireModel(Long id) {
        DeviceModel model = id == null ? null : deviceModelMapper.selectById(id);
        if (model == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "型号不存在或已被删除，请刷新后重试");
        }
        return model;
    }

    private String requireModelName(String raw) {
        String name = trimToNull(raw);
        if (name == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "型号名称不能为空");
        }
        if (name.length() > 128) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "型号名称不能超过 128 个字符");
        }
        return name;
    }

    private void assertNameAvailable(String name, Long excludeId) {
        Long count = deviceModelMapper.selectCount(Wrappers.<DeviceModel>lambdaQuery()
                .eq(DeviceModel::getModelName, name)
                .ne(excludeId != null, DeviceModel::getId, excludeId));
        if (count != null && count > 0) {
            throw duplicate(name);
        }
    }

    private BusinessException duplicate(String name) {
        return new BusinessException(ErrorCode.PARAM_INVALID,
                "型号「" + name + "」已存在（型号名称不区分大小写）");
    }

    private DeviceModelVO toVO(DeviceModel model) {
        DeviceModelVO vo = new DeviceModelVO();
        vo.setId(model.getId());
        vo.setModelName(model.getModelName());
        vo.setBrand(model.getBrand());
        vo.setStatus(model.getStatus());
        vo.setStatusLabel(DeviceModelStatus.labelOf(model.getStatus()));
        // 一页最多 200 条，逐条 count 优于为此引入一段自定义 XML；
        // 该数字只用于「停用前看一眼影响面」，不参与业务判定
        vo.setDeviceCount(deviceMapper.selectCount(Wrappers.<Device>lambdaQuery()
                .eq(Device::getModelId, model.getId())));
        vo.setRemark(model.getRemark());
        vo.setCreatedAt(model.getCreatedAt());
        vo.setUpdatedAt(model.getUpdatedAt());
        return vo;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
