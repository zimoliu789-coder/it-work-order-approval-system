package com.enterprise.ticket.common.form;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 动态表单 JSON 编解码
 *
 * <h2>为什么单独一个类，而不是在各 Service 里各自 new ObjectMapper</h2>
 * <p>schema 与 form_data 是<b>长期存档</b>的数据：写进去的 JSON 决定了几周后工单详情
 * 能不能正确回显。若各处用不同配置的 mapper（有的开 FAIL_ON_UNKNOWN_PROPERTIES、
 * 有的不同），升级一次依赖就可能出现「同一条数据有的地方读得出来、有的地方报错」。
 * 这里收敛成唯一入口，配置只在此处定义。
 *
 * <h2>解析失败一律转成业务错误码</h2>
 * <p>存档 JSON 损坏（人工改库、迁移出错）时，调用方需要的是
 * 「这条数据的格式不合法」这个可读结论，而不是 Jackson 的堆栈。
 * 因此 {@link #readSchema} / {@link #readData} 把异常统一翻译成
 * {@link ErrorCode#FORM_SCHEMA_INVALID} / {@link ErrorCode#FORM_DATA_INVALID}。
 *
 * <p><b>刻意忽略未知属性</b>：将来给字段加配置项（如新类型的专属属性）时，
 * 旧数据里没有该属性、新数据里多出该属性都属正常，不该因此报错。
 */
public final class FormSchemaCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            // 未知属性忽略而非报错：schema 会随版本演进，向后兼容优先
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private FormSchemaCodec() {
    }

    /** 对象 → JSON 文本（写库用） */
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "表单数据序列化失败");
        }
    }

    /** JSON 文本 → 表单定义（读取存档用） */
    public static FormSchema readSchema(String json) {
        if (json == null || json.isBlank()) {
            return FormSchema.empty();
        }
        try {
            FormSchema schema = MAPPER.readValue(json, FormSchema.class);
            return schema == null ? FormSchema.empty() : schema;
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.FORM_SCHEMA_INVALID, "表单定义格式不合法，无法解析");
        }
    }

    /**
     * JSON 文本 → 字符串数组（申请类型的提交权限值用）。
     *
     * <p>解析失败<b>不抛异常</b>而是返回空列表：提交权限值损坏时，正确的降级行为是
     * 「当作没有人被授权」（宁可少放行，不可错放行），而不是让整个申请类型列表打不开。
     * 这一点与 schema / form_data 的处理刻意相反 —— 那两者的损坏必须显式报错，
     * 因为「用一份错的表单去校验用户输入」比「拒绝服务」更糟。
     */
    public static List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = MAPPER.readValue(json,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
            return values == null ? List.of() : values;
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /**
     * JSON 文本 → 表单数据（键 → 值）。
     *
     * <p>用 {@link LinkedHashMap} 保留原始顺序：详情页回显时字段顺序与提交顺序一致，
     * 用户对照时不会有「明明是按顺序填的，展示出来却乱序」的困惑。
     */
    public static Map<String, Object> readData(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> data = MAPPER.readValue(json,
                    MAPPER.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
            return data == null ? new LinkedHashMap<>() : data;
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.FORM_DATA_INVALID, "表单数据格式不合法，无法解析");
        }
    }
}
