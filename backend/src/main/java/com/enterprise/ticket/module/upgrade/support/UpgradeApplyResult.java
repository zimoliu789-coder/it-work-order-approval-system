package com.enterprise.ticket.module.upgrade.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 外部编排脚本的应用结果回执（{@code state/result/&lt;taskNo&gt;.json}）
 *
 * <p>这是「后端」与「进程外脚本」之间<b>唯一</b>的通信载体，因此格式必须极简且稳定 ——
 * 它由 shell 写出（{@code printf} 拼 JSON），由 Java 读入。任何一边改字段名，
 * 另一边都会静默读不到值（{@code ignoreUnknown} 会让它变成「字段是 null」而不是报错），
 * 所以两侧的契约要同时改：本类与 {@code deploy/ha/scripts/upgrade-apply.sh}。
 *
 * <p>{@code result} 取值：
 * <ul>
 *   <li>{@code SUCCESS} —— 新产物已替换且健康检查通过；</li>
 *   <li>{@code ROLLED_BACK} —— 新产物起不来，脚本已还原备份并重启成功；</li>
 *   <li>{@code FAILED} —— 脚本自身失败（既没起来也没还原成功），需人工介入。</li>
 * </ul>
 *
 * <p>{@code finishedAt} 刻意用 {@link String} 而不是 {@code LocalDateTime}：
 * 它由 shell 用 {@code date} 写出，后端只是原样转存进任务说明里展示。
 * 声明成时间类型会引入一条**没有任何收益**的解析耦合 ——
 * 脚本的日期格式一旦与 Jackson 的期望不符（带不带毫秒、带不带时区），
 * 整个回执都会读不出来，而这个字段本来就没参与任何判断。
 *
 * <p>为什么脚本只写这三种结果、不写「进度」：进度属于后端能算的东西（解压到第几个文件），
 * 而「替换 + 重启」这一段对外是原子的 —— 要么新版本在跑，要么旧版本在跑，
 * 中间没有可展示的稳定状态。造一个假进度条只会让人更焦虑。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpgradeApplyResult(String taskNo, String result, String message, String finishedAt) {
}
