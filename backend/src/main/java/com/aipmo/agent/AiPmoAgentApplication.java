package com.aipmo.agent;

import com.aipmo.agent.config.MetricsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({MetricsProperties.class})
public class AiPmoAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiPmoAgentApplication.class, args);
    }
}
