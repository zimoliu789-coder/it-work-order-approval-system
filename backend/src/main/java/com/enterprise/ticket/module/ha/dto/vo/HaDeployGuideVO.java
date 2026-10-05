package com.enterprise.ticket.module.ha.dto.vo;

import java.util.List;

/**
 * 主备配置「三步走」指引（，  [169]–[172]）
 *
 * <h2>这个接口存在的理由 —— 它正是需求要解决的那个问题的答案</h2>
 * <p>需求 [157] 行的现状描述是：「可能只做了数据同步没做可视化配置界面，需要改配置文件」；
 * 需求 [158] 行进一步说明：「维护人员不懂 keepalived、数据库主从、rsync 等专业技术」。
 *
 * <p>因此本次的交付物不能只是「几个输入框 + 一个保存按钮」——
 * 那只把「去哪改配置文件」换成了「去哪填输入框」，维护人员仍然要自己去
 * 拼 {@code .env.ha} 与 {@code keepalived.conf}。真正消除痛点的做法是：
 * <b>把系统已经知道的配置值，渲染成一份可以直接抄进部署文件的内容</b>，
 * 并配上一段「第 1 步 / 第 2 步 / 第 3 步」的说明。
 *
 * <p>诚实性边界同样重要：本 VO 产出的是<b>文本</b>，不是「已经替维护人员部署好了」。
 * {@link #steps} 里必须明确写出哪些步骤仍需人工在目标机器上执行
 * （渲染 keepalived 配置、执行复制脚本都需要 root 与目标机环境），
 * 不能让维护人员以为点几下就完成部署 —— 那会在真机上表现为「以为配好了、实际没配」。
 *
 * @param steps               三步操作说明（纯文本，按顺序展示）
 * @param envSnippet          可直接抄进 {@code deploy/ha/.env.ha} 的 HA 段内容
 *                            （密钥与口令一律留 {@code __CHANGE_ME__} 占位符，绝不下发真实值）
 * @param keepalivedConf      keepalived 配置内容（由模板 + 当前配置渲染）；
 *                            null = 服务器上没有模板文件，此时只能由维护人员手工编写
 * @param deployDirPresent    部署资产目录此刻是否存在（决定上面两项能不能渲染出来）
 * @param note                一句总体说明（为什么有些内容渲染不出来 / 下一步该做什么）
 */
public record HaDeployGuideVO(
        List<String> steps,
        String envSnippet,
        String keepalivedConf,
        boolean deployDirPresent,
        String note
) {
}
