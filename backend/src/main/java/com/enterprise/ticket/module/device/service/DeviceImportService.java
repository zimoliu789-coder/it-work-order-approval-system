package com.enterprise.ticket.module.device.service;

import com.enterprise.ticket.module.device.dto.DeviceImportExecuteRequest;
import com.enterprise.ticket.module.device.dto.DeviceImportFailureReportRequest;
import com.enterprise.ticket.module.device.dto.vo.DeviceImportPreviewVO;
import com.enterprise.ticket.module.device.dto.vo.DeviceImportResultVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 设备批量导入服务
 *
 * <p>权限：super_admin / admin（与「新增设备」一致）。
 *
 * <p>流程：下载模板 → 上传校验（<b>不阻断</b>，全部解析后给预览）→ 确认导入（只写通过的行）
 * → 失败行可导出明细 Excel 修改后重导。
 *
 * <p>校验规则与「单条新增设备」严格共用（{@link DeviceService#validateNewDevice}），
 * 避免两条入口的判定出现漂移。
 */
public interface DeviceImportService {

    /** 生成导入模板 .xlsx（表头 + 一行示例数据，示例行标注「导入前删除」并在解析时自动跳过） */
    byte[] buildTemplate();

    /**
     * 上传并校验，返回预览结果（成功 N 条 / 失败 M 条 + 每行失败原因）
     *
     * @throws com.enterprise.ticket.common.exception.BusinessException 文件类型/大小/行数不合法或无法解析
     */
    DeviceImportPreviewVO preview(MultipartFile file);

    /** 确认导入：只写入校验通过的行，每 50 条一个事务 */
    DeviceImportResultVO execute(DeviceImportExecuteRequest request);

    /** 生成失败明细 .xlsx（与模板同列 + 「失败原因」列，修改后可直接重新导入） */
    byte[] buildFailureReport(DeviceImportFailureReportRequest request);
}
