package com.enterprise.ticket.module.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统配置表（ LDAP 参数、 定时任务可配置参数、 限流参数）
 */
@Data
@TableName("system_config")
public class SystemConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String configKey;

    private String configValue;

    /** 配置分组：lock / approval / borrow / security / log / ldap */
    private String configGroup;

    private String configDesc;

    /** 是否允许在管理界面修改 */
    private Boolean editable;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
