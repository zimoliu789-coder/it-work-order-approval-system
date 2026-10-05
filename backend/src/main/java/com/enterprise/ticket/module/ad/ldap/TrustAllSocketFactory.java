package com.enterprise.ticket.module.ad.ldap;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * 「信任所有证书」的 SSLSocketFactory（AD 配置中「证书验证：跳过」模式）
 *
 * <h2>为什么必须存在这么一个类</h2>
 * <p>内网自建域控（尤其是一台 DC 自签证书、或用了企业内网 CA 但没有导入 JVM truststore 的环境）
 * 在 LDAPS 下会因为证书链无法验证而直接握手失败。运维的诉求往往是
 * 「先让它通，证书的事以后再说」，而 JNDI 没有提供任何「忽略证书」的开关 ——
 * 唯一的入口就是 {@code java.naming.ldap.factory.socket} 指定一个自定义工厂类。
 *
 * <h2>安全边界（必须明确）</h2>
 * <ul>
 *   <li>本类<b>只</b>在 {@code ad_config.strict_cert = 0} 时被挂上去；</li>
 *   <li>跳过校验意味着无法识别中间人：攻击者可以在网络路径上冒充域控，
 *       收集到用户提交的域口令。因此这条路径在配置页会给出醒目警告，
 *       且生产环境建议保持严格校验（把内网 CA 导入 JVM truststore 才是正解）；</li>
 *   <li>本类不做任何其它事情（不记录、不缓存、不改全局 {@code SSLContext}），
 *       避免「一次放宽」变成「全进程放宽」—— 普通的 HTTPS 请求仍然走严格校验。</li>
 * </ul>
 *
 * <h2>为什么是 public + 无参构造</h2>
 * <p>JNDI 通过 {@code Class.forName(name).newInstance()} 反射实例化这个类，
 * 因此它必须是 public、且有无参构造函数。做成静态内部类会让类名带 {@code $}
 * 而增加无谓的踩坑面。
 */
public class TrustAllSocketFactory extends SocketFactory {

    private static final SSLSocketFactory DELEGATE = buildTrustAllFactory();

    /** JNDI 反射实例化入口 */
    public TrustAllSocketFactory() {
    }

    private static SSLSocketFactory buildTrustAllFactory() {
        try {
            TrustManager[] trustAll = new TrustManager[]{new TrustAllManager()};
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new SecureRandom());
            return context.getSocketFactory();
        } catch (Exception e) {
            throw new IllegalStateException("构建「跳过证书校验」的 SSL 工厂失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Socket createSocket() throws IOException {
        return DELEGATE.createSocket();
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return DELEGATE.createSocket(host, port);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return DELEGATE.createSocket(host, port, localHost, localPort);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return DELEGATE.createSocket(host, port);
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        return DELEGATE.createSocket(address, port, localAddress, localPort);
    }

    /** 无条件放行任何服务端证书 */
    private static final class TrustAllManager implements X509TrustManager {

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // 客户端证书不参与本场景（AD 只做服务端认证）
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // 刻意不校验：本类存在的唯一目的就是在「跳过校验」模式下让握手通过
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
