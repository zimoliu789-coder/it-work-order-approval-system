package com.enterprise.ticket.common.form;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 动态表单定义
 *
 * <p>对应 {@code form_template_version.schema_json} 的根对象：
 * <pre>
 * { "fields": [ { "key": "purchaseName", "label": "采购物品", "type": "TEXT", ... } ] }
 * </pre>
 *
 * <p><b>为什么根对象是「对象」而不是直接一个数组</b>：表单定义将来必然要长出与字段无关的
 * 顶层属性（如「提交后提示语」「是否允许重复提交」）。根是数组的话，这些属性无处安放，
 * 只能靠约定塞进某个特殊元素里 —— 那才是真正的技术债。根是对象时，加字段是纯增量。
 */
@Data
public class FormSchema {

    private List<FormField> fields = new ArrayList<>();

    /** 空 schema（无字段）—— 用于容错，避免调用方拿 null */
    public static FormSchema empty() {
        return new FormSchema();
    }

    public List<FormField> getFields() {
        return fields == null ? new ArrayList<>() : fields;
    }
}
