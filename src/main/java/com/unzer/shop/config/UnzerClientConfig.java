package com.unzer.shop.config;

import com.unzer.payment.Unzer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(UnzerProperties.class)
public class UnzerClientConfig {

    @Bean
    public Unzer unzerClient(UnzerProperties props) {
        if (props.getPrivateKey() == null || props.getPrivateKey().isBlank()) {
            throw new IllegalStateException(
                    "UNZER_PRIVATE_KEY is not set. Export it before starting the app — " +
                    "see README.md 'Setup' section. Never hard-code it here or commit it.");
        }
        return new Unzer(props.getPrivateKey());
    }
}
