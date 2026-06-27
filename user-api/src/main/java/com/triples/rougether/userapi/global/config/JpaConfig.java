package com.triples.rougether.userapi.global.config;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EntityScan("com.triples.rougether.domain")
@EnableJpaRepositories("com.triples.rougether.domain")
@EnableJpaAuditing
public class JpaConfig {
}
