package com.triples.rougether.userapi.house.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("house.cover-images")
public record HouseCoverImageProperties(List<String> keys) {

    public HouseCoverImageProperties {
        keys = keys == null ? List.of() : List.copyOf(keys);
    }
}
