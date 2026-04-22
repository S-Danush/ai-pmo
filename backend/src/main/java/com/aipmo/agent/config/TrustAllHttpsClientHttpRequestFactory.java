package com.aipmo.agent.config;

import org.springframework.http.client.SimpleClientHttpRequestFactory;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * <strong>Local development only.</strong> Disables TLS server certificate and hostname verification for
 * connections made through this factory (e.g. OpenAI when the JVM trust store cannot validate chains
 * behind SSL inspection).
 */
public final class TrustAllHttpsClientHttpRequestFactory extends SimpleClientHttpRequestFactory {

    private final javax.net.ssl.SSLSocketFactory sslSocketFactory;

    public TrustAllHttpsClientHttpRequestFactory() throws Exception {
        TrustManager[] trustAll =
                new TrustManager[] {
                    new X509TrustManager() {
                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }

                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    }
                };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAll, new SecureRandom());
        this.sslSocketFactory = ctx.getSocketFactory();
    }

    @Override
    protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
        if (connection instanceof HttpsURLConnection https) {
            https.setSSLSocketFactory(sslSocketFactory);
            https.setHostnameVerifier((hostname, session) -> true);
        }
        super.prepareConnection(connection, httpMethod);
    }
}
