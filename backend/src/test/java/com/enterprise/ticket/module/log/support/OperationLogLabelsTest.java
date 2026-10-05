package com.enterprise.ticket.module.log.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 操作日志中文化单元测试（需求四）
 *
 * <p>锁定三件事：
 * <ul>
 *   <li>模块 / 动作编码到中文的映射（已知编码命中、未知编码原样返回不丢信息）；</li>
 *   <li>「详情」列默认摘要的人类可读性（@AuditLog / 批量导入 / 认证三类详情形态各得其所）；</li>
 *   <li>失败摘要包含失败原因、但<b>绝不含堆栈</b>。</li>
 * </ul>
 */
class OperationLogLabelsTest {

    @Test
    @DisplayName("模块：已知编码命中中文，未知编码原样返回（不丢信息）")
    void moduleLabel() {
        assertEquals("员工管理", OperationLogLabels.moduleLabel("USER"));
        assertEquals("工单管理", OperationLogLabels.moduleLabel("ORDER"));
        assertEquals("设备管理", OperationLogLabels.moduleLabel("DEVICE"));
        assertEquals("认证登录", OperationLogLabels.moduleLabel("AUTH"));
        assertEquals("UNKNOWN_MODULE", OperationLogLabels.moduleLabel("UNKNOWN_MODULE"));
    }

    @Test
    @DisplayName("动作：已知编码命中中文，未知编码原样返回")
    void actionLabel() {
        assertEquals("新增员工", OperationLogLabels.actionLabel("USER_CREATE"));
        assertEquals("超管强制干预", OperationLogLabels.actionLabel("ORDER_FORCE_OPERATION"));
        assertEquals("批量导入设备", OperationLogLabels.actionLabel("DEVICE_IMPORT"));
        assertEquals("SOME_NEW_ACTION", OperationLogLabels.actionLabel("SOME_NEW_ACTION"));
    }

    @Test
    @DisplayName("摘要：@AuditLog 详情取 description 段")
    void summary_fromAuditLogDesc() {
        String details = "desc=新增设备台账 | method=DeviceController#create | args=[] | cost=12ms";
        assertEquals("新增设备台账", OperationLogLabels.summary("DEVICE_CREATE", details, "SUCCESS"));
    }

    @Test
    @DisplayName("摘要：失败时追加失败原因（取自 error=，不含堆栈）")
    void summary_failureAppendsReason() {
        String details = "desc=审批借用工单 | method=OrderController#approve | args=[] | cost=5ms | error=设备型号不符";
        String summary = OperationLogLabels.summary("ORDER_APPROVE", details, "FAILED");
        assertEquals("审批借用工单（失败：设备型号不符）", summary);
        assertTrue(!summary.contains("Exception") && !summary.contains("\tat "), "摘要不得包含堆栈");
    }

    @Test
    @DisplayName("摘要：批量导入详情转成「成功 N 条 / 失败 M 条」")
    void summary_fromImportDetails() {
        String details = "file=设备导入.xlsx | total=3 | imported=2 | failed=1";
        assertTrue(OperationLogLabels.summary("DEVICE_IMPORT", details, "SUCCESS").contains("成功 2 条，失败 1 条"));
    }

    @Test
    @DisplayName("摘要：认证模块纯文本详情去掉 ip= / role= 技术片段")
    void summary_fromAuthPlainText() {
        assertEquals("登录成功",
                OperationLogLabels.summary("LOGIN", "登录成功，role=user，ip=10.0.0.8", "SUCCESS"));
        assertEquals("密码校验失败，累计失败 2/5 次",
                OperationLogLabels.summary("LOGIN_FAILED", "密码校验失败，累计失败 2/5 次，ip=10.0.0.8", "FAILED"));
    }

    @Test
    @DisplayName("摘要：详情为空时兜底为动作中文名")
    void summary_blankFallsBackToActionLabel() {
        assertEquals("提交借用申请", OperationLogLabels.summary("ORDER_CREATE", null, "SUCCESS"));
        assertEquals("提交借用申请", OperationLogLabels.summary("ORDER_CREATE", "", "SUCCESS"));
    }

    @Test
    @DisplayName("结果 / 风险标签")
    void resultAndRiskLabels() {
        assertEquals("成功", OperationLogLabels.resultLabel("SUCCESS"));
        assertEquals("失败", OperationLogLabels.resultLabel("FAILED"));
        assertEquals("高风险", OperationLogLabels.riskLabel("HIGH"));
        assertEquals("普通", OperationLogLabels.riskLabel("NORMAL"));
    }

    @Test
    @DisplayName("模块编码集合可枚举（供筛选下拉）")
    void moduleCodes() {
        assertTrue(OperationLogLabels.moduleCodes().contains("AUTH"));
        assertTrue(OperationLogLabels.moduleCodes().contains("ORDER"));
    }
}
