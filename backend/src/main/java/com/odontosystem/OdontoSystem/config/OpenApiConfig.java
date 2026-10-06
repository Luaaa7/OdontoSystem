package com.odontosystem.OdontoSystem.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Datos generales que muestra Swagger (/swagger-ui.html) y el botón "Authorize" para probar
 * los endpoints protegidos: se pega el access_token que devuelve /api/auth/login.
 */
@Configuration
public class OpenApiConfig {

    private static final String ESQUEMA_JWT = "bearerAuth";

    /**
     * La API responde en snake_case (spring.jackson.property-naming-strategy), pero Swagger
     * arma los esquemas con su propio Jackson y por defecto los mostraria en camelCase.
     * Este bean le aplica la misma regla para que la documentacion coincida con el JSON real.
     */
    @Bean
    public ModelResolver modelResolverSnakeCase() {
        return new ModelResolver(Json.mapper().copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
    }

    @Bean
    public OpenAPI odontoSystemOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("OdontoSystem API")
                        .version("v1")
                        .description("""
                                API REST del marketplace dental OdontoSystem (Ica, Perú).
                                Contrato oficial para la web y la app: nombres JSON en snake_case \
                                y valores de los enums en español, igual que la base de datos.
                                Para endpoints protegidos: hacer login, copiar el access_token \
                                y pegarlo en el botón Authorize."""))
                .components(new Components().addSecuritySchemes(ESQUEMA_JWT, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(ESQUEMA_JWT));
    }
}
