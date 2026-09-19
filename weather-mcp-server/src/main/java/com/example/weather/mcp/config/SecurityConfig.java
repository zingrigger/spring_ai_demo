package com.example.weather.mcp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Resource-server security for the MCP endpoint.
 *
 * <p>The MCP server does not issue tokens: the external auth-server is the only
 * authorization server. This configuration validates access tokens against the
 * auth-server JWKS and requires the {@code weather:read} scope for {@code /mcp}.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(WeatherMcpProperties.class)
public class SecurityConfig {

    /**
     * Serves the RFC 9728 protected-resource metadata from the MVC controller
     * (ProtectedResourceMetadataController). This chain matches only the metadata
     * path and is evaluated before the main chain so that the resource server's
     * built-in OAuth2ProtectedResourceMetadataFilter does not shadow the controller
     * with an incomplete response (it omits {@code authorization_servers}).
     * The endpoint stays public (permitAll); /mcp rules are untouched.
     */
    @Bean
    @Order(1)
    SecurityFilterChain protectedResourceMetadataSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/.well-known/oauth-protected-resource")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, WeatherMcpProperties properties) throws Exception {
        return http
                .csrf(csrf -> csrf.ignoringRequestMatchers("/mcp"))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/mcp").hasAuthority("SCOPE_" + properties.requiredScope())
                        .anyRequest().permitAll())
                .oauth2ResourceServer(resource -> resource
                        .authenticationEntryPoint(new McpBearerAuthenticationEntryPoint(properties))
                        .jwt(Customizer.withDefaults()))
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(WeatherMcpProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        decoder.setJwtValidator(McpJwtValidators.create(
                properties.authorizationServerUrl(), properties.resourceIdentifier()));
        return decoder;
    }
}
