package com.enterprise.ticket.common.form;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 系统字段（{@code systemField}）与自定义字段的切分。
 *
 * <h2>为什么需要「只取自定义字段」</h2>
 * 内置类型（借用 / 报修）的表单里混着两类字段：
 * <ul>
 *   <li><b>系统字段</b>：值由提交页的专属控件填写，走 {@code OrderCreateRequest} /
 *       {@code DeviceFaultRequest} 的专用入参（一级分类的三级联动、扫码查设备，
 *       这些交互不是通用渲染器能表达的）；</li>
 *   <li><b>自定义字段</b>：管理员在设计器里加的，值随表单数据落
 *       {@code order_form_data} / {@code device_fault.form_data_json}。</li>
 * </ul>
 *
 * <p>两者在 schema 里同处一个 {@code fields} 数组，于是有两处必须切分：
 * <ul>
 *   <li><b>提交页</b>：只把自定义字段交给通用渲染器，系统字段继续用专属控件；</li>
 *   <li><b>详情回显</b>：只渲染自定义字段 —— 系统字段的信息已经在工单主信息区
 *       （类别 / 型号 / 归还日期 / 故障设备），再按 schema 画一遍只会得到一排「—」。</li>
 * </ul>
 *
 * <p>切分只认 {@code systemField == TRUE}，不认 key 清单：key 清单会随内置类型演进漂移，
 * 而标记由目录在播种时写入、并由发布校验强制保留。
 *
 * <p>布局字段（{@code NONE}，分区标题 / 说明文字）**保留**：
 * 它们不是系统字段，且删掉会让自定义字段区的分组文案消失。
 */
public final class SystemFields {

    private SystemFields() {
    }

    /** 取自定义字段（保留布局字段，保持原顺序） */
    public static List<FormField> extraFieldsOf(FormSchema schema) {
        if (schema == null) {
            return List.of();
        }
        return schema.getFields().stream()
                .filter(Objects::nonNull)
                .filter(field -> !Boolean.TRUE.equals(field.getSystemField()))
                .toList();
    }

    /**
     * 只含自定义字段的 schema 副本。
     *
     * <p>返回副本而不是就地删字段：入参是<b>已发布版本的快照对象</b>
     * （{@code formTemplateService#requirePublishedSchema} 的返回值可能被缓存复用），
     * 就地修改会让同一版本在别处的读取少掉字段。
     */
    public static FormSchema extraSchemaOf(FormSchema schema) {
        if (schema == null) {
            return null;
        }
        FormSchema copy = new FormSchema();
        copy.setFields(new ArrayList<>(extraFieldsOf(schema)));
        return copy;
    }
}
