package com.example.weather.mcp.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

public final class McpBearerAuthenticationEntryPoint implements AuthenticationEntryPoint {

    static final String RESOURCE_METADATA_URL = "http://localhost:8081/.well-known/oauth-protected-resource";
    static final String REQUIRED_SCOPE = "weather:read";

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws java.io.IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate",
                "Bearer resource_metadata=\"" + RESOURCE_METADATA_URL + "\", scope=\"" + REQUIRED_SCOPE + "\"");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"unauthorized\"}");
    }
}
