package com.enterprise.ticket.module.backup.support;

/**
 * 备份执行失败（P0）
 *
 * <p>刻意用一个模块内异常而不是复用 {@code BusinessException}：
 * 备份失败<b>不是用户操作非法</b>，而是环境问题（mysqldump 缺失、目录不可写、磁盘满）。
 * 两者在接口层的处置完全不同 —— 前者回 400 让用户改输入，后者应当<b>被记进
 * {@code backup_record} 并以消息告警</b>，接口本身仍是「请求已受理」。
 * 混用会让「目录不可写」以 400 的形式返回，看起来像管理员填错了参数。
 *
 * <p>{@code message} 会被原样写进 {@code backup_record.error_message} 并出现在告警消息里，
 * 因此<b>用例里必须写清「是什么」与「怎么办」</b>（例如给出实际路径与可配置项名）。
 */
public class BackupException extends RuntimeException {

    public BackupException(String message) {
        super(message);
    }

    public BackupException(String message, Throwable cause) {
        super(message, cause);
    }
}
