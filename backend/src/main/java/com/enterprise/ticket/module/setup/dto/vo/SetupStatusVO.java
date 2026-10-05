package com.enterprise.ticket.module.setup.dto.vo;

import lombok.Data;

/**
 * 初始化状态（本次新增）。
 *
 * <p>只回一个布尔值：前端据此决定「是否把访问者引导到 /setup」。
 * 刻意不返回任何账号信息 —— 未初始化的系统对匿名访问者不应泄露更多。
 */
@Data
public class SetupStatusVO {

    /** 系统是否已完成初始化（= 库中是否已存在超级管理员） */
    private boolean initialized;
}
