package com.enterprise.ticket.module.export.excel;

import com.enterprise.ticket.common.constant.DeviceStatus;
import com.enterprise.ticket.common.excel.ExcelExportWriter.SheetSpec;
import com.enterprise.ticket.module.device.dto.vo.DeviceVO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 设备台账导出
 *
 * <p>规范要求「含分类、资产编号、状态、报废标记」，因此列里必含：
 * 一级/二级分类、资产编号、设备状态、是否报废。
 *
 * <p><b>为什么单独列出「是否报废」而不是只给状态</b>： 中 {@code SCRAPPED} 既是
 * 状态也可能与其他状态语义重叠（例如先维修后报废），用一列布尔值让筛选/透视更直接。
 *
 * <p><b>设备金额追加在最后一列</b>：新增列一律追加在末尾，不做插入式排序 ——
 * 导出的 xlsx 常被下游脚本 / 数据透视按列序号消费，把新列插到中间会让那些消费者静默错位。
 *
 * <p>纯函数式构建：输入 {@code List<DeviceVO>} 输出 {@code SheetSpec}，不依赖 Spring、
 * 不碰数据库，因此可以单测「行顺序 / 报废列取值 / null 处理」。
 */
public final class DeviceExportExcel {

    public static final String SHEET_NAME = "设备台账";

    private static final String[] HEADERS = {
            "设备名称", "资产编号", "一级分类", "二级分类", "品牌", "型号",
            "序列号", "存放位置", "购置日期", "设备状态", "是否报废", "备注", "设备金额(元)"
    };

    /** 各列宽度（1/256 字符宽），与导入模板风格保持一致 */
    private static final int[] WIDTHS = {
            24, 18, 14, 14, 12, 18, 20, 20, 14, 12, 10, 28, 14
    };

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private DeviceExportExcel() {
    }

    public static SheetSpec build(List<DeviceVO> devices) {
        List<Object[]> rows = new ArrayList<>();
        for (DeviceVO device : devices == null ? List.<DeviceVO>of() : devices) {
            rows.add(new Object[]{
                    device.getDeviceName(),
                    device.getAssetNo(),
                    device.getPrimaryCategoryName(),
                    device.getSecondaryCategoryName(),
                    device.getBrand(),
                    device.getModel(),
                    device.getSerialNo(),
                    device.getStorageLocation(),
                    formatDate(device.getPurchaseDate()),
                    // 状态中文名优先用 VO 已装配好的 label，兜底由枚举现算 —— 保证与列表页文案一致
                    device.getStatusLabel() != null ? device.getStatusLabel() : DeviceStatus.labelOf(device.getStatus()),
                    scrapLabel(device.getStatus()),
                    device.getRemark(),
                    formatAmount(device.getAmount())
            });
        }
        return new SheetSpec(SHEET_NAME, HEADERS, WIDTHS, rows);
    }

    /** 报废标记：状态为 SCRAPPED 时为「是」，其余为「否」（不用空值，便于 Excel 直接筛选） */
    public static String scrapLabel(String status) {
        return DeviceStatus.SCRAPPED.name().equals(status) ? "是" : "否";
    }

    private static String formatDate(LocalDate date) {
        return date == null ? "" : DATE.format(date);
    }

    /**
     * 金额 → 文本：未录入（null）导出为**空单元格**，而不是 {@code 0}。
     *
     * <p>「还没录金额」与「金额是 0」是两件不同的事，导出里必须能分辨 ——
     * 审批分档也依赖这一点。用 {@code toPlainString()} 而非 {@code toString()}：
     * 后者对极小/极大值会给出科学计数法（{@code 1E+3}），在台账里读起来像乱码。
     */
    private static String formatAmount(BigDecimal amount) {
        return amount == null ? "" : amount.stripTrailingZeros().toPlainString();
    }
}
