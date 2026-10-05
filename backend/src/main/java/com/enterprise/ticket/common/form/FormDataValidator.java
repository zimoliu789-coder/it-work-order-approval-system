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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 动态表单<b>数据</b>校验
 *
 * <h2>为什么校验逻辑必须与 schema 同源</h2>
 * <p>设计器里配了什么（必填、范围、正则、选项），提交时就必须按同样的规则校验。
 * 若前端自己实现一套、后端再实现一套，两者只要有一处不一致，就会出现
 * 「前端放过去了、后端拒绝」或更糟的「后端放过去了、脏数据入库」。
 * 因此<b>前端只做即时提示，规则由服务端这里统一裁决</b>。
 *
 * <h2>字段级错误</h2>
 * <p>{@link #validateAndCollect} 返回的是「哪个字段、错在哪」的成对信息，
 * 而不是一个笼统的「参数不合法」。自定义表单有几十个字段，
 * 只说「表单数据不合法」等于让用户自己逐格猜。
 *
 * <h2>引用类字段要查库</h2>
 * <p>{@link FormFieldType.ValueKind#USER_REF} / {@code DEVICE_REF} / {@code GROUP_REF}
 * 存的只是 id。若不校验存在性，任何客户端都能提交 {@code "approver": 999999} 这种
 * 指向不存在实体的数据 —— 而这类脏数据在详情页回显时会变成空白或报错，
 * 排查时又找不到来源。故此处逐条查库确认（表单字段数量有限，不构成性能问题）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FormDataValidator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UserMapper userMapper;
    private final DeviceMapper deviceMapper;
    private final DepartmentMapper departmentMapper;

    /**
     * 校验表单数据，失败即抛 {@link ErrorCode#FORM_DATA_INVALID}（message 含字段级明细）。
     */
    public void validate(FormSchema schema, Map<String, Object> data) {
        List<String> errors = validateAndCollect(schema, data);
        if (!errors.isEmpty()) {
            // 只取前 5 条拼接：一次提交可能有几十个字段没填，全量返回会让错误提示淹没界面，
            // 而用户修完第一批后再次提交自然会看到剩下的 —— 分批暴露比一次性刷屏更有用。
            List<String> shown = errors.size() > 5 ? errors.subList(0, 5) : errors;
            String suffix = errors.size() > 5 ? "（另有 " + (errors.size() - 5) + " 项未通过，请逐项检查）" : "";
            throw new BusinessException(ErrorCode.FORM_DATA_INVALID,
                    "表单校验未通过：" + String.join("；", shown) + suffix);
        }
    }

    /**
     * 收集字段级错误（不抛异常，便于单测直接断言明细）。
     *
     * @param schema 表单定义（发布时已通过 {@link FormSchemaValidator} 校验）
     * @param data   用户提交的原始数据（key → 值）
     * @return 错误描述列表；空表示全部通过
     */
    public List<String> validateAndCollect(FormSchema schema, Map<String, Object> data) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> safeData = data == null ? Map.of() : data;
        List<FormField> dataFields = schema.getFields().stream()
                .filter(Objects::nonNull)
                .filter(field -> {
                    FormFieldType type = FormFieldType.of(field.getType());
                    return type != null && type.isDataField();
                })
                .toList();

        // 未定义的字段：直接拒绝而不是静默丢弃。
        // 静默丢弃意味着「用户填了、系统没存」，而用户在详情页看不到自己填的内容时，
        // 只会认为系统丢数据 —— 与其无声无息，不如明确告诉他该字段不属于本表单。
        for (String key : safeData.keySet()) {
            boolean defined = dataFields.stream().anyMatch(field -> Objects.equals(field.getKey(), key));
            if (!defined) {
                errors.add("存在表单未定义的字段「" + key + "」");
            }
        }

        for (FormField field : dataFields) {
            validateField(field, safeData.get(field.getKey()), errors);
        }
        return errors;
    }

    // ------------------------------------------------------------------
    // 单字段校验
    // ------------------------------------------------------------------

    private void validateField(FormField field, Object value, List<String> errors) {
        FormFieldType type = FormFieldType.of(field.getType());
        String label = field.getLabel() == null ? field.getKey() : field.getLabel();
        boolean required = Boolean.TRUE.equals(field.getRequired());

        if (isEmpty(value)) {
            if (required) {
                errors.add("「" + label + "」为必填项");
            }
            return;
        }

        switch (type.getValueKind()) {
            case TEXT -> validateText(field, label, stringValue(value), errors);
            case NUMBER -> validateNumber(field, label, value, errors);
            case DATE -> validateDate(field, label, stringValue(value), errors);
            case DATETIME -> validateDateTime(field, label, stringValue(value), errors);
            case OPTION -> validateOption(field, label, stringValue(value), errors);
            case OPTION_MULTI -> validateOptionMulti(field, label, value, errors);
            case FILE -> validateFiles(field, label, value, errors);
            case USER_REF -> validateUserRef(field, label, value, errors);
            case DEVICE_REF -> validateDeviceRef(field, label, value, errors);
            case GROUP_REF -> validateGroupRef(field, label, value, errors);
            default -> {
                // NONE（布局类）已在上游过滤，不会走到这里
            }
        }
    }

    private void validateText(FormField field, String label, String text, List<String> errors) {
        Integer min = field.getMinLength();
        Integer max = field.getMaxLength();
        if (min != null && text.length() < min) {
            errors.add("「" + label + "」长度不能少于 " + min + " 个字符");
        }
        if (max != null && text.length() > max) {
            errors.add("「" + label + "」长度不能超过 " + max + " 个字符");
        }
        String pattern = field.getPattern();
        if (pattern != null && !pattern.isBlank() && !Pattern.matches(pattern, text)) {
            errors.add("「" + label + "」格式不符合要求");
        }
    }

    private void validateNumber(FormField field, String label, Object value, List<String> errors) {
        Double number = doubleValue(value);
        if (number == null) {
            errors.add("「" + label + "」必须是数字");
            return;
        }
        if (field.getMin() != null && number < field.getMin()) {
            errors.add("「" + label + "」不能小于 " + trimNumber(field.getMin()));
        }
        if (field.getMax() != null && number > field.getMax()) {
            errors.add("「" + label + "」不能大于 " + trimNumber(field.getMax()));
        }
        Integer precision = field.getPrecision();
        if (precision != null && precision >= 0 && decimalPlaces(value) > precision) {
            errors.add("「" + label + "」小数位数不能超过 " + precision + " 位");
        }
    }

    private void validateDate(FormField field, String label, String text, List<String> errors) {
        LocalDate date = parseDate(text);
        if (date == null) {
            errors.add("「" + label + "」日期格式不合法（应为 yyyy-MM-dd）");
            return;
        }
        applyDateLimit(field, label, date, errors);
    }

    private void validateDateTime(FormField field, String label, String text, List<String> errors) {
        LocalDateTime dateTime = parseDateTime(text);
        if (dateTime == null) {
            errors.add("「" + label + "」日期时间格式不合法（应为 yyyy-MM-dd HH:mm:ss）");
            return;
        }
        applyDateLimit(field, label, dateTime.toLocalDate(), errors);
    }

    private void applyDateLimit(FormField field, String label, LocalDate date, List<String> errors) {
        String limit = field.getDateLimit();
        LocalDate today = LocalDate.now();
        if ("NOT_BEFORE_TODAY".equals(limit) && date.isBefore(today)) {
            errors.add("「" + label + "」不能早于今天");
        } else if ("NOT_AFTER_TODAY".equals(limit) && date.isAfter(today)) {
            errors.add("「" + label + "」不能晚于今天");
        }
        if ("CUSTOM".equals(limit)) {
            LocalDate min = parseDate(field.getDateMin());
            LocalDate max = parseDate(field.getDateMax());
            if (min != null && date.isBefore(min)) {
                errors.add("「" + label + "」不能早于 " + field.getDateMin());
            }
            if (max != null && date.isAfter(max)) {
                errors.add("「" + label + "」不能晚于 " + field.getDateMax());
            }
        }
    }

    private void validateOption(FormField field, String label, String value, List<String> errors) {
        if (!optionValues(field).contains(value)) {
            errors.add("「" + label + "」的取值不在可选范围内");
        }
    }

    private void validateOptionMulti(FormField field, String label, Object value, List<String> errors) {
        List<String> values = stringList(value);
        if (values == null) {
            errors.add("「" + label + "」必须是数组");
            return;
        }
        List<String> allowed = optionValues(field);
        for (String item : values) {
            if (!allowed.contains(item)) {
                errors.add("「" + label + "」的取值「" + item + "」不在可选范围内");
                return;
            }
        }
    }

    private void validateFiles(FormField field, String label, Object value, List<String> errors) {
        List<String> ids = stringList(value);
        if (ids == null) {
            errors.add("「" + label + "」必须是附件 id 数组");
            return;
        }
        Integer maxCount = field.getMaxCount();
        if (maxCount != null && ids.size() > maxCount) {
            errors.add("「" + label + "」最多上传 " + maxCount + " 个附件");
        }
        for (String id : ids) {
            if (longValue(id) == null) {
                errors.add("「" + label + "」包含非法的附件 id：" + id);
                return;
            }
        }
    }

    private void validateUserRef(FormField field, String label, Object value, List<String> errors) {
        Long userId = longValue(value);
        if (userId == null) {
            errors.add("「" + label + "」必须是人员 id");
            return;
        }
        User user = userMapper.selectById(userId);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled()) || Boolean.TRUE.equals(user.getDimission())) {
            errors.add("「" + label + "」所选人员不存在或已离职禁用");
        }
    }

    private void validateDeviceRef(FormField field, String label, Object value, List<String> errors) {
        Long deviceId = longValue(value);
        if (deviceId == null) {
            errors.add("「" + label + "」必须是设备 id");
            return;
        }
        Device device = deviceMapper.selectById(deviceId);
        if (device == null || DeviceStatus.of(device.getStatus()) == DeviceStatus.SCRAPPED) {
            errors.add("「" + label + "」所选设备不存在或已报废");
        }
    }

    private void validateGroupRef(FormField field, String label, Object value, List<String> errors) {
        Long groupId = longValue(value);
        if (groupId == null) {
            errors.add("「" + label + "」必须是部门 id");
            return;
        }
        Department group = departmentMapper.selectById(groupId);
        if (group == null) {
            errors.add("「" + label + "」所选部门不存在");
        }
    }

    // ------------------------------------------------------------------
    // 取值转换
    // ------------------------------------------------------------------

    private List<String> optionValues(FormField field) {
        if (field.getOptions() == null) {
            return List.of();
        }
        return field.getOptions().stream()
                .filter(Objects::nonNull)
                .map(FormOption::getValue)
                .toList();
    }

    private static boolean isEmpty(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return text.isBlank();
        }
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        return false;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (Object item : collection) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private static Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.valueOf(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.valueOf(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static int decimalPlaces(Object value) {
        String text = value instanceof Number number
                ? new java.math.BigDecimal(number.toString()).stripTrailingZeros().toPlainString()
                : String.valueOf(value);
        int dot = text.indexOf('.');
        return dot < 0 ? 0 : text.length() - dot - 1;
    }

    private static String trimNumber(Double value) {
        if (value == null) {
            return "";
        }
        return value == Math.floor(value) ? String.valueOf(value.longValue()) : String.valueOf(value);
    }

    private static LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim(), DATE_FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDateTime parseDateTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text.trim(), DATETIME_FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
