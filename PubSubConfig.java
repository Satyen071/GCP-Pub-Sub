package com.loblaw.ingestionservice.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@EnableConfigurationProperties
@ConfigurationProperties("pubsub")
public class PubSubConfig {

    private String projectId;
    private String topicId;
    private String credential;
}
