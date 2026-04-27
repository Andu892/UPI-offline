package com.demo.upimesh.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class AppConfig {

    @Bean
    public OpenAPI upiOfflineOpenAPI() {
        return new OpenAPI()
                .components(new Components())
                .info(new Info()
                        .title("UPI Offline Mesh API")
                        .description("Offline UPI mesh demo API with bridge ingestion, mesh simulation, and account/transaction endpoints.")
                        .version("0.0.1")
                        .contact(new Contact()
                                .name("Demo Team")
                                .email("support@example.com"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0.html")));
    }
}
