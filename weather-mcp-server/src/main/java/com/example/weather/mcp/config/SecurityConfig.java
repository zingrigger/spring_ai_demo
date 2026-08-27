package com.example.weather.mcp.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
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
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .oauth2AuthorizationServer(Customizer.withDefaults())
                .csrf(csrf -> csrf.ignoringRequestMatchers("/mcp"))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/mcp").hasAuthority("SCOPE_" + McpBearerAuthenticationEntryPoint.REQUIRED_SCOPE)
                        .anyRequest().permitAll())
                .oauth2ResourceServer(resource -> resource
                        .authenticationEntryPoint(new McpBearerAuthenticationEntryPoint())
                        .jwt(Customizer.withDefaults()))
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder().issuer("http://localhost:8081").build();
    }
}
