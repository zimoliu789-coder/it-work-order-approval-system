package com.enterprise.ticket.module.attachment.service;

import com.enterprise.ticket.module.attachment.dto.AttachmentDownload;
import com.enterprise.ticket.module.attachment.dto.vo.AttachmentVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 附件服务（ 附件上传通用能力）
 *
 * <p>四类关联业务：申请附件 / 驳回附件 / 归还照片 / 故障照片，见
 * {@link com.enterprise.ticket.common.constant.AttachmentBizType}。
 *
 * <p><b>权限口径</b>：上传与下载都要求调用者对该业务记录**可见**
 * （工单：申请人 / 审批人 / 实际执行人 / admin 以上；故障：上报人 / 处理人 / admin 以上），
 * 未授权一律 {@code 403}（ 明确要求）。
 */
public interface AttachmentService {

    /** 上传附件（校验类型/大小/数量 → 落盘 → 落库），返回展示对象 */
    AttachmentVO upload(String bizType, Long bizId, MultipartFile file);

    /** 下载附件（含鉴权）；下载行为由控制器记入操作日志 */
    AttachmentDownload download(Long id);

    /** 删除附件（软删；仅上传者本人或 admin 以上可删） */
    void delete(Long id);

    /** 按业务查询附件列表（含鉴权，按上传顺序升序） */
    List<AttachmentVO> listByBiz(String bizType, Long bizId);
}
