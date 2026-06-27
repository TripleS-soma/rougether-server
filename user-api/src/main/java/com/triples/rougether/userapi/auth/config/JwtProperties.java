package com.triples.rougether.userapi.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jwt")
public record JwtProperties(String secret, Duration accessTtl, Duration refreshTtl) {
}
