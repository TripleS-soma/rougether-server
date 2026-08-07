package com.triples.rougether.userapi.global.storage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AssetPropertiesBindingTest {

    @Test
    void deploymentEnvironmentEnablesVersionPurge() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
            "deploymentEnvironment",
            Map.of("ASSET_S3_PURGE_VERSIONS", "true")
        ));

        new YamlPropertySourceLoader()
            .load("application.yml", new FileSystemResource("src/main/resources/application.yml"))
            .forEach(environment.getPropertySources()::addLast);

        assertThat(environment.getProperty("ASSET_S3_PURGE_VERSIONS")).isEqualTo("true");
        assertThat(environment.getProperty("asset.s3.purge-versions-on-delete")).isEqualTo("true");

        AssetProperties properties = Binder.get(environment)
            .bind("asset", Bindable.of(AssetProperties.class))
            .orElseThrow(() -> new IllegalStateException("asset properties were not bound"));

        assertThat(properties.s3().purgeVersionsOnDelete()).isTrue();
    }
}
