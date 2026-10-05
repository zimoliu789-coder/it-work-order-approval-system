package com.enterprise.ticket.module.attachment.service.impl;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.AttachmentBizType;
import com.enterprise.ticket.common.constant.RoleCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.attachment.entity.Attachment;
import com.enterprise.ticket.module.attachment.mapper.AttachmentMapper;
import com.enterprise.ticket.module.attachment.support.AttachmentStorage;
import com.enterprise.ticket.module.device.entity.DeviceFault;
import com.enterprise.ticket.module.device.mapper.DeviceFaultMapper;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.entity.OrderApprovalNode;
import com.enterprise.ticket.module.order.mapper.OrderApprovalNodeMapper;
import com.enterprise.ticket.module.order.mapper.OrderMapper;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import com.enterprise.ticket.security.LoginUser;
import com.enterprise.ticket.support.MyBatisLambdaCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 附件服务单元测试（规范 §25）
 *
 * <p>附件是「跨业务对象的通用能力」，最大的风险不是文件 IO，而是<b>越权</b>：
 * 拿一个别人工单的 bizId 就能读写附件。因此测试把可见性矩阵与删除权限作为主战场：
 * <ul>
 *   <li>上传/下载/列表：非相关人一律 403（admin 以上放行）；</li>
 *   <li>删除：仅上传者本人或 admin 以上；</li>
 *   <li>业务记录不存在与无权访问分别返回 404 / 403，语义不混淆；</li>
 *   <li>数量上限与磁盘文件缺失等边界给出明确错误码，而不是 500。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AttachmentServiceImplTest {

    private static final Long ORDER_ID = 100L;
    private static final Long DEVICE_ID = 11L;
    private static final Long APPLICANT_ID = 2L;
    private static final Long OTHER_ID = 3L;
    private static final Long APPROVER_ID = 4L;
    private static final Long ADMIN_ID = 9L;
    private static final Long ATTACHMENT_ID = 500L;

    @BeforeAll
    static void initMyBatisLambdaCache() {
        MyBatisLambdaCache.init(Attachment.class, Order.class, OrderApprovalNode.class, DeviceFault.class, User.class);
    }

    @TempDir
    Path tempDir;

    @Mock
    private AttachmentMapper attachmentMapper;
    @Mock
    private AttachmentStorage storage;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderApprovalNodeMapper nodeMapper;
    @Mock
    private DeviceFaultMapper faultMapper;
    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private AttachmentServiceImpl service;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // 辅助构造
    // ------------------------------------------------------------------

    private void login(Long userId, String role) {
        User user = new User();
        user.setId(userId);
        user.setUsername("u" + userId);
        user.setDisplayName("用户" + userId);
        user.setRole(role);
        user.setEnabled(true);
        user.setDimission(false);
        LoginUser loginUser = new LoginUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    private Order order() {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOrderNo("BO-100");
        order.setDeviceId(DEVICE_ID);
        order.setApplicantId(APPLICANT_ID);
        order.setStatus("BORROWED");
        return order;
    }

    private Attachment attachment(Long uploaderId) {
        Attachment a = new Attachment();
        a.setId(ATTACHMENT_ID);
        a.setBizType(AttachmentBizType.APPLY_ATTACHMENT.name());
        a.setBizId(ORDER_ID);
        a.setFileName("材料.pdf");
        a.setStoredPath("2026/09/abc.pdf");
        a.setFileSize(1024L);
        a.setContentType("application/pdf");
        a.setUploaderId(uploaderId);
        a.setDeleted(false);
        return a;
    }

    /**
     * 构造一个 MultipartFile mock。
     *
     * <p>三处文件属性桩一律用 {@code lenient()}：多数「失败路径」用例会在触碰文件之前
     * 就因业务校验（非法业务类型 / 记录不存在 / 越权 / 超限）短路抛出，这些桩根本不会被读到；
     * 在 Mockito 严格桩模式下会被判为 UnnecessaryStubbingException，
     * 与「测试真正想验证的越权行为」无关，故显式放宽。
     */
    private MultipartFile file(String name) {
        MultipartFile f = org.mockito.Mockito.mock(MultipartFile.class);
        lenient().when(f.getOriginalFilename()).thenReturn(name);
        lenient().when(f.getSize()).thenReturn(1024L);
        try {
            lenient().when(f.getInputStream())
                    .thenReturn(new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
            // Mockito 对受检异常的桩不会真正抛；此处仅为满足编译
        }
        return f;
    }

    private ErrorCode codeOf(Runnable action) {
        BusinessException e = assertThrows(BusinessException.class, action::run);
        return e.getErrorCode();
    }

    // ------------------------------------------------------------------
    // 上传
    // ------------------------------------------------------------------

    @Test
    @DisplayName("上传：业务类型非法 → ATTACHMENT_BIZ_TYPE_INVALID")
    void upload_invalidBizType() {
        login(APPLICANT_ID, RoleCode.USER);
        assertEquals(ErrorCode.ATTACHMENT_BIZ_TYPE_INVALID,
                codeOf(() -> service.upload("NOT_A_TYPE", ORDER_ID, file("a.pdf"))));
    }

    @Test
    @DisplayName("上传：关联工单不存在 → ATTACHMENT_BIZ_NOT_FOUND")
    void upload_bizNotFound() {
        login(APPLICANT_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(null);
        assertEquals(ErrorCode.ATTACHMENT_BIZ_NOT_FOUND,
                codeOf(() -> service.upload(AttachmentBizType.APPLY_ATTACHMENT.name(), ORDER_ID, file("a.pdf"))));
    }

    @Test
    @DisplayName("上传：与工单无关的用户 → 403（越权防护）")
    void upload_notVisible() {
        login(OTHER_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order());
        when(nodeMapper.selectCount(any())).thenReturn(0L);
        assertEquals(ErrorCode.FORBIDDEN,
                codeOf(() -> service.upload(AttachmentBizType.APPLY_ATTACHMENT.name(), ORDER_ID, file("a.pdf"))));
    }

    @Test
    @DisplayName("上传：申请人本人 → 成功落盘并落库")
    void upload_asApplicant_success() {
        login(APPLICANT_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order());
        when(attachmentMapper.selectCount(any())).thenReturn(0L);
        when(storage.maxPerBiz()).thenReturn(10);
        when(storage.store(any(), eq("材料.pdf"))).thenReturn("2026/09/abc.pdf");
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(APPLICANT_ID, "刘备")));
        // 真实 MyBatis-Plus 会在 insert 后把自增主键回填进实体；Mockito 不会，
        // 故手工模拟回填，否则下载地址里的 id 会是 null（这正是本用例要固化的行为）。
        when(attachmentMapper.insert(any())).thenAnswer(inv -> {
            inv.getArgument(0, Attachment.class).setId(ATTACHMENT_ID);
            return 1;
        });

        var vo = service.upload(AttachmentBizType.APPLY_ATTACHMENT.name(), ORDER_ID, file("材料.pdf"));

        assertNotNull(vo);
        assertEquals("材料.pdf", vo.getFileName());
        assertEquals("/api/attachments/" + ATTACHMENT_ID + "/download", vo.getDownloadUrl());

        ArgumentCaptor<Attachment> captor = ArgumentCaptor.forClass(Attachment.class);
        verify(attachmentMapper).insert(captor.capture());
        assertEquals("2026/09/abc.pdf", captor.getValue().getStoredPath());
        assertEquals(APPLICANT_ID, captor.getValue().getUploaderId());
        assertEquals(AttachmentBizType.APPLY_ATTACHMENT.name(), captor.getValue().getBizType());
    }

    @Test
    @DisplayName("上传：存储层校验失败（如照片类收到 pdf）原样抛出，不误报为其它错误")
    void upload_storageRejects_propagates() {
        login(APPLICANT_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order());
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.ATTACHMENT_TYPE_NOT_ALLOWED))
                .when(storage).validate(any(), any());
        assertEquals(ErrorCode.ATTACHMENT_TYPE_NOT_ALLOWED,
                codeOf(() -> service.upload(AttachmentBizType.RETURN_PHOTO.name(), ORDER_ID, file("scan.pdf"))));
        verify(attachmentMapper, never()).insert(any());
    }

    @Test
    @DisplayName("上传：附件数量达上限 → ATTACHMENT_LIMIT_EXCEEDED")
    void upload_exceedsLimit() {
        login(APPLICANT_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order());
        when(storage.maxPerBiz()).thenReturn(3);
        when(attachmentMapper.selectCount(any())).thenReturn(3L);
        assertEquals(ErrorCode.ATTACHMENT_LIMIT_EXCEEDED,
                codeOf(() -> service.upload(AttachmentBizType.APPLY_ATTACHMENT.name(), ORDER_ID, file("a.pdf"))));
        verify(attachmentMapper, never()).insert(any());
    }

    // ------------------------------------------------------------------
    // 下载
    // ------------------------------------------------------------------

    @Test
    @DisplayName("下载：与工单无关的用户 → 403")
    void download_notVisible() {
        login(OTHER_ID, RoleCode.USER);
        when(attachmentMapper.selectById(ATTACHMENT_ID)).thenReturn(attachment(APPLICANT_ID));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order());
        when(nodeMapper.selectCount(any())).thenReturn(0L);
        assertEquals(ErrorCode.FORBIDDEN, codeOf(() -> service.download(ATTACHMENT_ID)));
    }

    @Test
    @DisplayName("下载：审批人可下载（可见性含节点审批人）")
    void download_asApprover_success() throws Exception {
        login(APPROVER_ID, RoleCode.USER);
        when(attachmentMapper.selectById(ATTACHMENT_ID)).thenReturn(attachment(APPLICANT_ID));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order());
        when(nodeMapper.selectCount(any())).thenReturn(1L);
        Path stored = tempDir.resolve("abc.pdf");
        Files.writeString(stored, "content");
        when(storage.resolve("2026/09/abc.pdf")).thenReturn(stored);

        var result = service.download(ATTACHMENT_ID);
        assertNotNull(result);
        assertEquals("材料.pdf", result.attachment().getFileName());
        assertTrue(result.resource().exists());
    }

    @Test
    @DisplayName("下载：DB 有记录但磁盘文件缺失 → ATTACHMENT_NOT_FOUND（而非空流/500）")
    void download_fileMissingOnDisk() {
        login(APPLICANT_ID, RoleCode.USER);
        when(attachmentMapper.selectById(ATTACHMENT_ID)).thenReturn(attachment(APPLICANT_ID));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order());
        when(storage.resolve("2026/09/abc.pdf")).thenReturn(tempDir.resolve("missing.pdf"));
        assertEquals(ErrorCode.ATTACHMENT_NOT_FOUND, codeOf(() -> service.download(ATTACHMENT_ID)));
    }

    @Test
    @DisplayName("下载：附件不存在 → ATTACHMENT_NOT_FOUND")
    void download_notFound() {
        login(APPLICANT_ID, RoleCode.USER);
        when(attachmentMapper.selectById(ATTACHMENT_ID)).thenReturn(null);
        assertEquals(ErrorCode.ATTACHMENT_NOT_FOUND, codeOf(() -> service.download(ATTACHMENT_ID)));
    }

    // ------------------------------------------------------------------
    // 删除
    // ------------------------------------------------------------------

    @Test
    @DisplayName("删除：非上传者且非管理员 → 403")
    void delete_notOwnerNotAdmin() {
        login(OTHER_ID, RoleCode.USER);
        when(attachmentMapper.selectById(ATTACHMENT_ID)).thenReturn(attachment(APPLICANT_ID));
        assertEquals(ErrorCode.FORBIDDEN, codeOf(() -> service.delete(ATTACHMENT_ID)));
        verify(attachmentMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("删除：上传者本人 → 软删记录（写 deleted_at）并清盘")
    void delete_owner_success() {
        login(APPLICANT_ID, RoleCode.USER);
        when(attachmentMapper.selectById(ATTACHMENT_ID)).thenReturn(attachment(APPLICANT_ID));
        service.delete(ATTACHMENT_ID);
        // 批次 E 起：删除改为条件 UPDATE（一次落 deleted=1 与 deleted_at），
        // 不再用 @TableLogic 的 deleteById —— 保留期清理需要「删除时刻」作为基准
        verify(attachmentMapper).update(isNull(), any());
        verify(attachmentMapper, never()).deleteById(any(Long.class));
        verify(storage).deleteQuietly("2026/09/abc.pdf");
    }

    @Test
    @DisplayName("删除：admin 可删除他人上传的附件")
    void delete_admin_success() {
        login(ADMIN_ID, RoleCode.ADMIN);
        when(attachmentMapper.selectById(ATTACHMENT_ID)).thenReturn(attachment(APPLICANT_ID));
        service.delete(ATTACHMENT_ID);
        verify(attachmentMapper).update(isNull(), any());
    }

    // ------------------------------------------------------------------
    // 列表
    // ------------------------------------------------------------------

    @Test
    @DisplayName("列表：与工单无关的用户 → 403")
    void list_notVisible() {
        login(OTHER_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order());
        when(nodeMapper.selectCount(any())).thenReturn(0L);
        assertEquals(ErrorCode.FORBIDDEN,
                codeOf(() -> service.listByBiz(AttachmentBizType.APPLY_ATTACHMENT.name(), ORDER_ID)));
    }

    @Test
    @DisplayName("列表：申请人可读，返回上传人姓名与图片标记")
    void list_asApplicant_returnsRows() {
        login(APPLICANT_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order());
        when(attachmentMapper.selectList(any())).thenReturn(List.of(attachment(APPLICANT_ID)));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(APPLICANT_ID, "刘备")));
        when(storage.isImage("材料.pdf")).thenReturn(false);

        var rows = service.listByBiz(AttachmentBizType.APPLY_ATTACHMENT.name(), ORDER_ID);
        assertEquals(1, rows.size());
        assertEquals("刘备", rows.get(0).getUploaderName());
        assertEquals(false, rows.get(0).getImage());
    }

    @Test
    @DisplayName("列表：无附件时返回空列表（不抛错）")
    void list_empty() {
        login(APPLICANT_ID, RoleCode.USER);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order());
        when(attachmentMapper.selectList(any())).thenReturn(List.of());
        assertTrue(service.listByBiz(AttachmentBizType.APPLY_ATTACHMENT.name(), ORDER_ID).isEmpty());
    }

    // ------------------------------------------------------------------
    // 故障照片（另一业务主体）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("故障照片：上报人可上传；无关用户 403")
    void faultPhoto_visibility() {
        DeviceFault fault = new DeviceFault();
        fault.setId(700L);
        fault.setDeviceId(DEVICE_ID);
        fault.setReporterId(APPLICANT_ID);
        fault.setStatus("PENDING_REPAIR");

        login(OTHER_ID, RoleCode.USER);
        when(faultMapper.selectById(700L)).thenReturn(fault);
        assertEquals(ErrorCode.FORBIDDEN,
                codeOf(() -> service.listByBiz(AttachmentBizType.FAULT_PHOTO.name(), 700L)));

        login(APPLICANT_ID, RoleCode.USER);
        when(attachmentMapper.selectList(any())).thenReturn(List.of());
        assertTrue(service.listByBiz(AttachmentBizType.FAULT_PHOTO.name(), 700L).isEmpty());
    }

    private User user(Long id, String displayName) {
        User u = new User();
        u.setId(id);
        u.setUsername("u" + id);
        u.setDisplayName(displayName);
        u.setRole(RoleCode.USER);
        return u;
    }
}
