package com.enterprise.ticket.common.excel;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Excel 批量导入的通用解析支持（ 设备导入 +  员工导入共用）
 *
 * <p>本类只做「Excel ↔ 行数据」的<b>无状态</b>转换与<b>解析安全防护</b>，
 * 不承担任何业务校验：必填、唯一性、分组/分类归属、角色枚举等规则一律由各业务
 * Service 层复用「单条新增」的同一套规则判定，避免出现「Excel 侧一套规则、单条新增另一套」。
 * 「无状态」是硬约束 —— 子类是单例 Bean，任何可变成员都会被并发导入共享。
 *
 * <p><b>为什么抽成基类</b>：设备导入与员工导入的「解析安全三道防线 + 单元格取值 +
 * 模板/失败明细样式」完全同构，各写一份必然漂移（例：只给其中一处补上压缩炸弹防护）。
 * 差异只在「列定义」与「业务校验」，因此列定义由 {@link #headers()} / {@link #columnWidths()}
 * 提供，业务校验留在各自 Service 层。
 *
 * <p><b>列采用「按位置解析」</b>而非按表头名匹配：用户常见操作是修改表头文案
 * （加星号、改错别字），按名匹配会让整列静默丢失；按位置解析只要求「列顺序不变」，
 * 更符合「下载模板 → 填数据 → 上传」的实际用法。多出来的列（例如失败明细里的
 * 「失败原因」列）会被自然忽略，因此失败明细改完可直接重新导入。
 *
 * <h2>解析安全（三道防线）</h2>
 * <ol>
 *   <li><b>条目解压体积预检</b>（{@link #assertExpandedSize(InputStream)}）：xlsx 是 zip 容器，
 *       几 MB 的上传可解压出上百 MB 的 XML，而 POI 是「整表读入堆」的实现 —— 若不设限，
 *       {@code maxRows} 这类业务规则要等 POI 解析完才生效，攻击者可用「大量行 / 高压缩比」
 *       文件把 JVM 撑爆。故在交给 POI 之前先按 zip 条目声明的解压体积做一次快速拒绝；</li>
 *   <li><b>POI 全局解压上限</b>（类初始化时设置 {@link ZipSecureFile}）：兜住「声明体积造假」
 *       的文件 —— 声明小、实际膨胀大的条目会在 POI 内部被拒绝；</li>
 *   <li><b>物理行数硬顶</b>（{@link #MAX_PHYSICAL_ROWS}）：业务上限之外再加一道资源护栏，
 *       避免先构造出几十万个行对象才拒绝。</li>
 * </ol>
 *
 * <p><b>XXE</b>：POI 5.x 默认禁用 DOCTYPE（{@code SAXHelper} 设置
 * {@code disallow-doctype-decl=true}），故内嵌外部实体 / 外部 DTD 的 xlsx 会解析失败，
 * 不会被读取本地文件或发起外网请求。此处不额外放开任何 XML 解析开关。
 *
 * <p><b>公式注入</b>：写出（模板 / 失败明细）一律用 {@code setCellValue(String)}，
 * 生成的是字符串单元格；只有 {@code setCellFormula} 才会产生 {@code <f>} 节点。
 * 因此用户数据里以 {@code = + - @} 开头的内容在 Excel 中只按文本显示，不会被求值。
 * 修改写出行时务必保持这一点。
 */
public abstract class ExcelImportSupport {

    /** 示例行标记：含此标记的行视为模板示例，解析时自动跳过 */
    public static final String EXAMPLE_MARKER = "导入前删除";

    /**
     * 单个 zip 条目解压后允许的最大体积。
     *
     * <p>业务上限是 500 数据行，其工作表 XML 解压后约几百 KB；取 8MB 有 ~20 倍余量，
     * 既能容纳「行数超标但格式合法」的文件（好让用户收到「最多 500 行」这个更准确的提示），
     * 又能阻挡解压炸弹。
     */
    protected static final long MAX_ENTRY_BYTES = 8L * 1024 * 1024;

    /** 全部条目解压后的总体积上限（xlsx 的关键部件：工作表 + 共享字符串 + 样式） */
    protected static final long MAX_TOTAL_BYTES = 24L * 1024 * 1024;

    /** zip 条目数上限（正常 xlsx 的条目数在几十个量级，2000 有充足余量） */
    protected static final long MAX_ZIP_ENTRIES = 2000L;

    /**
     * 物理行数硬顶（含表头、空白行、示例行）。
     *
     * <p>与业务上限 {@code maxRows}（500 数据行）是两回事：这里挡的是「文件本身就不正常」
     * 的情况，阈值放得比业务上限宽，避免把「540 行里有 40 行空白」这类合法文件误判。
     */
    protected static final int MAX_PHYSICAL_ROWS = 5000;

    protected static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    static {
        // POI 的解压防护是**全局静态**配置（进程内一次性生效）。默认 MAX_ENTRY_SIZE 约 4GB
        // 形同不设限，这里收紧到与业务余量匹配的值，作为「声明体积造假」文件的后手。
        // MAX_FILE_COUNT 另防「大量小条目」型压缩包：正常 xlsx 的条目数在几十个量级。
        ZipSecureFile.setMinInflateRatio(0.01d);
        ZipSecureFile.setMaxEntrySize(MAX_ENTRY_BYTES);
        ZipSecureFile.setMaxTextSize(MAX_TOTAL_BYTES);
        ZipSecureFile.setMaxFileCount(MAX_ZIP_ENTRIES);
    }

    // ------------------------------------------------------------------
    // 子类提供的列定义与错误码
    // ------------------------------------------------------------------

    /** 模板/明细列顺序（解析、生成模板、失败明细共用同一份定义） */
    protected abstract String[] headers();

    /** 各列建议宽度（单位：1/256 个字符宽），长度须与 {@link #headers()} 一致 */
    protected abstract int[] columnWidths();

    /** 列数（= 表头数） */
    protected int columnCount() {
        return headers().length;
    }

    /**
     * 体积超限错误码。
     *
     * <p>各模块用<b>自己的</b>错误码（前端据此区分是设备导入还是员工导入），文案语义一致。
     * 之所以用可覆写方法而非构造参数：两个子类都需要无参构造（Spring 单例 Bean + 单测直接 new）。
     */
    protected ErrorCode fileTooLargeCode() {
        return ErrorCode.DEVICE_IMPORT_FILE_TOO_LARGE;
    }

    /** 体积预检阶段（zip 结构损坏）的错误码，默认与设备导入一致 */
    protected ErrorCode precheckParseFailedCode() {
        return ErrorCode.DEVICE_IMPORT_PARSE_FAILED;
    }

    /** 解析阶段（内容损坏 / 伪装文件 / 公式无缓存结果）的错误码，默认与设备导入一致 */
    protected ErrorCode parseFailedCode() {
        return ErrorCode.DEVICE_IMPORT_PARSE_FAILED;
    }

    // ------------------------------------------------------------------
    // 模板 / 明细的通用样式与表头
    // ------------------------------------------------------------------

    /** 写出表头行（含加粗、灰底、居中）并设置各列宽度、冻结首行 */
    protected void writeHeaderRow(Sheet sheet, Workbook workbook, CellStyle headerStyle) {
        String[] headers = headers();
        int[] widths = columnWidths();
        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }
        for (int i = 0; i < widths.length; i++) {
            sheet.setColumnWidth(i, widths[i] * 256);
        }
        sheet.createFreezePane(0, 1);
    }

    /** 表头样式：加粗、灰底、居中（与导出文件共用 {@link ExcelStyles}，保证视觉一致） */
    protected CellStyle headerStyle(Workbook workbook) {
        return ExcelStyles.headerStyle(workbook);
    }

    protected CellStyle textStyle(Workbook workbook, boolean wrapText) {
        return ExcelStyles.textStyle(workbook, wrapText);
    }

    // ------------------------------------------------------------------
    // 解析前的体积预检
    // ------------------------------------------------------------------

    /** 按默认上限做解压体积预检 */
    public void assertExpandedSize(InputStream inputStream) {
        assertExpandedSize(inputStream, MAX_ENTRY_BYTES, MAX_TOTAL_BYTES);
    }

    /**
     * 解压体积预检：逐条目累加 zip 声明的解压体积，超出即拒绝。
     *
     * <p>只读 zip 条目表、不解压内容，成本是 O(条目数)。声明体积为 -1（写入端未填）的条目
     * 跳过累加 —— 这类文件由 POI 的 {@link ZipSecureFile} 上限在实际解压时兜住。
     *
     * <p>本方法会消费完输入流，故调用方需要另开一个流给解析方法。
     *
     * @throws BusinessException 解压体积超出上限，或 zip 结构损坏
     * @implNote 非 zip 内容<b>不会</b>在这里报错：{@link ZipInputStream} 对非 zip 只是取不到条目、
     *         并不抛异常。伪装成 .xlsx 的普通文件仍由解析方法统一报「解析失败」——
     *         本方法只负责「体积超限」这一件事。
     */
    public void assertExpandedSize(InputStream inputStream, long maxEntryBytes, long maxTotalBytes) {
        long total = 0L;
        try (ZipInputStream zip = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                long size = entry.getSize();
                if (size <= 0) {
                    continue;
                }
                if (size > maxEntryBytes) {
                    throw expandedTooLarge();
                }
                total += size;
                if (total > maxTotalBytes) {
                    throw expandedTooLarge();
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            // 损坏或截断的 zip
            throw new BusinessException(precheckParseFailedCode());
        }
    }

    private BusinessException expandedTooLarge() {
        return new BusinessException(fileTooLargeCode(),
                "文件解压后的内容体积异常（超出 " + (MAX_TOTAL_BYTES / 1024 / 1024)
                        + "MB），请确认是真实数据后重新上传");
    }

    // ------------------------------------------------------------------
    // 解析骨架
    // ------------------------------------------------------------------

    /**
     * 单行读取回调。
     *
     * @param rowNo 文件中的行号（含表头，从 1 开始），用于把失败原因定位回用户看到的行
     * @return 解析结果；返回 {@code null} 表示跳过该行（整行空白）
     */
    @FunctionalInterface
    protected interface RowReader<T> {
        T readRow(Row row, int rowNo, DataFormatter formatter);
    }

    /**
     * 通用解析骨架：跳过第 1 行（表头）、完全空白行，以及含 {@link #EXAMPLE_MARKER} 的示例行，
     * 其余交给 {@code reader} 做列映射。
     *
     * <p>调用方应先用 {@link #assertExpandedSize(InputStream)} 做体积预检
     * （需要两个独立的输入流：本方法会消费完整个流）。
     *
     * @param maxRows      业务上限，超出即抛 {@code rowLimitCode}
     * @param rowLimitCode 行数超限错误码（各模块自己的码，便于前端区分场景）
     * @param emptyCode    无数据行错误码
     */
    protected <T> List<T> parseSheet(InputStream inputStream, int maxRows, RowReader<T> reader,
                                     ErrorCode rowLimitCode, ErrorCode emptyCode) {
        List<T> rows = new ArrayList<>();
        // DataFormatter 内部持有 HashMap 缓存与可变 DateFormat，POI 明确标注为**非线程安全**，
        // 故不能作为单例字段共享；每次解析新建一个，用完即弃。
        DataFormatter formatter = new DataFormatter(Locale.CHINA);
        try (Workbook workbook = new XSSFWorkbook(inputStream)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new BusinessException(emptyCode);
            }
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                throw new BusinessException(emptyCode);
            }
            int lastRowNum = sheet.getLastRowNum();
            // 资源护栏：先按物理行数快速拒绝，避免为几十万行逐行构造对象后才报错
            if (lastRowNum >= MAX_PHYSICAL_ROWS) {
                throw new BusinessException(rowLimitCode,
                        "文件包含 " + (lastRowNum + 1) + " 行，超出处理上限，请拆分为每份不超过 "
                                + maxRows + " 行后重新导入");
            }
            for (int i = 1; i <= lastRowNum; i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                if (isExampleRow(row, formatter)) {
                    continue;
                }
                T parsed = reader.readRow(row, i + 1, formatter);
                if (parsed == null) {
                    continue;
                }
                rows.add(parsed);
                if (rows.size() > maxRows) {
                    throw new BusinessException(rowLimitCode,
                            "单次最多导入 " + maxRows + " 行，当前文件已超出，请拆分后重新导入");
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // POI 对损坏/伪装文件会抛各种运行时异常（NotOfficeXmlFileException 等），
            // 统一转为可读的规范错误码，避免把 POI 内部信息暴露给用户
            throw new BusinessException(parseFailedCode());
        }
        if (rows.isEmpty()) {
            throw new BusinessException(emptyCode);
        }
        return rows;
    }

    /** 是否为模板自带的示例行（任一单元格含「导入前删除」即视为示例） */
    protected boolean isExampleRow(Row row, DataFormatter formatter) {
        int count = columnCount();
        for (int i = 0; i < count; i++) {
            String text = cellText(row.getCell(i), formatter);
            if (text != null && text.contains(EXAMPLE_MARKER)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单元格 → 文本
     *
     * <p>四类特殊处理（都是实际导入中最容易踩的坑）：
     * <ol>
     *   <li><b>日期单元格</b>：按 yyyy-MM-dd 输出，避免用户设了日期格式后读出一串数字；</li>
     *   <li><b>整数型数字单元格</b>：用 {@link BigDecimal#toPlainString()} 输出，
     *       否则资产编号这类「纯数字编号」会被 Excel 转成数字并以科学计数法呈现（2.026E7）；</li>
     *   <li><b>公式单元格</b>：读取<b>缓存结果</b>（用户在 Excel 里看到的值），
     *       而不是公式本体 —— 见 {@link #formulaText(Cell, DataFormatter)}；</li>
     *   <li><b>空白单元格</b>：返回 null（由上层按「必填 / 选填」判定）。</li>
     * </ol>
     */
    protected String cellText(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType();
        if (type == CellType.BLANK) {
            return null;
        }
        if (type == CellType.FORMULA) {
            String text = formulaText(cell, formatter);
            if (text == null) {
                throw new BusinessException(parseFailedCode(),
                        "第 " + (cell.getRowIndex() + 1) + " 行「" + columnLabel(cell.getColumnIndex())
                                + "」是公式且没有计算结果，请在 Excel 中打开并保存（或改用「粘贴为数值」）后重新上传");
            }
            return text;
        }
        if (type == CellType.NUMERIC) {
            return numberText(cell, formatter);
        }
        return emptyToNull(formatter.formatCellValue(cell));
    }

    /**
     * 公式单元格取「缓存结果」。
     *
     * <p>Excel 保存文件时会把公式的<b>计算结果</b>一并写入（{@code <v>} 节点），用户打开看到的
     * 就是它。而 POI 的 {@link DataFormatter} 在<b>没有传入 FormulaEvaluator 时会把公式文本
     * 原样返回</b>（例如 {@code CONCATENATE("资产","X")}）—— 若沿用它，用户看到「资产X」，
     * 导入进来的却是公式字符串，属于静默写入错误数据。故这里显式按缓存结果类型取值，
     * 拿不到结果就由上层报错，绝不把公式本体当业务数据。
     *
     * <p>刻意<b>不</b>用 FormulaEvaluator 现算：现算会执行文件里的公式，且对跨工作簿引用、
     * 未实现函数等场景并不等价于 Excel 的结果；「读缓存值」既安全又与用户所见一致。
     *
     * @return 缓存结果文本；无可用缓存结果时返回 null
     */
    protected String formulaText(Cell cell, DataFormatter formatter) {
        return switch (cell.getCachedFormulaResultType()) {
            case STRING -> emptyToNull(cell.getRichStringCellValue().getString());
            case NUMERIC -> numberText(cell, formatter);
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            // _NONE / BLANK / ERROR：公式没有可用结果（未计算、或计算失败）
            default -> null;
        };
    }

    /** 数字单元格 → 文本：日期按 yyyy-MM-dd，整数不走科学计数法 */
    protected String numberText(Cell cell, DataFormatter formatter) {
        if (DateUtil.isCellDateFormatted(cell)) {
            LocalDate date = cell.getLocalDateTimeCellValue().toLocalDate();
            return ISO_DATE.format(date);
        }
        double value = cell.getNumericCellValue();
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return BigDecimal.valueOf(value).toPlainString();
        }
        return emptyToNull(formatter.formatCellValue(cell));
    }

    /** 列号 → 面向用户的列名（落在模板列内用表头文案，否则用 Excel 列字母） */
    protected String columnLabel(int columnIndex) {
        String[] headers = headers();
        if (columnIndex >= 0 && columnIndex < headers.length) {
            return headers[columnIndex];
        }
        StringBuilder name = new StringBuilder();
        int index = columnIndex;
        while (index >= 0) {
            name.insert(0, (char) ('A' + index % 26));
            index = index / 26 - 1;
        }
        return name + " 列";
    }

    protected String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    protected String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
