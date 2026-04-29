package com.aipmo.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "groq.api")
public class GroqProperties {

    /** Groq Cloud API key; leave blank to use rule-based chat fallback. */
    private String key = "";

    private String model = "llama-3.3-70b-versatile";

    private String baseUrl = "https://api.groq.com/openai/v1";

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
