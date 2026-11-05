package it.infocube.ianus.plugin.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import it.infocube.ianus.plugin.web.ApiKeyFilter;
import org.springframework.context.annotation.Configuration;

/** Intestazione e autenticazione della specifica OpenAPI generata dal codice (sezione 7). */
@Configuration
@OpenAPIDefinition(info = @Info(title = "IANUS Plugin", version = "2.0"))
@SecurityScheme(name = "apiKey", type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.HEADER,
        paramName = ApiKeyFilter.HEADER)
public class OpenApiConfig {
}
