package com.hush.core;

import android.content.Context;

import com.hush.utils.Diag;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * 给 HMS 进程补上华为自签根证书（2.6.42）。
 *
 * <p><b>证据</b>：2.6.41 的日志里 {@code netprobe} 三路探测：</p>
 * <pre>
 *   hwid1.vmall.com   https=302   OK
 *   www.baidu.com     https=200   OK
 *   grs.dbankcloud.com https=ERR  CertPathValidatorException:
 *                                 Trust anchor for certification path not found.
 * </pre>
 *
 * <p>用手里的 curl 复现，证书链是
 * {@code grs.dbankcloud.com -> Huawei CBG Application Integration CA
 * -> Huawei CBG Root CA}，根证书<b>没有随握手下发</b>，而这张华为自签根
 * 只预装在华为/荣耀机型的系统 CA store 里。本机没有，于是 GRS 解析失败，
 * 账号 kit 拿不到 API 站点，一键登录校验请求永远走不到服务端 ——
 * 页面上就是「无法获取数据，请稍后再试。」</p>
 *
 * <p><b>做法</b>：HMS 自己带了这张根证书，account kit 的 assets 里就叫
 * {@code CBG_CA.cer}（主体已验证为
 * {@code CN=Huawei CBG Root CA, OU=Huawei CBG, O=Huawei, C=CN}，
 * 自签），hmscore 的 assets 里还有一份 {@code hmsrootcas.bks}。把它读出来
 * 作为额外信任锚，和系统 TrustManager 组合成「先系统、失败再试华为根」，
 * 再设成进程默认：</p>
 * <pre>
 *   SSLContext.setDefault(ctx)                    // 影响 SSLContext.getDefault()
 *   HttpsURLConnection.setDefaultSSLSocketFactory // 影响默认 HttpsURLConnection
 * </pre>
 *
 * <p>只<b>增加</b>信任锚，不关闭校验：系统信任链能通过的域名行为完全不变，
 * 只有系统链失败时才回退到华为根。HMS 自己的 okhttp 用的是
 * {@code NetworkClient.getSSLSocketFactory()}（SecureX509TrustManager，
 * 本来就会读 hmsrootcas.bks），这里主要救的是 GRS 站点解析、运营商 SDK
 * 回传这类走 {@code HttpsURLConnection} 的路径。</p>
 */
public final class HmsTls {

    private static final String[] ASSETS = {"CBG_CA.cer", "hmsrootcas.bks"};

    private static boolean sDone;

    private HmsTls() {
    }

    /** 在 HMS 进程里装一次；找不到证书或环境不支持都只写日志，不抛。 */
    public static void install(Context app) {
        if (sDone || app == null) return;
        sDone = true;
        try {
            X509TrustManager extra = extraTrust(app);
            if (extra == null) {
                Diag.log("tls", "assets 里没有华为根证书，跳过");
                return;
            }
            X509TrustManager system = systemTrust();
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new Composite(system, extra)}, null);
            SSLContext.setDefault(ctx);
            HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
            Diag.log("tls", "进程默认信任链已追加 Huawei CBG Root CA");
        } catch (Throwable t) {
            Diag.log("tls", "安装信任链失败 " + t);
        }
    }

    private static X509TrustManager systemTrust() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        }
        return null;
    }

    /** 依次尝试 CBG_CA.cer（X.509 DER）和 hmsrootcas.bks（BKS）。 */
    private static X509TrustManager extraTrust(Context app) {
        for (String name : ASSETS) {
            InputStream is = null;
            try {
                is = app.getAssets().open(name);
                X509TrustManager tm = loadTrust(name, is);
                if (tm != null) {
                    Diag.log("tls", "已读取信任锚 assets/" + name);
                    return tm;
                }
            } catch (Throwable t) {
                Diag.log("tls", "读取 assets/" + name + " 失败 " + t.getClass().getSimpleName());
            } finally {
                if (is != null) try {
                    is.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static X509TrustManager loadTrust(String name, InputStream is) throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        if (name.endsWith(".cer")) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(is);
            ks.setCertificateEntry("huawei-cbg", cert);
            Diag.log("tls", "证书 " + cert.getSubjectDN());
        } else {
            // BKS 由 HMS 打包，口令为空
            KeyStore bks = KeyStore.getInstance("BKS");
            bks.load(is, "".toCharArray());
            java.util.Enumeration<String> aliases = bks.aliases();
            int n = 0;
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                java.security.cert.Certificate c = bks.getCertificate(alias);
                if (c != null) {
                    ks.setCertificateEntry("hms-" + n++, c);
                }
            }
            if (n == 0) return null;
            Diag.log("tls", "BKS 证书数 " + n);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        }
        return null;
    }

    /**
     * 先走系统信任链，失败再试华为根。抛出的异常用系统那份的，
     * 这样真正不受信任的站点报错信息与容器外完全一致。
     */
    private static final class Composite implements X509TrustManager {

        private final X509TrustManager system;
        private final X509TrustManager extra;

        Composite(X509TrustManager system, X509TrustManager extra) {
            this.system = system;
            this.extra = extra;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
            if (system != null) {
                system.checkClientTrusted(chain, authType);
            } else {
                extra.checkClientTrusted(chain, authType);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
            try {
                if (system != null) {
                    system.checkServerTrusted(chain, authType);
                    return;
                }
            } catch (java.security.cert.CertificateException first) {
                try {
                    extra.checkServerTrusted(chain, authType);
                    return;
                } catch (Throwable ignored) {
                    throw first;
                }
            }
            extra.checkServerTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> all = new ArrayList<>();
            if (system != null) {
                java.util.Collections.addAll(all, system.getAcceptedIssuers());
            }
            java.util.Collections.addAll(all, extra.getAcceptedIssuers());
            return all.toArray(new X509Certificate[0]);
        }
    }
}
