package com.jackal.group.tfx.gau;

import org.jasypt.encryption.StringEncryptor;
import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.jasypt.encryption.pbe.config.EnvironmentStringPBEConfig;
import org.jasypt.iv.RandomIvGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

@SpringBootApplication
@EnableScheduling
public class GenAiUtilApplication {
    @Bean("defaultWebclientConnectionProvider")
    public ConnectionProvider defaultWebclientConnectionProvider() {
        return ConnectionProvider.builder("fixed")
                .maxConnections(500)
                .maxIdleTime(Duration.ofSeconds(10))
                .maxLifeTime(Duration.ofSeconds(60))
                .pendingAcquireTimeout(Duration.ofSeconds(60))
                .evictInBackground(Duration.ofSeconds(120)).build();
    }
    @Bean("jasyptStringEncryptor")
    public StringEncryptor jasyptStringEncryptor() {
        StandardPBEStringEncryptor encryptor = new StandardPBEStringEncryptor();
        EnvironmentStringPBEConfig config = new EnvironmentStringPBEConfig();
        config.setPasswordEnvName("PLATFORM_KEY");
        config.setAlgorithm("PBEWithMD5AndDES");
        encryptor.setConfig(config);
        encryptor.setIvGenerator(new RandomIvGenerator());
        return encryptor;
    }

    public static void main(String[] args) {
        SpringApplication.run(GenAiUtilApplication.class, args);
    }
}
