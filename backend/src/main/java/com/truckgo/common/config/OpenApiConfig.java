package com.truckgo.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI truckGoOpenApi() {
        return new OpenAPI().info(new Info()
                .title("TruckGo API")
                .description("Tài liệu REST API TruckGo: xác thực, hồ sơ người dùng, tài xế, phương tiện và quản trị duyệt hồ sơ.")
                .version("v1")
                .contact(new Contact().name("TruckGo")))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}
