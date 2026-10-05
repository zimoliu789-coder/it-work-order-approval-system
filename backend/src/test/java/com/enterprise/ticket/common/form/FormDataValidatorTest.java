package com.enterprise.ticket.common.form;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.DeviceStatus;
import com.enterprise.ticket.common.constant.FormFieldType;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.department.entity.Department;
import com.enterprise.ticket.module.department.mapper.DepartmentMapper;
import com.enterprise.ticket.module.device.entity.Device;
import com.enterprise.ticket.module.device.mapper.DeviceMapper;
import com.enterprise.ticket.module.user.entity.User;
import com.enterprise.ticket.module.user.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 动态表单数据校验单元测试（Phase 14）
 *
 * <p>纯 Mockito：引用类字段要查库，因此 mock {@code UserMapper / DeviceMapper / DepartmentMapper}。
 *
 * <p>为什么这些用例重要：{@link FormDataValidator} 是「用户提交的数据」进入数据库前的最后一道
 * 服务端校验。它与前端 {@code validateFormData} 同口径却独立实现 —— 前端只为即时提示，
 * 真正的准入在这里。一旦这里漏判，脏数据（非法选项、越界数字、指向不存在实体的 id）
 * 就会落库，而这类问题在详情页回显时才暴露，排查成本极高。
 */
@ExtendWith(MockitoExtension.class)
class FormDataValidatorTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private DeviceMapper deviceMapper;
    @Mock
    private DepartmentMapper departmentMapper;

    @InjectMocks
    private FormDataValidator validator;

    // ------------------------------------------------------------------
    // 辅助构造
    // ------------------------------------------------------------------

    private static FormField field(String type, String key, String label) {
        FormField field = new FormField();
        field.setType(type);
        field.setKey(key);
        field.setLabel(label);
        field.setRequired(false);
        field.setWidth(1);
        return field;
    }

    private static FormSchema schema(FormField... fields) {
        FormSchema schema = new FormSchema();
        schema.setFields(new ArrayList<>(List.of(fields)));
        return schema;
    }

    private static FormOption option(String value, String label) {
        FormOption option = new FormOption();
        option.setValue(value);
        option.setLabel(label);
        return option;
    }

    private static Map<String, Object> data(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    // ------------------------------------------------------------------
    // 必填 / 未定义字段
    // ------------------------------------------------------------------

    @Test
    @DisplayName("必填为空 → 收集到「必填」错误；非必填为空 → 通过")
    void requiredAndOptional() {
        FormField required = field(FormFieldType.TEXT.name(), "reason", "申请事由");
        required.setRequired(true);
        FormField optional = field(FormFieldType.TEXT.name(), "note", "备注");

        List<String> errors = validator.validateAndCollect(schema(required, optional), Map.of());

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("「申请事由」为必填项"));
    }

    @Test
    @DisplayName("提交了 schema 未定义的字段 → 拒绝（而不是静默丢弃）")
    void rejectsUndefinedField() {
        List<String> errors = validator.validateAndCollect(
                schema(field(FormFieldType.TEXT.name(), "reason", "事由")),
                data("reason", "办公", "hack", "x"));

        assertTrue(errors.stream().anyMatch(e -> e.contains("未定义的字段") && e.contains("hack")));
    }

    @Test
    @DisplayName("布局类字段即使 required 为真也不参与校验")
    void layoutFieldsSkipped() {
        FormField divider = field(FormFieldType.DIVIDER.name(), null, "分隔");
        divider.setRequired(true);

        assertTrue(validator.validateAndCollect(schema(divider), Map.of()).isEmpty());
    }

    // ------------------------------------------------------------------
    // 文本 / 数字
    // ------------------------------------------------------------------

    @Test
    @DisplayName("文本：长度与正则")
    void validateText() {
        FormField field = field(FormFieldType.TEXT.name(), "code", "工号");
        field.setMinLength(2);
        field.setMaxLength(4);
        field.setPattern("^[A-Z]+$");

        assertTrue(validator.validateAndCollect(schema(field), data("code", "A")).get(0).contains("不能少于 2"));
        assertTrue(validator.validateAndCollect(schema(field), data("code", "ABCDE")).get(0).contains("不能超过 4"));
        assertTrue(validator.validateAndCollect(schema(field), data("code", "abc")).get(0).contains("格式不符合要求"));
        assertTrue(validator.validateAndCollect(schema(field), data("code", "AB")).isEmpty());
    }

    @Test
    @DisplayName("数字：非数字 / 越界 / 小数位超限")
    void validateNumber() {
        FormField field = field(FormFieldType.NUMBER.name(), "amount", "金额");
        field.setMin(0.0);
        field.setMax(1000.0);
        field.setPrecision(2);

        assertTrue(validator.validateAndCollect(schema(field), data("amount", "abc")).get(0).contains("必须是数字"));
        assertTrue(validator.validateAndCollect(schema(field), data("amount", -1)).get(0).contains("不能小于 0"));
        assertTrue(validator.validateAndCollect(schema(field), data("amount", 2000)).get(0).contains("不能大于 1000"));
        assertTrue(validator.validateAndCollect(schema(field), data("amount", 1.234)).get(0).contains("小数位数不能超过 2"));
        assertTrue(validator.validateAndCollect(schema(field), data("amount", 12.34)).isEmpty());
    }

    // ------------------------------------------------------------------
    // 日期
    // ------------------------------------------------------------------

    @Test
    @DisplayName("日期：格式错误 / 不能早于今天 / 自定义区间越界")
    void validateDate() {
        FormField plain = field(FormFieldType.DATE.name(), "d", "日期");
        assertTrue(validator.validateAndCollect(schema(plain), data("d", "2026/01/01")).get(0).contains("格式不合法"));

        FormField notBefore = field(FormFieldType.DATE.name(), "d", "预约日期");
        notBefore.setDateLimit("NOT_BEFORE_TODAY");
        String yesterday = LocalDate.now().minusDays(1).toString();
        assertTrue(validator.validateAndCollect(schema(notBefore), data("d", yesterday)).get(0).contains("不能早于今天"));

        FormField custom = field(FormFieldType.DATE.name(), "d", "日期");
        custom.setDateLimit("CUSTOM");
        custom.setDateMin("2026-01-01");
        custom.setDateMax("2026-12-31");
        assertTrue(validator.validateAndCollect(schema(custom), data("d", "2025-12-31")).get(0).contains("不能早于 2026-01-01"));
        assertTrue(validator.validateAndCollect(schema(custom), data("d", "2026-06-06")).isEmpty());
    }

    @Test
    @DisplayName("日期时间：格式为 yyyy-MM-dd HH:mm:ss")
    void validateDateTime() {
        FormField field = field(FormFieldType.DATETIME.name(), "dt", "时间");
        assertTrue(validator.validateAndCollect(schema(field), data("dt", "2026-06-06")).get(0).contains("格式不合法"));
        assertTrue(validator.validateAndCollect(schema(field), data("dt", "2026-06-06 10:00:00")).isEmpty());
    }

    // ------------------------------------------------------------------
    // 选项
    // ------------------------------------------------------------------

    @Test
    @DisplayName("单选：值必须命中选项")
    void validateOption() {
        FormField field = field(FormFieldType.SELECT.name(), "s", "类别");
        field.setOptions(List.of(option("A", "甲"), option("B", "乙")));

        assertTrue(validator.validateAndCollect(schema(field), data("s", "C")).get(0).contains("取值不在可选范围内"));
        assertTrue(validator.validateAndCollect(schema(field), data("s", "A")).isEmpty());
    }

    @Test
    @DisplayName("多选：必须是数组，且每项命中选项")
    void validateOptionMulti() {
        FormField field = field(FormFieldType.MULTI_SELECT.name(), "m", "标签");
        field.setOptions(List.of(option("A", "甲"), option("B", "乙")));

        assertTrue(validator.validateAndCollect(schema(field), data("m", "A")).get(0).contains("必须是数组"));
        assertTrue(validator.validateAndCollect(schema(field), data("m", List.of("A", "X"))).get(0).contains("不在可选范围内"));
        assertTrue(validator.validateAndCollect(schema(field), data("m", List.of("A", "B"))).isEmpty());
    }

    // ------------------------------------------------------------------
    // 附件
    // ------------------------------------------------------------------

    @Test
    @DisplayName("附件：必须是 id 数组、数量上限、非法 id 拒绝")
    void validateFiles() {
        FormField field = field(FormFieldType.FILE.name(), "f", "附件");
        field.setMaxCount(2);

        assertTrue(validator.validateAndCollect(schema(field), data("f", "not-array")).get(0).contains("必须是附件 id 数组"));
        assertTrue(validator.validateAndCollect(schema(field), data("f", List.of(1, 2, 3))).get(0).contains("最多上传 2 个"));
        assertTrue(validator.validateAndCollect(schema(field), data("f", List.of(1, "abc"))).get(0).contains("非法的附件 id"));
        assertTrue(validator.validateAndCollect(schema(field), data("f", List.of(1, 2))).isEmpty());
    }

    // ------------------------------------------------------------------
    // 引用类（查库）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("人员引用：不存在 / 已离职 / 已禁用 → 拒绝；在职启用 → 通过")
    void validateUserRef() {
        FormField field = field(FormFieldType.USER.name(), "owner", "负责人");

        when(userMapper.selectById(1L)).thenReturn(activeUser(1L, false, true));
        when(userMapper.selectById(2L)).thenReturn(activeUser(2L, true, true));
        when(userMapper.selectById(3L)).thenReturn(activeUser(3L, false, false));

        assertTrue(validator.validateAndCollect(schema(field), data("owner", 1)).isEmpty());
        assertTrue(validator.validateAndCollect(schema(field), data("owner", 2)).get(0).contains("不存在或已离职"));
        assertTrue(validator.validateAndCollect(schema(field), data("owner", 3)).get(0).contains("不存在或已离职"));
        assertTrue(validator.validateAndCollect(schema(field), data("owner", 999)).get(0).contains("不存在或已离职"));
        assertTrue(validator.validateAndCollect(schema(field), data("owner", "abc")).get(0).contains("必须是人员 id"));
    }

    @Test
    @DisplayName("设备引用：不存在 / 已报废 → 拒绝")
    void validateDeviceRef() {
        FormField field = field(FormFieldType.DEVICE.name(), "device", "设备");

        Device ok = new Device();
        ok.setId(1L);
        ok.setStatus(DeviceStatus.AVAILABLE.name());
        Device scrapped = new Device();
        scrapped.setId(2L);
        scrapped.setStatus(DeviceStatus.SCRAPPED.name());

        when(deviceMapper.selectById(1L)).thenReturn(ok);
        when(deviceMapper.selectById(2L)).thenReturn(scrapped);

        assertTrue(validator.validateAndCollect(schema(field), data("device", 1)).isEmpty());
        assertTrue(validator.validateAndCollect(schema(field), data("device", 2)).get(0).contains("不存在或已报废"));
        assertTrue(validator.validateAndCollect(schema(field), data("device", 999)).get(0).contains("不存在或已报废"));
    }

    @Test
    @DisplayName("部门引用：不存在 → 拒绝")
    void validateDepartmentRef() {
        FormField field = field(FormFieldType.BIZ_GROUP.name(), "group", "分组");

        Department group = new Department();
        group.setId(7L);
        when(departmentMapper.selectById(7L)).thenReturn(group);

        assertTrue(validator.validateAndCollect(schema(field), data("group", 7)).isEmpty());
        assertTrue(validator.validateAndCollect(schema(field), data("group", 8)).get(0).contains("不存在"));
    }

    // ------------------------------------------------------------------
    // validate()：错误汇总与截断
    // ------------------------------------------------------------------

    @Test
    @DisplayName("validate：有错抛 FORM_DATA_INVALID，且最多只列前 5 条（避免刷屏）")
    void validateThrowsWithLimitedDetail() {
        List<FormField> fields = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            FormField field = field(FormFieldType.TEXT.name(), "f" + i, "字段" + i);
            field.setRequired(true);
            fields.add(field);
        }
        FormSchema schema = new FormSchema();
        schema.setFields(fields);

        BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> validator.validate(schema, Map.of()));
        assertEquals(ErrorCode.FORM_DATA_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("另有 4 项未通过"));
    }

    @Test
    @DisplayName("validate：全部通过时不抛异常")
    void validatePassesWhenClean() {
        FormSchema schema = schema(field(FormFieldType.TEXT.name(), "reason", "事由"));
        validator.validate(schema, data("reason", "采购办公用品"));
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static User activeUser(Long id, boolean dimission, boolean enabled) {
        User user = new User();
        user.setId(id);
        user.setDimission(dimission);
        user.setEnabled(enabled);
        return user;
    }
}
