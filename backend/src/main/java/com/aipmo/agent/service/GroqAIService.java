package com.aipmo.agent.service;

import com.aipmo.agent.config.GroqProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** Groq OpenAI-compatible chat completions client. */
@Service
public class GroqAIService {

    /** One chat turn for OpenAI-style {@code messages} arrays (user / assistant only). */
    public record GroqChatMessage(String role, String content) {}

    private static final Logger log = LoggerFactory.getLogger(GroqAIService.class);

    private final GroqProperties properties;
    private final ObjectMapper objectMapper;
    private final Environment environment;

    public GroqAIService(
            GroqProperties properties, ObjectMapper objectMapper, Environment environment) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.environment = environment;
    }

    /**
     * Resolves the Groq API key: bound {@code groq.api.key}, then Spring {@code Environment}, then
     * {@code GROQ_API_KEY} from the OS process environment (helps when IDE/Gradle does not inject
     * user-level vars into Spring's property sources the same way as a plain shell).
     */
    public String resolvedApiKey() {
        String k = properties.getKey();
        if (k != null && !k.isBlank()) {
            return k.trim();
        }
        k = environment.getProperty("groq.api.key");
        if (k != null && !k.isBlank()) {
            return k.trim();
        }
        k = System.getenv("GROQ_API_KEY");
        if (k != null && !k.isBlank()) {
            return k.trim();
        }
        return "";
    }

    public boolean isEnabled() {
        return !resolvedApiKey().isEmpty();
    }

    /**
     * @param systemPrompt instructions for the model
     * @param userContent user question plus structured context (already JSON text or prose)
     */
    public String complete(String systemPrompt, String userContent) {
        return complete(systemPrompt, userContent, false);
    }

    /**
     * @param jsonObjectResponse when true, requests {@code response_format: json_object} (user prompt
     *     should require JSON output)
     */
    public String complete(String systemPrompt, String userContent, boolean jsonObjectResponse) {
        return completeConversation(
                systemPrompt, List.of(new GroqChatMessage("user", userContent)), jsonObjectResponse);
    }

    /**
     * Multi-turn chat: {@code system} plus ordered user/assistant messages (OpenAI-compatible {@code
     * messages}).
     */
    public String completeConversation(
            String systemPrompt, List<GroqChatMessage> conversationMessages, boolean jsonObjectResponse) {
        String apiKey = resolvedApiKey();
        if (apiKey.isEmpty()) {
            throw new IllegalStateException("Groq API key not configured");
        }
        if (log.isDebugEnabled()) {
            log.debug(
                    "Groq request model={} jsonObject={} turns={}",
                    properties.getModel(),
                    jsonObjectResponse,
                    conversationMessages != null ? conversationMessages.size() : 0);
        }
        String url = properties.getBaseUrl().replaceAll("/$", "") + "/chat/completions";
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", properties.getModel());
        body.put("temperature", 0.42);
        if (jsonObjectResponse) {
            ObjectNode rf = objectMapper.createObjectNode();
            rf.put("type", "json_object");
            body.set("response_format", rf);
        }
        ArrayNode messages = body.putArray("messages");
        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", systemPrompt);
        if (conversationMessages != null) {
            for (GroqChatMessage m : conversationMessages) {
                if (m == null || m.role() == null || m.content() == null) {
                    continue;
                }
                String r = m.role().trim().toLowerCase();
                if (!"user".equals(r) && !"assistant".equals(r)) {
                    continue;
                }
                ObjectNode node = messages.addObject();
                node.put("role", r);
                node.put("content", m.content());
            }
        }

        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
            HttpRequest req =
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(90))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + apiKey)
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                            .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.warn("Groq HTTP {}: {}", resp.statusCode(), truncate(resp.body(), 500));
                throw new IllegalStateException("Groq API error: HTTP " + resp.statusCode());
            }
            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new IllegalStateException("Groq returned no choices");
            }
            String text = choices.get(0).path("message").path("content").asText("");
            if (text.isBlank()) {
                log.warn("Groq returned empty message content");
                throw new IllegalStateException("Groq returned empty content");
            }
            log.debug("Groq response contentChars={}", text.length());
            return text.trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Groq request interrupted", e);
        } catch (Exception e) {
            log.warn("Groq call failed: {}", e.getMessage());
            throw new IllegalStateException("Groq request failed: " + e.getMessage(), e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
