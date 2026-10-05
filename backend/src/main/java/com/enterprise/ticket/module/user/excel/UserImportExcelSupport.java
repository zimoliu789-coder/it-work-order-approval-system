package com.enterprise.ticket.module.user.excel;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.excel.ExcelImportSupport;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.module.user.dto.UserImportRow;
import com.enterprise.ticket.module.user.dto.vo.UserImportRowVO;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 员工批量导入的 Excel 读写支持（需求方 2026-09-18 小迭代 · ）
 *
 * <p>通用能力（解析安全三道防线、单元格取值、模板与明细样式、公式注入防护）全部继承自
 * {@link ExcelImportSupport}；本类只声明<b>员工列定义</b>与行映射。
 * 与设备导入同构 —— 这正是把它抽成基类的原因：两处若各自实现，
 * 「只给其中一处补上压缩炸弹防护」几乎是必然发生的事故。
 *
 * <p><b>唯一性校验不在本类</b>：只有<b>登录名</b>需要唯一（且要同时查「库内已有」与
 * 「文件内重复」）。 起姓名允许重复（公司可能有多个张伟），因此姓名<b>不再</b>参与唯一性判定。
 * 登录名唯一属于业务规则，放在 {@code UserImportServiceImpl} 里，
 * 与「新增员工」复用同一套判定，保证两条入口不会漂移。
 *
 * <p>列定义：姓名、登录名、初始密码、部门名称、角色、显示名称、直属领导。
 */
@Component
public class UserImportExcelSupport extends ExcelImportSupport {

    /**
     * 模板/明细列顺序（解析、生成模板、失败明细共用同一份定义）
     *
     * <p> 在<b>末尾</b>追加「直属领导」列（按姓名匹配）：追加而非插队，
     * 使用户手上的旧模板（6 列）仍能被正确解析 —— 旧文件第 7 列为空即视为「不配置领导」。
     */
    public static final String[] HEADERS = {
            "姓名", "登录名", "初始密码", "部门名称", "角色", "显示名称", "直属领导"
    };

    /** 各列建议宽度（单位：1/256 个字符宽） */
    private static final int[] COLUMN_WIDTHS = {
            14, 18, 18, 20, 14, 18, 18
    };

    /** 失败明细在模板列之后追加的列 */
    private static final String FAILURE_REASON_HEADER = "失败原因";

    private static final int COL_REAL_NAME = 0;
    private static final int COL_USERNAME = 1;
    private static final int COL_PASSWORD = 2;
    private static final int COL_BIZ_GROUP_NAME = 3;
    private static final int COL_ROLE = 4;
    private static final int COL_DISPLAY_NAME = 5;
    private static final int COL_LEADER_NAME = 6;

    @Override
    protected String[] headers() {
        return HEADERS;
    }

    @Override
    protected int[] columnWidths() {
        return COLUMN_WIDTHS;
    }

    // 员工导入使用自己的错误码，便于前端区分「设备导入失败」与「员工导入失败」
    @Override
    protected ErrorCode fileTooLargeCode() {
        return ErrorCode.USER_IMPORT_FILE_TOO_LARGE;
    }

    @Override
    protected ErrorCode precheckParseFailedCode() {
        return ErrorCode.USER_IMPORT_PARSE_FAILED;
    }

    @Override
    protected ErrorCode parseFailedCode() {
        return ErrorCode.USER_IMPORT_PARSE_FAILED;
    }

    // ------------------------------------------------------------------
    // 模板生成
    // ------------------------------------------------------------------

    /**
     * 生成导入模板：第 1 行表头，第 2 行示例数据（标注「导入前删除」，解析时自动跳过）
     *
     * <p>示例行的初始密码用需求方指定的统一演示口令，让「填什么」一目了然；
     * 示例行会在解析时被跳过，绝不会被真的导入（{@link #EXAMPLE_MARKER}）。
     */
    public byte[] buildTemplate() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("员工导入模板");
            writeHeaderRow(sheet, workbook, headerStyle(workbook));

            Row example = sheet.createRow(1);
            // ：姓名与登录名是两列、两套规则（姓名纯中文可重名、登录名 5 位以上纯数字）。
            // 示例值刻意写成「张三 / 10001」而不是「张三 / zhangsan」——
            // 模板示例是用户唯一的填写依据，示例本身不合规会直接教出不合规的数据。
            // 示例口令用中性占位串（不是任何账号的真实口令）：模板要「照着填」，
            // 因此示例必须能通过密码策略，但又不能是一个看起来像真的口令。
            String[] sample = {
                    "张三", "10001", "Init@2026", "研发部", "user",
                    "示例数据，导入前请删除本行（" + EXAMPLE_MARKER + "）",
                    "李四"
            };
            for (int i = 0; i < sample.length; i++) {
                example.createCell(i).setCellValue(sample[i]);
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "生成导入模板失败，请稍后重试");
        }
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /**
     * 解析上传的 .xlsx 为行数据（不做业务校验）
     *
     * <p>调用方应先用 {@link #assertExpandedSize(InputStream)} 做体积预检
     * （需要两个独立的输入流：本方法会消费完整个流）。
     *
     * @param maxRows 允许的最大数据行数，超出即抛 {@code USER_IMPORT_ROW_LIMIT_EXCEEDED}
     */
    public List<UserImportRow> parse(InputStream inputStream, int maxRows) {
        return parseSheet(inputStream, maxRows, this::readRow,
                ErrorCode.USER_IMPORT_ROW_LIMIT_EXCEEDED, ErrorCode.USER_IMPORT_EMPTY);
    }

    /** 读取一行；整行为空（无任何非空单元格）时返回 null */
    private UserImportRow readRow(Row row, int rowNo, DataFormatter formatter) {
        String[] values = new String[HEADERS.length];
        boolean blank = true;
        for (int i = 0; i < HEADERS.length; i++) {
            values[i] = cellText(row.getCell(i), formatter);
            if (values[i] != null && !values[i].isEmpty()) {
                blank = false;
            }
        }
        if (blank) {
            return null;
        }
        UserImportRow result = new UserImportRow();
        result.setRowNo(rowNo);
        result.setRealName(values[COL_REAL_NAME]);
        result.setUsername(values[COL_USERNAME]);
        result.setPassword(values[COL_PASSWORD]);
        result.setDepartmentName(values[COL_BIZ_GROUP_NAME]);
        result.setRole(values[COL_ROLE]);
        result.setDisplayName(values[COL_DISPLAY_NAME]);
        result.setLeaderName(values[COL_LEADER_NAME]);
        return result;
    }

    // ------------------------------------------------------------------
    // 失败明细导出
    // ------------------------------------------------------------------

    /**
     * 生成失败明细 Excel：与模板同列（保证修好后可直接重新导入），末尾追加「失败原因」列
     *
     * <p>「失败原因」是用户修行的主要依据，因此会写明<b>是哪一行和谁重了</b>
     * （例如「文件内第 3 行与第 7 行姓名重复」），而不是笼统的「重复」。
     *
     * <p>所有取值都用 {@code setCellValue(String)} 写入 —— 得到字符串单元格而非公式单元格，
     * 用户数据里以 {@code = + - @} 开头的内容在 Excel 中只按文本显示，不会被当作公式求值
     * （防公式注入）。修改本方法时务必保持用 setCellValue 写字符串。
     */
    public byte[] buildFailureReport(List<UserImportRowVO> failures) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("导入失败明细");
            CellStyle headerStyle = headerStyle(workbook);
            writeHeaderRow(sheet, workbook, headerStyle);
            CellStyle reasonStyle = textStyle(workbook, true);

            Cell reasonHeader = sheet.getRow(0).createCell(HEADERS.length);
            reasonHeader.setCellValue(FAILURE_REASON_HEADER);
            reasonHeader.setCellStyle(headerStyle);
            sheet.setColumnWidth(HEADERS.length, 50 * 256);

            int rowIndex = 1;
            for (UserImportRowVO failure : failures) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(COL_REAL_NAME).setCellValue(nullToEmpty(failure.getRealName()));
                row.createCell(COL_USERNAME).setCellValue(nullToEmpty(failure.getUsername()));
                row.createCell(COL_PASSWORD).setCellValue(nullToEmpty(failure.getPassword()));
                row.createCell(COL_BIZ_GROUP_NAME).setCellValue(nullToEmpty(failure.getDepartmentName()));
                row.createCell(COL_ROLE).setCellValue(nullToEmpty(failure.getRole()));
                row.createCell(COL_DISPLAY_NAME).setCellValue(nullToEmpty(failure.getDisplayName()));
                row.createCell(COL_LEADER_NAME).setCellValue(nullToEmpty(failure.getLeaderName()));
                Cell reasonCell = row.createCell(HEADERS.length);
                reasonCell.setCellValue(nullToEmpty(failure.getReason()));
                reasonCell.setCellStyle(reasonStyle);
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "生成失败明细失败，请稍后重试");
        }
    }
}
