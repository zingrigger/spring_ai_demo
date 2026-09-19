package com.example.weather.mcp.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

public final class McpBearerAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final WeatherMcpProperties properties;

    public McpBearerAuthenticationEntryPoint(WeatherMcpProperties properties) {
        this.properties = properties;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws java.io.IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate",
                "Bearer resource_metadata=\"" + this.properties.protectedResourceMetadataUrl()
                        + "\", scope=\"" + this.properties.requiredScope() + "\"");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"unauthorized\"}");
    }
}
