package com.enterprise.ticket.module.order.service.impl;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.FormFieldType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.common.form.FormField;
import com.enterprise.ticket.common.form.FormSchema;
import com.enterprise.ticket.common.form.FormDataValidator;
import com.enterprise.ticket.module.applytype.service.ApplyTypeService;
import com.enterprise.ticket.module.form.service.FormTemplateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 借用单自定义字段的校验与归一化（V59 / 批次 3.5）。
 *
 * <h2>为什么值得一个独立的测试类</h2>
 * {@code OrderServiceImpl#create} 有 200 行、十几个依赖，驱动它要 mock 一大堆东西；
 * 而本批新增的逻辑全在 {@link OrderServiceImpl#resolveBorrowExtraFormData} 这三行判定里。
 * 该方法 therefore 开成包级可见（与 {@code ApplyTypeServiceImpl#bySortOrder()} 同一做法），
 * 直接驱动三条规则。
 *
 * <p>三条规则各对应一种真实故障：
 * <ul>
 *   <li><b>没绑表单版本却抛异常</b> ⇒ 升级未跑播种的存量部署连普通报修 / 借用都提交不了
 *       （最严重的一条，且在现场很难定位到「是表单版本没播种」）；</li>
 *   <li><b>不校验就落库</b> ⇒ 任何人都能往 {@code form_data_json} 里塞任意 key，
 *       详情页回显时变成一堆看不懂的东西；</li>
 *   <li><b>把系统字段也塞进 map</b> ⇒ 与专用入参出现第二份真相，改一处忘一处就会漂移。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OrderBorrowFormDataTest {

    @Mock
    private ApplyTypeService applyTypeService;
    @Mock
    private FormTemplateService formTemplateService;
    @Mock
    private FormDataValidator formDataValidator;

    @InjectMocks
    private OrderServiceImpl service;

    private static final Long FORM_VERSION_ID = 88L;

    private static FormSchema borrowSchema() {
        FormField extra = new FormField();
        extra.setKey("need_adapter");
        extra.setLabel("是否需要电源适配器");
        extra.setType(FormFieldType.SELECT.name());
        extra.setRequired(false);
        extra.setWidth(2);
        FormSchema schema = new FormSchema();
        schema.setFields(new ArrayList<>(List.of(extra)));
        return schema;
    }

    @Test
    @DisplayName("空 formData：不查表单、不校验，直接返回空 map（存量调用方零影响）")
    void emptyFormDataSkipsEverything() {
        assertEquals(Map.of(), service.resolveBorrowExtraFormData(FORM_VERSION_ID, null));
        assertEquals(Map.of(), service.resolveBorrowExtraFormData(FORM_VERSION_ID, Map.of()));
        verify(formTemplateService, never()).requirePublishedSchema(any());
        verify(formDataValidator, never()).validate(any(), any());
    }

    @Test
    @DisplayName("★ 没绑表单版本：空值放行、非空明确拒绝 —— 不让存量部署连普通借用都提交不了")
    void missingFormVersionToleratesEmptyButRejectsData() {
        // 空：照常放行（这是「升级未跑播种」时唯一不能让普通员工受困的路径）
        assertEquals(Map.of(), service.resolveBorrowExtraFormData(null, Map.of()));

        // 非空：明确拒绝，而不是静默丢弃（静默丢弃 = 用户填了却没存，且无从排查）
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.resolveBorrowExtraFormData(null, Map.of("need_adapter", "YES")));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("尚未配置表单版本"), "原因要说清楚，实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("★ 绑了表单版本：按那一版 schema 校验后原样返回（不加工、不丢 key）")
    void validatesAgainstPublishedSchema() {
        when(formTemplateService.requirePublishedSchema(FORM_VERSION_ID)).thenReturn(borrowSchema());

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("need_adapter", "YES");
        Map<String, Object> result = service.resolveBorrowExtraFormData(FORM_VERSION_ID, raw);

        // 必须走一遍 schema 校验：直调接口塞任意 key 就靠这一步拦
        verify(formDataValidator).validate(any(), any());
        assertEquals("YES", result.get("need_adapter"));
        // 返回新 map 而非入参本身：调用方后续若改动不会反向污染请求对象
        assertTrue(result != raw);
    }
}
