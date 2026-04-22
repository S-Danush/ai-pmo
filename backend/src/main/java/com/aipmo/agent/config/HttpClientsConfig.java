package com.aipmo.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration
public class HttpClientsConfig {

    private static final Logger log = LoggerFactory.getLogger(HttpClientsConfig.class);

    @Bean
    public ClientHttpRequestFactory integrationHttpRequestFactory(
            @Value("${http.client.connect-timeout-ms:10000}") int connectTimeoutMs,
            @Value("${http.client.read-timeout-ms:45000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return factory;
    }

    /**
     * HTTP layer for OpenAI only. Uses {@link SslConfig} Apache HttpClient (custom truststore / proxy capable).
     * When {@code http.client.openai.trust-all-certificates=true}, uses a trust-all factory (local dev only —
     * never in production).
     */
    @Bean
    @Qualifier("openAiHttpRequestFactory")
    public ClientHttpRequestFactory openAiHttpRequestFactory(
            @Value("${http.client.openai.trust-all-certificates:false}") boolean trustAllCertificates,
            HttpComponentsClientHttpRequestFactory sslAwareHttpRequestFactory,
            @Value("${http.client.openai.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${http.client.openai.read-timeout-ms:10000}") int readTimeoutMs)
            throws Exception {
        if (trustAllCertificates) {
            log.warn(
                    "SECURITY: http.client.openai.trust-all-certificates=true — TLS certificate verification "
                            + "is DISABLED for OpenAI RestClient only. Use for local development; never enable in production.");
            TrustAllHttpsClientHttpRequestFactory factory = new TrustAllHttpsClientHttpRequestFactory();
            factory.setConnectTimeout(connectTimeoutMs);
            factory.setReadTimeout(readTimeoutMs);
            return factory;
        }
        return sslAwareHttpRequestFactory;
    }

    @Bean
    public RestClient openAiRestClient(
            @Qualifier("openAiHttpRequestFactory") ClientHttpRequestFactory openAiHttpRequestFactory) {
        return RestClient.builder()
                .baseUrl("https://api.openai.com")
                .requestFactory(openAiHttpRequestFactory)
                .build();
    }

    private static String trimTrailingSlash(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Bean
    public RestClient jiraRestClient(
            JiraProperties jiraProperties, ClientHttpRequestFactory integrationHttpRequestFactory) {
        String base = trimTrailingSlash(jiraProperties.resolvedBaseUrl());
        if (base.isEmpty()) {
            base = "https://example.invalid";
        }
        return RestClient.builder()
                .baseUrl(base)
                .requestFactory(integrationHttpRequestFactory)
                .requestInterceptor(new JiraBasicAuthInterceptor(jiraProperties))
                .build();
    }

    @Bean
    public RestClient githubRestClient(
            GitHubProperties gitHubProperties, ClientHttpRequestFactory integrationHttpRequestFactory) {
        return RestClient.builder()
                .baseUrl("https://api.github.com")
                .requestFactory(integrationHttpRequestFactory)
                .requestInterceptor(new GitHubAuthInterceptor(gitHubProperties))
                .build();
    }

    private static final class JiraBasicAuthInterceptor implements ClientHttpRequestInterceptor {

        private final JiraProperties props;

        private JiraBasicAuthInterceptor(JiraProperties props) {
            this.props = props;
        }

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                throws IOException {
            if (props.isComplete()) {
                String user = props.resolvedEmail();
                String pass = props.resolvedApiToken();
                String raw = user + ":" + pass;
                String encoded = java.util.Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
                request.getHeaders().set("Authorization", "Basic " + encoded);
            }
            return execution.execute(request, body);
        }
    }

    private static final class GitHubAuthInterceptor implements ClientHttpRequestInterceptor {

        private final GitHubProperties props;

        private GitHubAuthInterceptor(GitHubProperties props) {
            this.props = props;
        }

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                throws IOException {
            String token = props.resolvedToken();
            if (!token.isEmpty()) {
                request.getHeaders().setBearerAuth(token);
            }
            request.getHeaders().set("Accept", "application/vnd.github+json");
            request.getHeaders().set("X-GitHub-Api-Version", "2022-11-28");
            return execution.execute(request, body);
        }
    }
}
