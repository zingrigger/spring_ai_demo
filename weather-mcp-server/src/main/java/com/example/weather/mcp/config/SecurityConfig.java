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
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
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
                .formLogin(Customizer.withDefaults())
                .csrf(csrf -> csrf.ignoringRequestMatchers("/mcp"))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/mcp").hasAuthority("SCOPE_" + McpBearerAuthenticationEntryPoint.REQUIRED_SCOPE)
                        // The AS endpoint filter runs after the authorization
                        // rules, so the interactive authorize step must require
                        // an authenticated principal here; otherwise anonymous
                        // requests bounce back to the client with
                        // error=invalid_request instead of reaching the login
                        // page. The token endpoint stays permitAll so
                        // client_credentials (and the code exchange) can
                        // authenticate via the AS client-authentication filter.
                        .requestMatchers("/oauth2/authorize").authenticated()
                        .anyRequest().permitAll())
                .oauth2ResourceServer(resource -> resource
                        .authenticationEntryPoint(new McpBearerAuthenticationEntryPoint())
                        .jwt(Customizer.withDefaults()))
                .build();
    }

    /**
     * Local demo user so the interactive authorization_code + PKCE flow can
     * actually complete: a browser hitting {@code /oauth2/authorize} is
     * redirected to the form login page and can authenticate with these
     * credentials. Demo-only; a real deployment should use a real identity
     * provider or at least a hashed password.
     */
    @Bean
    UserDetailsService userDetailsService() {
        UserDetails demoUser = User.withUsername("demo")
                .password("{noop}demo-password")
                .roles("USER")
                .build();
        return new InMemoryUserDetailsManager(demoUser);
    }

    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        // The factory's default NimbusJwtDecoder validates only the JWS
        // signature — its claims verifier is a no-op, so a leaked token would
        // never expire. Replace it with the default timestamp validation
        // (exp/nbf, plus typ/x5t checks) so expired tokens are rejected.
        NimbusJwtDecoder decoder = (NimbusJwtDecoder) OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
        decoder.setJwtValidator(JwtValidators.createDefault());
        return decoder;
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder().issuer("http://localhost:8081").build();
    }
}
