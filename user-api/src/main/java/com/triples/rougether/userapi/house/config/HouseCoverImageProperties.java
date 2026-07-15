package com.triples.rougether.userapi.house.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("house.cover-images")
public record HouseCoverImageProperties(List<Entry> items) {

    public HouseCoverImageProperties {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public record Entry(String code, String name, String coverImageKey) {
    }
}
