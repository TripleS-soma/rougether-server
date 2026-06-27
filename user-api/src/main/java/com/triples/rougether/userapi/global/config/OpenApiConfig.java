package com.triples.rougether.userapi.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String JWT_SCHEME = "bearerAuth";

    @Bean
    OpenAPI openAPI() {
        // Authorize 버튼에 access token 만 넣으면 Authorization: Bearer <token> 으로 붙음
        SecurityScheme bearer = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT");

        return new OpenAPI()
                .info(new Info()
                        .title("Rougether User API")
                        .version("v1"))
                .components(new Components().addSecuritySchemes(JWT_SCHEME, bearer))
                .addSecurityItem(new SecurityRequirement().addList(JWT_SCHEME));
    }
}
