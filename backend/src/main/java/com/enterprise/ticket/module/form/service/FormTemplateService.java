package com.enterprise.ticket.module.form.service;

import com.enterprise.ticket.common.form.FormSchema;
import com.enterprise.ticket.module.form.dto.FormTemplateSaveRequest;
import com.enterprise.ticket.module.form.dto.vo.FormTemplateDetailVO;
import com.enterprise.ticket.module.form.dto.vo.FormTemplateVO;
import com.enterprise.ticket.module.form.dto.vo.FormTemplateVersionVO;

import java.util.List;

/**
 * 表单模板服务
 *
 * <h2>草稿 / 发布的两段式语义</h2>
 * <ul>
 *   <li><b>保存草稿</b>：宽松保存，允许半成品（还没配选项的下拉、还没写显示名的字段），
 *       否则用户没法边想边存；同一模板同时最多只有 1 份草稿。</li>
 *   <li><b>发布</b>：严格校验（至少 1 个字段、key 唯一合法、选项完整……），
 *       校验通过后该草稿被「冻结」为历史版本，发布后<b>不可再改</b>。</li>
 * </ul>
 * 选在「发布」而不是「保存」做严格校验，是因为错误暴露在<b>配置者</b>面前时成本最低：
 * 他正开着设计器，改起来最快；而一旦发布，错误就会暴露在成百上千个提交者面前。
 */
public interface FormTemplateService {

    /** 模板列表（含最新已发布版本号、是否含草稿、字段数） */
    List<FormTemplateVO> listTemplates();

    /**
     * 模板详情（含 schema）。
     *
     * <p>有草稿给草稿，无草稿给最新已发布版本 —— 保证设计器总有内容可编辑。
     */
    FormTemplateDetailVO getDetail(Long templateId);

    /** 新建模板（同时创建 v1 草稿） */
    Long createTemplate(FormTemplateSaveRequest request);

    /** 更新草稿：已有草稿则覆盖，无草稿则自动新建一版草稿（「编辑已发布模板自动创建草稿版本」） */
    void updateTemplate(Long templateId, FormTemplateSaveRequest request);

    /**
     * 发布当前草稿为新版本。
     *
     * @return 新版本的 id（申请类型随后引用它）
     */
    Long publish(Long templateId);

    /** 版本列表（倒序，含草稿，标注是否为草稿） */
    List<FormTemplateVersionVO> listVersions(Long templateId);

    /** 某版本详情（含 schema），用于版本历史预览 */
    FormTemplateVersionVO getVersion(Long versionId);

    /** 删除模板（已被申请类型引用时拒绝，只能停用） */
    void deleteTemplate(Long templateId);

    // ------------------------------------------------------------------
    // 跨模块契约
    // ------------------------------------------------------------------

    /**
     * 取「已发布版本」的表单定义。
     *
     * <p>供申请类型创建校验与自定义工单提交校验复用：这两处都必须确认
     * 「引用的版本确实已发布」，否则会出现「引用了一份还在草稿里的表单」——
     * 那种工单一旦提交，凭它当时的 schema 根本查不到（草稿随时可被覆盖）。
     *
     * @throws com.enterprise.ticket.common.exception.BusinessException
     *         版本不存在 / 尚未发布
     */
    FormSchema requirePublishedSchema(Long versionId);

    /** 该版本是否为已发布版本（不抛异常，供界面渲染「能否选择」） */
    boolean isPublishedVersion(Long versionId);
}
