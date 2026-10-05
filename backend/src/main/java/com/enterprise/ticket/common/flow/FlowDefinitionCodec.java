package com.enterprise.ticket.common.flow;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 流程定义 JSON 编解码。
 *
 * <p>与一期 {@code FormSchemaCodec} 同构：用同一个 {@link ObjectMapper} 配置
 * （忽略未知属性，向后兼容优先）——流程定义会随版本演进，旧存档不该因为多了一个字段就读不出来。
 */
public final class FlowDefinitionCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private FlowDefinitionCodec() {
    }

    /** 对象 → JSON 文本（写库用） */
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "流程定义序列化失败");
        }
    }

    /**
     * JSON 文本 → 流程定义。
     *
     * <p>解析失败抛 {@link ErrorCode#FLOW_DEFINITION_INVALID} 而不是 INTERNAL_ERROR：
     * 这类失败几乎总是**存量配置本身有问题**（人工改库、迁移遗留），
     * 归到"定义不合法"能让排查直接指向配置，而不是误以为服务端崩了。
     */
    public static FlowDefinition read(String json) {
        if (json == null || json.isBlank()) {
            throw new BusinessException(ErrorCode.FLOW_DEFINITION_INVALID, "流程定义为空");
        }
        try {
            FlowDefinition definition = MAPPER.readValue(json, FlowDefinition.class);
            if (definition == null) {
                throw new BusinessException(ErrorCode.FLOW_DEFINITION_INVALID, "流程定义为空");
            }
            return definition;
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.FLOW_DEFINITION_INVALID,
                    "流程定义格式不合法，无法解析：" + e.getOriginalMessage());
        }
    }
}
