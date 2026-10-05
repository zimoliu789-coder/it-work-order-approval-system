package com.enterprise.ticket.config;

import com.enterprise.ticket.common.trace.MdcTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.task.DelegatingSecurityContextAsyncTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步线程池配置
 *
 * <p>专门为「普通操作日志异步落库」提供独立线程池，
 * 与业务线程隔离，避免日志写入阻塞接口响应。
 *
 * <p><b>两个池都挂了 {@link MdcTaskDecorator}</b>（）：异步任务的日志必须带上
 * 提交请求的 traceId，否则一条异步审计日志将无法与它所属的操作请求关联起来。
 */
@Configuration
public class AsyncConfig {

    @Bean("auditLogExecutor")
    public Executor auditLogExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(2000);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("audit-log-");
        // 队列满时由调用线程执行，保证日志不丢
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 跨线程传递 MDC（traceId），否则异步日志缺链路标识
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }

    /**
     * Excel 异步导出线程池（「导出超过 10000 条时异步生成文件」）
     *
     * <p>与审计日志池<b>分开</b>的原因：导出是「用户等待结果」的长任务（秒级到十几秒），
     * 若与日志共用线程池，几个大导出就能把池占满，导致审计日志积压 —— 审计是安全能力，
     * 不能被业务任务饿死。这里队列刻意给得很小：导出是重活，排队十几个不如直接拒绝，
     * 让用户稍后重试（拒绝策略由调用线程执行，天然退化为同步，不会丢任务）。
     *
     * <p><b>为什么包一层 {@link DelegatingSecurityContextAsyncTaskExecutor}</b>：
     * 异步线程默认拿不到 {@code SecurityContextHolder}（它基于 ThreadLocal），
     * 而导出数据装载复用列表 Service —— 那些方法要读当前登录人来判角色、
     * 做「只看自己的工单」过滤。包上这一层后，异步线程继承提交时的安全上下文，
     * 既不需要为异步单独写一套无鉴权的查询，也保证导出仍然按<b>提交人的权限</b>取数
     * （而不是在异步里放开权限）。
     *
     * <p>注意顺序：{@code TaskDecorator} 必须设在<b>被包装之前</b>的原始执行器上，
     * 否则装饰器会被外层包装遮蔽而不生效。
     */
    @Bean("exportExecutor")
    public Executor exportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(32);
        executor.setKeepAliveSeconds(120);
        executor.setThreadNamePrefix("export-");
        // 队列满时由调用线程执行：退化为同步生成，任务不丢（宁可请求慢，不可少文件）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 跨线程传递 MDC（traceId）
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return new DelegatingSecurityContextAsyncTaskExecutor(executor);
    }
}
