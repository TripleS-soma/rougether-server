package com.triples.rougether.lambdacontrol;

import com.triples.rougether.furniture.config.FurnitureGenerationProperties;
import com.triples.rougether.furniture.lambda.FurnitureLambdaControl;
import com.triples.rougether.furniture.service.*;
import java.time.Clock;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.*;

@SpringBootConfiguration
@EnableAutoConfiguration
@EnableJpaAuditing
@EntityScan("com.triples.rougether.domain")
@EnableJpaRepositories("com.triples.rougether.domain")
@EnableConfigurationProperties(FurnitureGenerationProperties.class)
@Import({FurnitureLambdaControl.class, FurnitureGenerationTransactions.class, GenerationCreditLedger.class})
public class ControlApplication {
    @Bean javax.sql.DataSource dataSource(org.springframework.core.env.Environment env) {
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(env.getRequiredProperty("spring.datasource.url"));
        source.setUsername(env.getRequiredProperty("spring.datasource.username"));
        source.setPassword(env.getRequiredProperty("spring.datasource.password"));
        var properties = new java.util.Properties();
        properties.setProperty("connectTimeout", "5000");
        properties.setProperty("socketTimeout", "10000");
        source.setConnectionProperties(properties);
        return source;
    }
    @Bean Clock controlClock() { return Clock.systemUTC(); }
}
