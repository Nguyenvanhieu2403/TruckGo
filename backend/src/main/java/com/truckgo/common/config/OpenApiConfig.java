package com.truckgo.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI truckGoOpenApi() {
        return new OpenAPI().info(new Info()
                .title("TruckGo API")
                .description("Tài liệu REST API nền tảng TruckGo. Các API nghiệp vụ sẽ được bổ sung ở những sprint tiếp theo.")
                .version("v1")
                .contact(new Contact().name("TruckGo")));
    }
}
