package com.enterprise.ticket.common.form;

import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.constant.FormFieldType;
import com.enterprise.ticket.common.exception.BusinessException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 表单<b>定义</b>合法性校验
 *
 * <h2>为什么必须在「发布」这一刻校验</h2>
 * <p>设计器是自由拖拽的，草稿允许多处半成品（还没填显示名的字段、还没配选项的下拉……），
 * 否则用户根本没法边想边存。但一旦<b>发布</b>，这个 schema 就会被申请类型引用、
 * 被成百上千次提交用来校验数据 —— 此时任何一个「配漏了」都会变成运行期故障：
 * 选项类字段没有选项 → 用户永远选不出合法值；文本字段写了非法正则 → 每次提交都抛异常。
 *
 * <p>所以校验点选在「发布」：草稿宽松（能存），发布严格（能用）。这也让错误暴露在
 * <b>配置者</b>面前（他正在设计器里，改起来最快），而不是在使用者面前。
 *
 * <h2>顺手归一化</h2>
 * <p>{@link #validateAndNormalize} 会清掉「与类型无关的残留配置」（如把文本字段
 * 之前配过的 min/max 清空）。原因：用户切换字段类型时旧配置会留在对象里，
 * 若原样存库，将来有人读 schema 会看到「文本字段带着 min/max」这种自相矛盾的定义，
 * 消耗排查时间。
 */
public final class FormSchemaValidator {

    /** 字段 key：字母/下划线开头，其后为字母/数字/下划线 */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private static final Pattern PREFIX_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9]{1,9}$");

    private static final Pattern TYPE_CODE_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{1,19}$");

    /** 日期范围限制的合法取值 */
    private static final Set<String> DATE_LIMITS =
            Set.of("NONE", "NOT_BEFORE_TODAY", "NOT_AFTER_TODAY", "CUSTOM");

    /**
     * 明确禁止的扩展名。
     *
     * <p>{@code svg} 是唯一一个：它本质是 XML，可内嵌 {@code <script>}，
     * 在浏览器里以内联方式打开即等价于 XSS 载荷。项目既有的下载 / 预览白名单
     * 已经排除它（服务端只认白名单），这里在设计器侧再拦一道 ——
     * 让配置者在「配字段」时就被告知「svgo 不行」，而不是等用户传上去被拒绝。
     */
    private static final Set<String> FORBIDDEN_EXTS = Set.of("svg", "html", "htm", "js", "exe", "bat", "sh", "jar");

    private static final int MAX_FIELDS = 50;
    private static final int MAX_OPTIONS = 50;
    private static final int MAX_LABEL_LEN = 64;
    private static final int MAX_KEY_LEN = 64;
    private static final int MAX_TEXT_LEN = 2000;
    private static final int MAX_PATTERN_LEN = 200;

    private FormSchemaValidator() {
    }

    /**
     * 校验并归一化表单定义。
     *
     * @throws BusinessException {@link ErrorCode#FORM_SCHEMA_INVALID}，message 指明具体问题
     */
    public static void validateAndNormalize(FormSchema schema) {
        if (schema == null) {
            throw invalid("表单定义不能为空");
        }
        List<FormField> fields = schema.getFields();
        if (fields.isEmpty()) {
            throw invalid("表单至少需要 1 个字段");
        }
        if (fields.size() > MAX_FIELDS) {
            throw invalid("表单字段数量不能超过 " + MAX_FIELDS + " 个");
        }

        Set<String> keys = new HashSet<>();
        for (int i = 0; i < fields.size(); i++) {
            FormField field = fields.get(i);
            String position = "第 " + (i + 1) + " 个字段";
            if (field == null) {
                throw invalid(position + "为空，请删除后重新添加");
            }
            FormFieldType type = FormFieldType.of(field.getType());
            if (type == null) {
                throw invalid(position + "的字段类型不合法：" + field.getType());
            }
            normalizeWidth(field, position);

            if (type == FormFieldType.DESCRIPTION) {
                requireNonBlank(field.getContent(), position + "（说明文字）的内容不能为空");
                clearDataConfig(field);
                continue;
            }
            if (type == FormFieldType.DIVIDER) {
                clearDataConfig(field);
                continue;
            }

            validateLabelAndKey(field, position, keys);
            validateOptions(field, type, position);
            validateNumber(field, type, position);
            validateText(field, type, position);
            validateDate(field, type, position);
            validateAttachment(field, type, position);
        }
    }

    /**
     * 发布前的「系统内置字段不可丢失」守卫（V53）
     *
     * <h2>为什么在发布这一刻做，而不是保存草稿时</h2>
     * <p>沿用本类既有的「草稿宽松、发布严格」分层：设计器里允许先把字段拖来拖去（含临时删掉），
     * 但一旦发布，这份 schema 就是业务逻辑的读取依据。{@code systemField=true} 的字段
     * （如出差的起止日期、报修/借用的「当前所在分部」）被后端按<b>固定 key</b> 读取，
     * 删掉或改类型不会报错，只会让分配、预填、重叠校验<b>静默失效</b> —— 排查成本极高。
     *
     * <h2>三条规则</h2>
     * <ol>
     *   <li>已发布版本中的系统字段，新版本必须<b>仍存在同 key 字段</b>（改 key 等同删除）；</li>
     *   <li>字段<b>类型不可变</b>（DATE 改成 TEXT 会让日期解析全部走兜底）；</li>
     *   <li>允许改显示名、必填、排序位置、宽度 —— 这些不影响取值。</li>
     * </ol>
     *
     * <p>另外在这里把残留的系统字段标记<b>恢复</b>为 true：否则「先通过接口把 systemField 摘掉，
     * 下次发布再删」就能绕过本守卫，等于没做。
     *
     * @param published 该模板最近一次<b>已发布</b>的 schema；为空（首次发布）时直接放行
     * @param draft     本次待发布的 schema（会被就地修改：恢复系统字段标记）
     * @throws BusinessException {@link ErrorCode#FORM_SCHEMA_INVALID}
     */
    public static void assertSystemFieldsKept(FormSchema published, FormSchema draft) {
        if (published == null || draft == null) {
            return;
        }
        Map<String, FormField> draftByKey = new LinkedHashMap<>();
        for (FormField field : draft.getFields()) {
            if (field != null && field.getKey() != null) {
                draftByKey.putIfAbsent(field.getKey(), field);
            }
        }

        List<String> missing = new ArrayList<>();
        List<String> retyped = new ArrayList<>();
        for (FormField old : published.getFields()) {
            if (old == null || !Boolean.TRUE.equals(old.getSystemField())) {
                continue;
            }
            String name = "「" + (isBlank(old.getLabel()) ? old.getKey() : old.getLabel())
                    + "」（" + old.getKey() + "）";
            FormField now = draftByKey.get(old.getKey());
            if (now == null) {
                missing.add(name);
                continue;
            }
            if (!Objects.equals(old.getType(), now.getType())) {
                retyped.add(name + "：" + old.getType() + " → " + now.getType());
                continue;
            }
            now.setSystemField(true);
        }

        if (missing.isEmpty() && retyped.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder(
                "系统内置字段是业务逻辑的读取依据，不允许删除或修改字段类型（否则分配 / 预填 / 校验会静默失效）：");
        if (!missing.isEmpty()) {
            message.append(" 已删除 ").append(String.join("、", missing));
        }
        if (!retyped.isEmpty()) {
            message.append(missing.isEmpty() ? " " : "；").append("已改类型 ").append(String.join("、", retyped));
        }
        throw invalid(message.toString());
    }

    /** 申请类型编码：字母开头，仅字母/数字/下划线，2–20 位 */
    public static void validateTypeCode(String typeCode) {
        if (typeCode == null || !TYPE_CODE_PATTERN.matcher(typeCode).matches()) {
            throw new BusinessException(ErrorCode.APPLY_TYPE_CODE_INVALID,
                    "类型编码不合法（字母开头，仅字母/数字/下划线，2-20 位）：" + typeCode);
        }
    }

    /** 工单编号前缀：字母开头，2–10 位字母/数字（空值表示用系统默认规则） */
    public static void validateOrderPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return;
        }
        if (!PREFIX_PATTERN.matcher(prefix).matches()) {
            throw new BusinessException(ErrorCode.APPLY_TYPE_PREFIX_INVALID,
                    "工单编号前缀不合法（字母开头，2-10 位字母/数字）：" + prefix);
        }
    }

    // ------------------------------------------------------------------
    // 分类型校验
    // ------------------------------------------------------------------

    private static void normalizeWidth(FormField field, String position) {
        Integer width = field.getWidth();
        if (width == null) {
            field.setWidth(1);
            return;
        }
        if (width != 1 && width != 2) {
            throw invalid(position + "的宽度只能是 1（整行）或 2（半行）");
        }
    }

    private static void validateLabelAndKey(FormField field, String position, Set<String> keys) {
        requireNonBlank(field.getLabel(), position + "的显示名称不能为空");
        if (field.getLabel().length() > MAX_LABEL_LEN) {
            throw invalid(position + "的显示名称不能超过 " + MAX_LABEL_LEN + " 个字符");
        }
        String key = field.getKey();
        requireNonBlank(key, "字段「" + field.getLabel() + "」的字段 key 不能为空");
        if (key.length() > MAX_KEY_LEN) {
            throw invalid("字段 key 不能超过 " + MAX_KEY_LEN + " 个字符：" + key);
        }
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw invalid("字段 key「" + key + "」不合法：必须以字母或下划线开头，只能包含字母、数字、下划线");
        }
        if (!keys.add(key)) {
            throw invalid("字段 key「" + key + "」重复，同一表单内 key 必须唯一");
        }
    }

    private static void validateOptions(FormField field, FormFieldType type, String position) {
        if (!type.isSupportsOptions()) {
            field.setOptions(null);
            return;
        }
        List<FormOption> options = field.getOptions();
        if (options == null || options.isEmpty()) {
            throw invalid(position + "（" + type.getLabel() + "）至少需要配置 1 个选项");
        }
        if (options.size() > MAX_OPTIONS) {
            throw invalid(position + "的选项数量不能超过 " + MAX_OPTIONS + " 个");
        }
        Set<String> values = new LinkedHashSet<>();
        for (FormOption option : options) {
            if (option == null) {
                throw invalid(position + "存在空的选项行，请删除后重新添加");
            }
            requireNonBlank(option.getValue(), position + "存在选项值为空的选项");
            requireNonBlank(option.getLabel(), position + "存在显示名为空的选项");
            if (!values.add(option.getValue())) {
                throw invalid(position + "存在重复的选项值：" + option.getValue());
            }
        }
    }

    private static void validateNumber(FormField field, FormFieldType type, String position) {
        if (type != FormFieldType.NUMBER) {
            field.setMin(null);
            field.setMax(null);
            field.setPrecision(null);
            field.setUnit(null);
            return;
        }
        if (field.getMin() != null && field.getMax() != null && field.getMin() > field.getMax()) {
            throw invalid(position + "的最小值不能大于最大值");
        }
        Integer precision = field.getPrecision();
        if (precision != null && (precision < 0 || precision > 4)) {
            throw invalid(position + "的小数位数只能是 0–4");
        }
    }

    private static void validateText(FormField field, FormFieldType type, String position) {
        if (type != FormFieldType.TEXT && type != FormFieldType.TEXTAREA) {
            field.setMinLength(null);
            field.setMaxLength(null);
            field.setPattern(null);
            return;
        }
        Integer min = field.getMinLength();
        Integer max = field.getMaxLength();
        if (min != null && min < 0) {
            throw invalid(position + "的最小长度不能为负数");
        }
        if (max != null && (max < 1 || max > MAX_TEXT_LEN)) {
            throw invalid(position + "的最大长度需在 1–" + MAX_TEXT_LEN + " 之间");
        }
        if (min != null && max != null && min > max) {
            throw invalid(position + "的最小长度不能大于最大长度");
        }
        String pattern = field.getPattern();
        if (pattern != null && !pattern.isBlank()) {
            if (pattern.length() > MAX_PATTERN_LEN) {
                throw invalid(position + "的正则表达式过长（不超过 " + MAX_PATTERN_LEN + " 个字符）");
            }
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                throw invalid(position + "的正则表达式不合法：" + e.getDescription());
            }
        }
    }

    private static void validateDate(FormField field, FormFieldType type, String position) {
        if (type != FormFieldType.DATE && type != FormFieldType.DATETIME) {
            field.setDateLimit(null);
            field.setDateMin(null);
            field.setDateMax(null);
            return;
        }
        String limit = field.getDateLimit();
        if (limit == null || limit.isBlank()) {
            field.setDateLimit("NONE");
            limit = "NONE";
        } else if (!DATE_LIMITS.contains(limit)) {
            throw invalid(position + "的日期范围限制取值不合法：" + limit);
        }
        if (!"CUSTOM".equals(limit)) {
            field.setDateMin(null);
            field.setDateMax(null);
            return;
        }
        if (isBlank(field.getDateMin()) && isBlank(field.getDateMax())) {
            throw invalid(position + "选择了「自定义」日期范围，需要至少填写起始或截止日期");
        }
        if (!isBlank(field.getDateMin()) && !isBlank(field.getDateMax())
                && field.getDateMin().compareTo(field.getDateMax()) > 0) {
            throw invalid(position + "的自定义起始日期不能晚于截止日期");
        }
    }

    private static void validateAttachment(FormField field, FormFieldType type, String position) {
        if (type != FormFieldType.FILE && type != FormFieldType.IMAGE) {
            field.setMaxCount(null);
            field.setFileTypes(null);
            field.setMaxSizeMb(null);
            return;
        }
        Integer maxCount = field.getMaxCount();
        if (maxCount == null) {
            maxCount = 1;
            field.setMaxCount(maxCount);
        }
        if (maxCount < 1 || maxCount > 10) {
            throw invalid(position + "的附件数量上限需在 1–10 之间");
        }
        Integer maxSizeMb = field.getMaxSizeMb();
        if (maxSizeMb == null) {
            maxSizeMb = 10;
            field.setMaxSizeMb(maxSizeMb);
        }
        if (maxSizeMb < 1 || maxSizeMb > 50) {
            throw invalid(position + "的单文件大小上限需在 1–50 MB 之间");
        }
        String fileTypes = field.getFileTypes();
        if (fileTypes == null || fileTypes.isBlank()) {
            field.setFileTypes(null);
            return;
        }
        for (String raw : fileTypes.split(",")) {
            String ext = raw.trim().toLowerCase(Locale.ROOT);
            if (ext.isEmpty()) {
                continue;
            }
            if (!ext.matches("^[a-z0-9]{1,8}$")) {
                throw invalid(position + "的文件类型「" + raw.trim() + "」不合法（只写扩展名，如 pdf,docx）");
            }
            if (FORBIDDEN_EXTS.contains(ext)) {
                throw invalid(position + "不允许使用高风险文件类型：" + ext);
            }
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static void clearDataConfig(FormField field) {
        field.setKey(null);
        field.setRequired(false);
        field.setOptions(null);
        field.setMin(null);
        field.setMax(null);
        field.setPrecision(null);
        field.setUnit(null);
        field.setMinLength(null);
        field.setMaxLength(null);
        field.setPattern(null);
        field.setDateLimit(null);
        field.setDateMin(null);
        field.setDateMax(null);
        field.setMaxCount(null);
        field.setFileTypes(null);
        field.setMaxSizeMb(null);
        field.setPlaceholder(null);
    }

    private static void requireNonBlank(String value, String message) {
        if (isBlank(value)) {
            throw invalid(message);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.FORM_SCHEMA_INVALID, message);
    }

    /** 供测试断言「合法 schema 的字段类型清单」与枚举对齐（避免测试与实现各写一份） */
    public static List<String> supportedTypes() {
        List<String> names = new ArrayList<>();
        for (FormFieldType type : FormFieldType.values()) {
            names.add(type.name());
        }
        return names;
    }
}
