package com.triples.rougether.userapi.auth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({JwtProperties.class, KakaoProperties.class})
public class AuthConfig {
}
