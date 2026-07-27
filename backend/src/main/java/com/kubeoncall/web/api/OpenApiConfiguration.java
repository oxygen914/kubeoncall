package com.kubeoncall.web.api;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;

/**
 * Runtime OpenAPI metadata for the versioned Console API.
 *
 * <p>The build starts this application on an isolated loopback port and fetches {@code /v3/api-docs}
 * into {@code api/openapi.json}. That generated file is the source used by the frontend type client,
 * so controller changes cannot silently drift away from its consumer contract.
 */
@OpenAPIDefinition(
        info = @Info(title = "KubeOnCall Console API", version = "v1", description = "Versioned Console API"))
@SecurityScheme(
        name = "sessionCookie",
        type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.COOKIE,
        paramName = "KOC_SESSION")
@SecurityScheme(name = "legacyBearer", type = SecuritySchemeType.HTTP, scheme = "bearer")
@SecurityScheme(name = "apiToken", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "koc_<base64url>")
public class OpenApiConfiguration {}
