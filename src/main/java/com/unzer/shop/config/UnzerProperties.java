package com.unzer.shop.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "unzer")
@Getter
@Setter
public class UnzerProperties {

    private String privateKey;

    private String returnUrl;

    private String webhookUrl;
}
