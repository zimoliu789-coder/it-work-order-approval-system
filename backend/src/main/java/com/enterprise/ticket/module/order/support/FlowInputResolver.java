package com.enterprise.ticket.module.order.support;

import com.enterprise.ticket.common.form.FormSchema;
import com.enterprise.ticket.common.form.FormSchemaCodec;
import com.enterprise.ticket.common.flow.BorrowFieldCatalog;
import com.enterprise.ticket.module.device.entity.Device;
import com.enterprise.ticket.module.device.mapper.DeviceMapper;
import com.enterprise.ticket.module.form.entity.FormTemplateVersion;
import com.enterprise.ticket.module.form.mapper.FormTemplateVersionMapper;
import com.enterprise.ticket.module.order.entity.Order;
import com.enterprise.ticket.module.order.entity.OrderFormData;
import com.enterprise.ticket.module.order.mapper.OrderFormDataMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 条件求值输入的重建器。
 *
 * <h2>为什么需要一个"重建"而不是把输入存起来</h2>
 * <p>运行期重算（{@code FlowActivationService#recompute}）必须用<b>与提交时完全相同</b>的条件输入
 * 重新求值 —— 否则"同上下文同结果"的幂等性就不成立，同一个条件在提交时判没命中、
 * 在重算时判命中，工单会走出前后矛盾的路径。
 *
 * <p>直接新增一列存下提交时的输入当然更省事，但那意味着：
 * ① 多一份可能与业务表不一致的冗余数据；② 存量工单没有这一列，得为空值兜底。
 * 而这份输入本来就是**可从已持久化的业务字段确定性推导**的
 * （借用单的四个内置字段全部落在 orders 上，自定义申请的表单数据落在 order_form_data），
 * 因此选择重建 —— 让"唯一的真相"仍在业务表里。
 *
 * <h2>一个必须小心的细节：expectedDays 的起算日</h2>
 * <p>提交时 {@code borrow.expectedDays} 是用<b>提交当天</b>换算的。若重建时又用
 * {@code LocalDate.now()}，同一笔工单在跨天之后重算会得到不同的天数 ——
 * 而条件「预计借用天数 &gt; 30」完全可能因此在午夜前后翻转。
 * 因此这里一律以 {@code order.createdAt} 的日期为起算日，
 * 从而**逐字复现提交时的取值**。
 */
@Component
public class FlowInputResolver {

    private final OrderFormDataMapper orderFormDataMapper;
    private final FormTemplateVersionMapper formTemplateVersionMapper;
    private final DeviceMapper deviceMapper;

    public FlowInputResolver(OrderFormDataMapper orderFormDataMapper,
                             FormTemplateVersionMapper formTemplateVersionMapper,
                             DeviceMapper deviceMapper) {
        this.orderFormDataMapper = orderFormDataMapper;
        this.formTemplateVersionMapper = formTemplateVersionMapper;
        this.deviceMapper = deviceMapper;
    }

    /**
     * 重建某工单的条件求值输入。
     *
     * @return 与提交时逐字一致的扁平 map；无法重建时返回空 map（求值器会自然判为"都不命中"，
     *         这是保守方向：宁可少走分支，不可错走）
     */
    public Map<String, Object> of(Order order) {
        if (order == null) {
            return new LinkedHashMap<>();
        }
        if ("CUSTOM".equals(order.getOrderType())) {
            return customFormData(order);
        }
        return borrowFormData(order);
    }

    /**
     * 重建字段 schema（仅用于把字段 key 渲染成中文名）。
     *
     * <p>取的是**这笔工单当初用的那一版**表单（{@code order_form_data.form_template_version_id}），
     * 而不是模板的当前版本：模板随时会改，若用当前版本，历史工单的说明文案会随模板一起漂移。
     */
    public FormSchema schemaOf(Order order) {
        if (order == null || !"CUSTOM".equals(order.getOrderType())) {
            return BorrowFieldCatalog.schema();
        }
        OrderFormData row = orderFormDataMapper.selectOne(Wrappers.<OrderFormData>lambdaQuery()
                .eq(OrderFormData::getOrderId, order.getId()));
        if (row == null || row.getFormTemplateVersionId() == null) {
            return FormSchema.empty();
        }
        FormTemplateVersion version = formTemplateVersionMapper.selectById(row.getFormTemplateVersionId());
        return version == null ? FormSchema.empty() : FormSchemaCodec.readSchema(version.getSchemaJson());
    }

    private Map<String, Object> customFormData(Order order) {
        OrderFormData row = orderFormDataMapper.selectOne(Wrappers.<OrderFormData>lambdaQuery()
                .eq(OrderFormData::getOrderId, order.getId()));
        if (row == null) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> data = FormSchemaCodec.readData(row.getFormDataJson());
        return data == null ? new LinkedHashMap<>() : data;
    }

    private Map<String, Object> borrowFormData(Order order) {
        Device device = order.getDeviceId() == null ? null : deviceMapper.selectById(order.getDeviceId());
        LocalDate submitDate = order.getCreatedAt() == null ? null : order.getCreatedAt().toLocalDate();
        Integer expectedDays = BorrowFieldCatalog.expectedDays(submitDate, order.getExpectedReturnDate());
        return BorrowFieldCatalog.formData(
                order.getUseType(),
                expectedDays,
                device == null ? null : device.getPrimaryCategoryId(),
                order.getReason(),
                // 设备金额：运行期重新求值（recompute）必须喂**同一份**输入，
                // 否则金额分档的条件在这里会判为"不成立"，把已经走到的「上级部门主管」
                // 静默降级为 SKIPPED —— 提交时算出来的四级路径会在运行期被改回三级。
                device == null ? null : device.getAmount());
    }
}
