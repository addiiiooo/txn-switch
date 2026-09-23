/* SPDX-License-Identifier: MIT */
package com.txnswitch.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfig {

  @Bean
  OpenAPI txnSwitchOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("txn-switch")
                .version("0.1.0")
                .description(
                    "A card authorization switch. Amounts are integers in the currency's minor"
                        + " units. Errors are RFC 9457 problem documents; the catalogue is in"
                        + " docs/errors.md.")
                .license(
                    new License()
                        .name("MIT")
                        .url("https://github.com/addiiiooo/txn-switch/blob/main/LICENSE")))
        .components(
            new Components()
                .addSecuritySchemes(
                    "apiKey",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .description("API key issued to the merchant")))
        .addSecurityItem(new SecurityRequirement().addList("apiKey"));
  }
}
