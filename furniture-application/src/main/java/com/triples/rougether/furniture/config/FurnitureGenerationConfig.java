package com.triples.rougether.furniture.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FurnitureGenerationProperties.class)
public class FurnitureGenerationConfig {
}
