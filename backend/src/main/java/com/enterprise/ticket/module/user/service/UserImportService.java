package com.enterprise.ticket.module.user.service;

import com.enterprise.ticket.module.user.dto.UserImportExecuteRequest;
import com.enterprise.ticket.module.user.dto.UserImportFailureReportRequest;
import com.enterprise.ticket.module.user.dto.vo.UserImportPreviewVO;
import com.enterprise.ticket.module.user.dto.vo.UserImportResultVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 员工批量导入服务（需求方 2026-09-18 小迭代 · ）
 *
 * <p>权限：仅 super_admin（与「新增员工」一致）。
 *
 * <p>流程与设备导入完全同构：下载模板 → 上传校验（<b>不阻断</b>，全部解析后给预览）
 * → 确认导入（只写通过的行）→ 失败行可导出明细 Excel 修改后重导。
 *
 * <h2>双重唯一校验（本需求的核心）</h2>
 * 每一行都要同时通过两道唯一性检查，且每道都要覆盖<b>两个来源</b>：
 * <table border="1">
 *   <caption>名称唯一性矩阵</caption>
 *   <tr><th></th><th>库内已有</th><th>文件内重复</th></tr>
 *   <tr><td>姓名</td><td>「姓名已存在：张三」</td><td>「文件内第 3 行与第 7 行姓名重复」</td></tr>
 *   <tr><td>登录名</td><td>「登录名已存在：zhangsan」</td><td>「文件内第 3 行与第 7 行登录名重复」</td></tr>
 * </table>
 * 「文件内重复」只判<b>通过其它校验的行</b>之间的重复（与设备导入的资产编号同一策略）：
 * 若第 3 行本身因「角色不合法」失败，它就不占用该姓名，第 7 行仍可正常导入 ——
 * 否则用户会看到「先修 A 再冒出 B」的连环报错。
 */
public interface UserImportService {

    /** 生成导入模板 .xlsx（表头 + 一行示例数据，示例行标注「导入前删除」并在解析时自动跳过） */
    byte[] buildTemplate();

    /**
     * 上传并校验，返回预览结果（成功 N 条 / 失败 M 条 + 每行失败原因）
     *
     * @throws com.enterprise.ticket.common.exception.BusinessException 文件类型/大小/行数不合法或无法解析
     */
    UserImportPreviewVO preview(MultipartFile file);

    /** 确认导入：只写入校验通过的行，每 50 条一个事务；新员工统一「首登强制改密」 */
    UserImportResultVO execute(UserImportExecuteRequest request);

    /** 生成失败明细 .xlsx（与模板同列 + 「失败原因」列，修改后可直接重新导入） */
    byte[] buildFailureReport(UserImportFailureReportRequest request);
}
