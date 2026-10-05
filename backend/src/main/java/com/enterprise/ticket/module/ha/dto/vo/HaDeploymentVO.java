package com.enterprise.ticket.module.ha.dto.vo;

/**
 * 主备部署资产探针结果
 *
 * <h2>为什么要有这个 VO —— 本次诚实性的落点</h2>
 * <p>本次的验收口径已提前拍板（{@code .docs/phase19-plan.md} 决策 5）：
 * 沙箱内 <b>Docker 不可用、没有第二台机器</b>，因此「真实心跳 / 真实 VIP 漂移 /
 * 真实 keepalived 切换」<b>无法</b>在本环境验证，只能交付
 * 「页面 + 接口 + 状态机 + 静态校验 + 脚本语法」。
 *
 * <p>由此产生一个必须正面处理的取舍：页面上的「手动切换主备」「立即同步」按钮
 * 会调用真实的 {@code deploy/ha/scripts/switchover.sh}，而在沙箱里那个脚本
 * （连同 {@code /etc/keepalived/} 目录、root 权限、第二台机器）<b>根本不存在</b>。
 * 这类接口最容易犯的错是「静默降级成假成功」—— 返回一个绿色提示，
 * 让维护人员以为切换已经完成，而实际上什么都没发生。
 * 运维界面上这种假成功比一个明确的报错危险得多。
 *
 * <p>因此凡是执行真实脚本的动作，都先把这组探针结果<b>随动作结果一起返回</b>：
 * 「脚本在不在」「部署目录配了没」「是不是 dry-run」全部如实告知，
 * 沙箱里应得的结果就是「脚本不存在，无法执行真实切换」这条明确的失败。
 *
 * @param haDirConfigured               是否配置了 {@code app.ha.deploy-dir}
 * @param haDir                         实际使用的部署资产目录（相对或绝对路径）
 * @param deployDirExists               该目录此刻是否存在
 * @param switchoverScriptPresent       {@code switchover.sh} 是否存在
 * @param setupReplicationScriptPresent {@code setup-replication.sh} 是否存在
 * @param keepalivedConfPresent         {@code keepalived/keepalived.conf.tmpl} 是否存在
 * @param dryRunEnabled                 是否为 dry-run 模式（只打印将要执行的命令，不真正执行）
 * @param hint                          给维护人员的一句人话说明（为什么现在不能真执行）
 */
public record HaDeploymentVO(
        boolean haDirConfigured,
        String haDir,
        boolean deployDirExists,
        boolean switchoverScriptPresent,
        boolean setupReplicationScriptPresent,
        boolean keepalivedConfPresent,
        boolean dryRunEnabled,
        String hint
) {

    /**
     * 是否具备执行真实运维脚本的条件（目录在 + 切换脚本在）。
     *
     * <p>刻意<b>不</b>把 {@code keepalivedConfPresent} 计入：切换脚本本身
     * 只操纵 {@code /etc/keepalived/MAINT} 标记文件，配置模板缺失说明的是
     * 「keepalived 还没初始化」，那是 {@code install-keepalived.sh} 的职责，
     * 不影响「现在能不能发起一次切换」。把无关条件塞进这个判定，
     * 会让一个其实可执行的动作被误判为不可执行。
     */
    public boolean switchoverExecutable() {
        return deployDirExists && switchoverScriptPresent;
    }
}
