package com.enterprise.ticket.module.ops.controller;

import com.enterprise.ticket.common.api.ApiResponse;
import com.enterprise.ticket.common.api.ErrorCode;
import com.enterprise.ticket.common.exception.BusinessException;
import com.enterprise.ticket.config.AppProperties;
import com.enterprise.ticket.module.ops.dto.BackupAlertRequest;
import com.enterprise.ticket.module.ops.service.BackupAlertService;
import com.enterprise.ticket.module.ops.support.InternalTokenVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 内部告警入口单测（部署与限流加固 · 规范 §32）
 *
 * <p>该接口在 {@code permit-all} 白名单里（备份脚本没有用户会话），必须自带凭据校验。
 * 本类钉死三条安全属性：
 * <ol>
 *   <li>令牌正确才放行；</li>
 *   <li>令牌缺失 / 错误一律拒绝，且<b>不触碰</b>业务逻辑（否则等于开放了一个匿名消息通道）；</li>
 *   <li><b>fail-closed</b>：服务端未配置令牌时接口整体关闭，即便客户端也传空值也不放行。</li>
 * </ol>
 *
 * <h2>为什么这里装配的是「真实」的校验器，而不是 mock</h2>
 * <p>批次 G 把内联的 {@code assertInternalToken} 抽成了
 * {@link InternalTokenVerifier}，供备份上报与主备心跳上报共用 ——
 * <b>同一套安全判定绝不能有两份实现</b>（加固只改一处的缺口从代码上看不出来）。
 *
 * <p>抽取之后，令牌比对本身的穷尽用例移到
 * {@code InternalTokenVerifierTest}；但本类刻意<b>不</b>把校验器换成 mock：
 * 本类真正要守的是「校验失败时绝不触碰业务逻辑」这条链路，
 * 换成 mock 就等于把「控制器是否真的接上了校验器」这件事从测试里抹掉了 ——
 * 而那正是抽取重构最容易弄错的地方（本次重构就曾让本类红过一次）。
 */
@ExtendWith(MockitoExtension.class)
class InternalAlertControllerTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Mock
    private BackupAlertService backupAlertService;

    /** 按服务端配置的令牌装配一个真实校验器的控制器 */
    private InternalAlertController controllerWithServerToken(String serverToken) {
        AppProperties properties = new AppProperties();
        properties.getOps().setInternalAlertToken(serverToken);
        return new InternalAlertController(new InternalTokenVerifier(properties), backupAlertService);
    }

    private BackupAlertRequest sampleRequest() {
        BackupAlertRequest request = new BackupAlertRequest();
        request.setStatus("FAILED");
        request.setFileName("db-backup-20260919-020001.tar.gz");
        request.setError("mysqldump: disk full");
        request.setHost("nas-01");
        request.setRetentionDays(30);
        return request;
    }

    @Test
    @DisplayName("令牌正确：放行并回传已通知的超管数量")
    void validToken() {
        when(backupAlertService.report(any())).thenReturn(3);

        ApiResponse<Map<String, Object>> response =
                controllerWithServerToken(SECRET).backup(SECRET, sampleRequest());

        assertEquals(ErrorCode.SUCCESS.getCode(), response.getCode());
        assertEquals(3, response.getData().get("notified"));
        verify(backupAlertService).report(any());
    }

    @Test
    @DisplayName("令牌错误：抛 403，且不触碰业务逻辑")
    void wrongToken() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controllerWithServerToken(SECRET).backup("wrong-token", sampleRequest()));

        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        verify(backupAlertService, never()).report(any());
    }

    @Test
    @DisplayName("令牌缺失：抛 403")
    void missingToken() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controllerWithServerToken(SECRET).backup(null, sampleRequest()));

        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        verify(backupAlertService, never()).report(any());
    }

    @Test
    @DisplayName("服务端未配置令牌：fail-closed，一律拒绝（避免变成匿名消息通道）")
    void failClosedWhenServerTokenBlank() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controllerWithServerToken("").backup("", sampleRequest()));
        assertEquals(ErrorCode.INTERNAL_ERROR, ex.getErrorCode());

        BusinessException ex2 = assertThrows(BusinessException.class,
                () -> controllerWithServerToken(null).backup("anything", sampleRequest()));
        assertEquals(ErrorCode.INTERNAL_ERROR, ex2.getErrorCode());

        verify(backupAlertService, never()).report(any());
    }
}
