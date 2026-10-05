package com.enterprise.ticket.module.export.excel;

import com.enterprise.ticket.common.excel.ExcelExportWriter.SheetSpec;
import com.enterprise.ticket.module.usage.dto.vo.UsageRecordVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用记录导出单测（规范 §35）
 *
 * <p>覆盖三处「不报错但会导出错内容」的规则：
 * <ol>
 *   <li><b>列与列表对齐</b>——表头顺序就是用户看到的列顺序，改错一列整份文件都会错位；</li>
 *   <li><b>长期领用占位</b>——计划归还为空必须写「长期领用」而不是空白，
 *       否则审计时无法区分「长期领用」与「数据缺失」；</li>
 *   <li><b>顺延 / 超时</b>——顺延 null 要写成 0（而不是空），超时写成「是 / 否」便于筛选。</li>
 * </ol>
 */
class UsageExportExcelTest {

    private UsageRecordVO row() {
        UsageRecordVO vo = new UsageRecordVO();
        vo.setOrderNo("BO20260920001");
        vo.setDeviceName("ThinkPad X1");
        vo.setAssetNo("ZC-0001");
        vo.setApplicantName("张三");
        vo.setDepartmentName("研发一组");
        vo.setUseType("SHORT_TERM");
        vo.setUseTypeLabel("短期借用");
        vo.setStatus("BORROWED");
        vo.setStatusLabel("使用中");
        vo.setCreatedAt(LocalDateTime.of(2026, 9, 20, 9, 30, 0));
        vo.setPlannedEndTime(LocalDateTime.of(2026, 9, 30, 18, 0, 0));
        vo.setActualEndTime(null);
        vo.setAutoExtendCount(2);
        vo.setBorrowTimeout(true);
        return vo;
    }

    @Test
    @DisplayName("表头与「使用记录」列表逐列对齐（列数、顺序固定）")
    void headers_matchListColumns() {
        SheetSpec spec = UsageExportExcel.build(List.of(row()));

        assertEquals("使用记录", spec.name());
        assertEquals(
                List.of("工单号", "设备", "资产编号", "借用人", "部门", "借用类型",
                        "状态", "提交时间", "计划归还", "实际归还", "顺延次数", "是否超时"),
                List.of(spec.headers()));
        assertEquals(spec.headers().length, spec.widths().length, "每列都应有列宽，避免打开时列被压扁");
    }

    @Test
    @DisplayName("一行按列顺序正确映射，时间格式化为 yyyy-MM-dd HH:mm:ss")
    void row_mapsInColumnOrder() {
        SheetSpec spec = UsageExportExcel.build(List.of(row()));
        Object[] cells = spec.rows().get(0);

        assertEquals("BO20260920001", cells[0]);
        assertEquals("ThinkPad X1", cells[1]);
        assertEquals("ZC-0001", cells[2]);
        assertEquals("张三", cells[3]);
        assertEquals("研发一组", cells[4]);
        assertEquals("短期借用", cells[5]);
        assertEquals("使用中", cells[6]);
        assertEquals("2026-09-20 09:30:00", cells[7]);
        assertEquals("2026-09-30 18:00:00", cells[8]);
        assertEquals("", cells[9], "未归还的实际归还时间为空串，而不是 null");
        assertEquals(2, cells[10]);
        assertEquals("是", cells[11]);
    }

    @Test
    @DisplayName("计划归还为空 → 写「长期领用」（不是空白，避免与数据缺失混淆）")
    void plannedEndNull_writesLongTerm() {
        UsageRecordVO vo = row();
        vo.setPlannedEndTime(null);

        Object[] cells = UsageExportExcel.build(List.of(vo)).rows().get(0);

        assertEquals("长期领用", cells[8]);
    }

    @Test
    @DisplayName("未超时写「否」、顺延为空写 0（数值列不留空）")
    void timeoutAndExtendCount_defaults() {
        UsageRecordVO vo = row();
        vo.setBorrowTimeout(null);
        vo.setAutoExtendCount(null);

        Object[] cells = UsageExportExcel.build(List.of(vo)).rows().get(0);

        assertEquals("否", cells[11]);
        assertEquals(0, cells[10]);
    }

    @Test
    @DisplayName("未装配中文 label 时按枚举兜底（不导出英文码）")
    void labelsFallbackToEnum() {
        UsageRecordVO vo = row();
        vo.setUseTypeLabel(null);
        vo.setStatusLabel(null);

        Object[] cells = UsageExportExcel.build(List.of(vo)).rows().get(0);

        assertEquals("短期借用", cells[5]);
        assertEquals("使用中", cells[6]);
    }

    @Test
    @DisplayName("空数据 / null 输入产出只有表头的空表，不抛错")
    void emptyInput_isSafe() {
        assertEquals(0, UsageExportExcel.build(List.of()).rows().size());
        assertEquals(0, UsageExportExcel.build(null).rows().size());
        assertNotNull(UsageExportExcel.build(null).headers());
        assertTrue(UsageExportExcel.build(null).headers().length > 0);
    }
}
