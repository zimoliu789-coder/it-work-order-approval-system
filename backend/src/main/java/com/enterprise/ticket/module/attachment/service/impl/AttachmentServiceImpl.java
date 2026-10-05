package com.enterprise.ticket.module.attachment.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.AttachmentBizType;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.util.SecurityUtils;
import com.enterprise.ticket.module.attachment.dto.AttachmentDownload;
import com.enterprise.ticket.module.attachment.dto.vo.AttachmentVO;
import com.enterprise.ticket.module.attachment.entity.Attachment;
import com.enterprise.ticket.module.attachment.mapper.AttachmentMapper;
import com.enterprise.ticket.module.attachment.service.AttachmentService;
import com.enterprise.ticket.module.attachment.support.AttachmentStorage;
import com.enterprise.ticket.module.device.entity.DeviceFault;
import com.enterprise.ticket.module.device.mapper.DeviceFaultMapper;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.entity.OrderApprovalNode;
import com.enterprise.ticket.module.order.mapper.OrderApprovalNodeMapper;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 附件服务实现
 *
 * <p><b>跨模块依赖</b>：按项目约定只依赖其它模块的 Mapper（{@code OrderMapper} /
 * {@code OrderApprovalNodeMapper} / {@code DeviceFaultMapper} / {@code UserMapper}），
 * 不反向依赖业务 Service，避免循环依赖。
 *
 * <p><b>为什么可见性在这里独立实现</b>：附件要按「业务主体」判权限，
 * 而工单可见性口径（申请人 / 审批人 / 实际执行人 / admin 以上，）
 * 在工单服务里是私有方法且与工单实体强耦合；附件模块需要的是「给定 bizType + bizId
 * 判一个用户能否访问」这一更窄的判定。这里按同一口径实现，并由回归脚本覆盖越权用例，
 * 保证两处不会悄悄分叉。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentServiceImpl implements AttachmentService {

    private final AttachmentMapper attachmentMapper;
    private final AttachmentStorage storage;
    private final OrderMapper orderMapper;
    private final OrderApprovalNodeMapper nodeMapper;
    private final DeviceFaultMapper faultMapper;
    private final UserMapper userMapper;

    // ------------------------------------------------------------------
    // 上传
    // ------------------------------------------------------------------

    @Override
    public AttachmentVO upload(String bizType, Long bizId, MultipartFile file) {
        AttachmentBizType type = requireBizType(bizType);
        requireBizId(bizId);
        Long userId = requireCurrentUserId();
        String role = currentRole();

        assertBizAccessible(type, bizId, userId, role);
        storage.validate(type, file);

        // 数量上限：防止单条业务附件无限增长（配置 app.attachment.max-per-biz）
        Long existing = attachmentMapper.selectCount(Wrappers.<Attachment>lambdaQuery()
                .eq(Attachment::getBizType, type.name())
                .eq(Attachment::getBizId, bizId));
        if (existing != null && existing >= storage.maxPerBiz()) {
            throw new BusinessException(ErrorCode.ATTACHMENT_LIMIT_EXCEEDED,
                    "单条记录最多 " + storage.maxPerBiz() + " 个附件");
        }

        String relative;
        try (var in = file.getInputStream()) {
            relative = storage.store(in, file.getOriginalFilename());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("附件读取失败：bizType={} bizId={} name={}", bizType, bizId, file.getOriginalFilename(), e);
            throw new BusinessException(ErrorCode.ATTACHMENT_SAVE_FAILED);
        }

        Attachment entity = new Attachment();
        entity.setBizType(type.name());
        entity.setBizId(bizId);
        entity.setFileName(safeFileName(file.getOriginalFilename()));
        entity.setStoredPath(relative);
        entity.setFileSize(file.getSize());
        entity.setContentType(file.getContentType());
        entity.setUploaderId(userId);
        entity.setDeleted(false);
        entity.setCreatedAt(LocalDateTime.now());
        attachmentMapper.insert(entity);

        log.info("附件上传：id={} type={} bizId={} size={} uploader={}",
                entity.getId(), type.name(), bizId, entity.getFileSize(), userId);
        return toVO(entity, Map.of(userId, displayNameOf(userId)));
    }

    // ------------------------------------------------------------------
    // 下载
    // ------------------------------------------------------------------

    @Override
    public AttachmentDownload download(Long id) {
        Attachment entity = requireAttachment(id);
        Long userId = requireCurrentUserId();
        String role = currentRole();
        AttachmentBizType type = requireBizType(entity.getBizType());
        assertBizAccessible(type, entity.getBizId(), userId, role);

        var path = storage.resolve(entity.getStoredPath());
        if (!Files.exists(path)) {
            // DB 有记录但磁盘文件缺失：属于运维异常，给出明确 404 而不是空流
            log.warn("附件记录存在但磁盘文件缺失：id={} path={}", id, entity.getStoredPath());
            throw new BusinessException(ErrorCode.ATTACHMENT_NOT_FOUND);
        }
        Resource resource = new PathResource(path);
        return new AttachmentDownload(entity, resource);
    }

    // ------------------------------------------------------------------
    // 删除
    // ------------------------------------------------------------------

    @Override
    public void delete(Long id) {
        Attachment entity = requireAttachment(id);
        Long userId = requireCurrentUserId();
        boolean isOwner = Objects.equals(entity.getUploaderId(), userId);
        if (!isOwner && !RoleCode.isAdminOrAbove(currentRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅上传者本人或管理员可删除附件");
        }
        // 先删库（逻辑删除 + 记录删除时刻）再尽力删盘：即使磁盘删除失败，也不再对外可见，
        // 语义上已删除。写 deleted_at 的原因见 Attachment#deletedAt —— 保留期清理
        // 要从「删除那一刻」起算，不能用上传时间代替；而 @TableLogic 的
        // deleteById 只写 deleted，故这里显式用条件 UPDATE 一次落两个字段。
        attachmentMapper.update(null, Wrappers.<Attachment>lambdaUpdate()
                .eq(Attachment::getId, id)
                .set(Attachment::getDeleted, true)
                .set(Attachment::getDeletedAt, LocalDateTime.now()));
        storage.deleteQuietly(entity.getStoredPath());
        log.info("附件删除：id={} uploader={} operator={}", id, entity.getUploaderId(), userId);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Override
    public List<AttachmentVO> listByBiz(String bizType, Long bizId) {
        AttachmentBizType type = requireBizType(bizType);
        requireBizId(bizId);
        Long userId = requireCurrentUserId();
        assertBizAccessible(type, bizId, userId, currentRole());

        List<Attachment> rows = attachmentMapper.selectList(Wrappers.<Attachment>lambdaQuery()
                .eq(Attachment::getBizType, type.name())
                .eq(Attachment::getBizId, bizId)
                .orderByAsc(Attachment::getId));
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> uploaderIds = new LinkedHashSet<>();
        rows.forEach(row -> uploaderIds.add(row.getUploaderId()));
        Map<Long, String> names = userNameMap(uploaderIds);
        return rows.stream().map(row -> toVO(row, names)).toList();
    }

    // ------------------------------------------------------------------
    // 可见性
    // ------------------------------------------------------------------

    /**
     * 业务可见性校验（ / ）
     *
     * <ul>
     *   <li>admin 以上直接放行（规范：管理端可查看全部业务数据）；</li>
     *   <li>工单类：申请人 / 当前实际执行人 / 该工单任一审批节点上的审批人；</li>
     *   <li>故障类：上报人 / 处理人。</li>
     * </ul>
     * 不满足抛 {@code FORBIDDEN}(403)，业务记录不存在抛 {@code ATTACHMENT_BIZ_NOT_FOUND}(404)。
     */
    private void assertBizAccessible(AttachmentBizType type, Long bizId, Long userId, String role) {
        if (RoleCode.isAdminOrAbove(role)) {
            return;
        }
        if (type.getTarget() == AttachmentBizType.BizTarget.ORDER) {
            Order order = orderMapper.selectById(bizId);
            if (order == null) {
                throw new BusinessException(ErrorCode.ATTACHMENT_BIZ_NOT_FOUND);
            }
            if (Objects.equals(order.getApplicantId(), userId)
                    || Objects.equals(order.getActualFinalHandlerId(), userId)) {
                return;
            }
            Long approverCount = nodeMapper.selectCount(Wrappers.<OrderApprovalNode>lambdaQuery()
                    .eq(OrderApprovalNode::getOrderId, bizId)
                    .eq(OrderApprovalNode::getApproverId, userId));
            if (approverCount != null && approverCount > 0) {
                return;
            }
        } else {
            DeviceFault fault = faultMapper.selectById(bizId);
            if (fault == null) {
                throw new BusinessException(ErrorCode.ATTACHMENT_BIZ_NOT_FOUND);
            }
            if (Objects.equals(fault.getReporterId(), userId)
                    || Objects.equals(fault.getHandledBy(), userId)) {
                return;
            }
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该业务的附件");
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    private Attachment requireAttachment(Long id) {
        Attachment entity = id == null ? null : attachmentMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.ATTACHMENT_NOT_FOUND);
        }
        return entity;
    }

    private AttachmentBizType requireBizType(String bizType) {
        AttachmentBizType type = AttachmentBizType.of(bizType);
        if (type == null) {
            throw new BusinessException(ErrorCode.ATTACHMENT_BIZ_TYPE_INVALID);
        }
        return type;
    }

    private void requireBizId(Long bizId) {
        if (bizId == null) {
            throw new BusinessException(ErrorCode.ATTACHMENT_BIZ_NOT_FOUND);
        }
    }

    private AttachmentVO toVO(Attachment entity, Map<Long, String> names) {
        String uploaderName = names.get(entity.getUploaderId());
        return AttachmentVO.of(entity, uploaderName == null ? "未知用户" : uploaderName,
                storage.isImage(entity.getFileName()));
    }

    private Map<Long, String> userNameMap(Collection<Long> userIds) {
        Collection<Long> ids = userIds == null ? List.of() : userIds.stream().filter(Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(User::getId,
                        user -> user.getDisplayName() == null ? user.getUsername() : user.getDisplayName(),
                        (a, b) -> a));
    }

    private String displayNameOf(Long userId) {
        return userNameMap(List.of(userId)).getOrDefault(userId, "用户#" + userId);
    }

    private Long requireCurrentUserId() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    private String currentRole() {
        var loginUser = SecurityUtils.getCurrentUser();
        return loginUser == null ? null : loginUser.getRole();
    }

    /** 原始文件名清洗：仅用于展示，去掉路径分隔符与控制字符，避免响应头注入与显示错乱 */
    private String safeFileName(String original) {
        if (original == null || original.isBlank()) {
            return "attachment";
        }
        String name = original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty()) {
            return "attachment";
        }
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }
}
