package com.enterprise.ticket.module.ha.dto.vo;

/**
 * 主备运维动作结果
 *
 * <p>用于「手动切换主备」「立即同步」这类<b>调用外部脚本</b>的动作。
 *
 * <h2>为什么这些动作不直接抛异常，而是返回一个带有 {@link #success} 的结果对象</h2>
 * <p>区分两类失败是必要的：
 * <ul>
 *   <li><b>请求本身非法</b>（没启用主备、脚本不存在、已有切换在进行中）→ 抛
 *       {@code BusinessException}，由全局异常处理器翻成对应错误码 —— 前端弹一个错误提示；</li>
 *   <li><b>请求合法但脚本执行失败</b>（脚本存在、跑起来却退出码非 0，例如目标机不可达）→
 *       返回本对象，把脚本的原始输出一并带回。</li>
 * </ul>
 * 第二类不抛异常的原因：它不是「参数错」，而是「现实世界的操作结果」，
 * 前端需要把 {@link #output} 原样展示给维护人员（那里面有 ssh 报错、keepalived 拒绝等
 * 真正能定位问题的信息）。若翻成一个笼统的错误码，这些信息就丢了。
 *
 * <h2>{@link #dryRun} 必须如实下发</h2>
 * <p>沙箱 / 未就绪环境下脚本只会被「打印出来而不执行」。页面必须在提示里明确写出
 * 「本次为演练（dry-run），未真正执行」—— 否则维护人员会把一次演练当成真实切换，
 * 这比彻底不能执行更危险。
 *
 * @param success  动作是否成功（dry-run 且命令组装成功时为 true，但 {@code dryRun=true} 会另行提示）
 * @param dryRun   是否为演练模式（未真正执行脚本）
 * @param action   动作标识（SWITCHOVER_TO_PEER / SWITCHOVER_BACK / STATUS / SYNC）
 * @param command  实际执行（或将要执行）的命令行，供维护人员对照部署文档手工执行
 * @param exitCode 进程退出码；未真正执行时为 null
 * @param output   进程输出（stdout + stderr 合并）；未执行时为空
 * @param message  给维护人员看的一句人话结论
 */
public record HaActionResultVO(
        boolean success,
        boolean dryRun,
        String action,
        String command,
        Integer exitCode,
        String output,
        String message
) {
}
