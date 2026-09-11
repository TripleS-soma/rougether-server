package com.triples.rougether.furnitureworker;
import java.time.Clock;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
@SpringBootApplication(scanBasePackages={"com.triples.rougether.furnitureworker", "com.triples.rougether.furniture",
        "com.triples.rougether.infra.assets", "com.triples.rougether.infra.llm"})
@EnableJpaAuditing
@EntityScan("com.triples.rougether.domain")
@EnableJpaRepositories("com.triples.rougether.domain")
public class FurnitureWorkerApplication {
    public static void main(String[] args) {
        var app=new SpringApplication(FurnitureWorkerApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.run(args);
    }
    @Bean Clock workerClock() { return Clock.systemUTC(); }
}
