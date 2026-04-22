package com.aipmo.agent;

import com.aipmo.agent.config.GitHubProperties;
import com.aipmo.agent.config.IntegrationProperties;
import com.aipmo.agent.config.JiraProperties;
import com.aipmo.agent.config.MetricsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
    JiraProperties.class,
    GitHubProperties.class,
    IntegrationProperties.class,
    MetricsProperties.class
})
public class AiPmoAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiPmoAgentApplication.class, args);
    }
}
