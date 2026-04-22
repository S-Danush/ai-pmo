package com.aipmo.agent.config;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.SecureRandom;

/**
 * Production TLS: optional custom truststore, optional HTTP proxy, shared by {@link RestTemplate} and
 * the OpenAI {@link org.springframework.web.client.RestClient} (via {@link HttpClientsConfig}).
 */
@Configuration
public class SslConfig {

    private static final Logger log = LoggerFactory.getLogger(SslConfig.class);

    @Bean
    public SSLContext applicationSslContext(
            @Value("${ssl.truststore.path:}") String truststorePath,
            @Value("${ssl.truststore.password:}") String truststorePassword,
            @Value("${ssl.truststore.type:}") String truststoreType)
            throws Exception {
        if (truststorePath != null && !truststorePath.isBlank()) {
            String type =
                    truststoreType == null || truststoreType.isBlank()
                            ? KeyStore.getDefaultType()
                            : truststoreType.trim();
            KeyStore trustStore = KeyStore.getInstance(type);
            char[] pwd =
                    truststorePassword == null || truststorePassword.isEmpty()
                            ? null
                            : truststorePassword.toCharArray();
            try (InputStream is = new BufferedInputStream(new FileInputStream(truststorePath.trim()))) {
                trustStore.load(is, pwd);
            }
            TrustManagerFactory tmf =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), new SecureRandom());
            log.info("Custom truststore loaded for TLS path={} keystoreType={}", truststorePath, type);
            return sslContext;
        }
        SSLContext ctx = SSLContext.getDefault();
        log.info("Using JVM default SSLContext (ssl.truststore.path not set)");
        return ctx;
    }

    @Bean(destroyMethod = "close")
    public CloseableHttpClient sslAwareHttpClient(
            SSLContext applicationSslContext,
            @Value("${http.client.openai.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${http.client.openai.read-timeout-ms:10000}") int readTimeoutMs,
            @Value("${proxy.host:}") String proxyHost,
            @Value("${proxy.port:0}") int proxyPort) {
        SSLConnectionSocketFactory sslSocketFactory = new SSLConnectionSocketFactory(applicationSslContext);

        PoolingHttpClientConnectionManager connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setSSLSocketFactory(sslSocketFactory)
                        .build();

        log.info("Custom SSL context initialized for outbound HTTPS calls");

        RequestConfig requestConfig =
                RequestConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                        .setResponseTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                        .build();

        var clientBuilder = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig);

        if (proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
            HttpHost proxy = new HttpHost("http", proxyHost.trim(), proxyPort);
            clientBuilder.setProxy(proxy);
            log.info("HTTP proxy configured for TLS clients host={} port={}", proxyHost.trim(), proxyPort);
        }

        return clientBuilder.build();
    }

    /**
     * Shared factory: standard certificate verification (no trust-all); used by OpenAI RestClient and
     * {@link #restTemplate}.
     */
    @Bean
    public HttpComponentsClientHttpRequestFactory sslAwareHttpRequestFactory(CloseableHttpClient sslAwareHttpClient) {
        return new HttpComponentsClientHttpRequestFactory(sslAwareHttpClient);
    }

    @Bean
    public RestTemplate restTemplate(HttpComponentsClientHttpRequestFactory sslAwareHttpRequestFactory) {
        return new RestTemplate(sslAwareHttpRequestFactory);
    }
}
