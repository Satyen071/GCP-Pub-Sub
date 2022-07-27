package com.loblaw.ingestionservice.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@EnableConfigurationProperties
@ConfigurationProperties("encryption")
public class EncryptionConfig {

    private String keyRing;
    private String keyId;
    private String locationId;

}
