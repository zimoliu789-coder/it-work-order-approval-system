package com.enterprise.ticket.module.system.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.Map;

/**
 * 系统参数批量保存请求（需求方三波·第一波·）
 *
 * <p>用 Map 而不是「列表 + 每个键一个字段」：参数会随时间增加（现在 14 个业务参数，
 * 将来还会加），每加一项就改一次 DTO 与前端表单是不必要的耦合。
 * Map 形式下新增参数只需在 {@code ConfigRules} 里补一条取值范围规则。
 */
@Data
public class ConfigUpdateRequest {

    /** 配置键 → 新值；允许只提交被改动的项（服务端会与库中值比对，未变的不写库） */
    @NotEmpty(message = "没有需要保存的参数")
    private Map<String, String> values;
}
