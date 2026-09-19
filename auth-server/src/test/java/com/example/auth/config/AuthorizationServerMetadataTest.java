package com.example.auth.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the real application context against MySQL 8 and checks the standard
 * OAuth 2.1 / OIDC metadata endpoints, the JWKS and the seeded JDBC clients.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class AuthorizationServerMetadataTest {

    private static final String ISSUER = "http://localhost:8083";

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Test
    void publishesAuthorizationServerMetadata() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-authorization-server"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value(ISSUER))
                .andExpect(jsonPath("$.authorization_endpoint").value(ISSUER + "/oauth2/authorize"))
                .andExpect(jsonPath("$.token_endpoint").value(ISSUER + "/oauth2/token"))
                .andExpect(jsonPath("$.jwks_uri").value(ISSUER + "/oauth2/jwks"))
                .andExpect(jsonPath("$.introspection_endpoint").value(ISSUER + "/oauth2/introspect"))
                .andExpect(jsonPath("$.revocation_endpoint").value(ISSUER + "/oauth2/revoke"));
    }

    @Test
    void publishesOpenIdProviderMetadata() throws Exception {
        mockMvc.perform(get("/.well-known/openid-configuration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value(ISSUER))
                .andExpect(jsonPath("$.userinfo_endpoint").value(ISSUER + "/userinfo"))
                .andExpect(jsonPath("$.id_token_signing_alg_values_supported[0]").value("RS256"));
    }

    @Test
    void publishesRsaJwksWithoutPrivateKeyMaterial() throws Exception {
        mockMvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys.length()").value(1))
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].kid").isNotEmpty())
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());
    }

    @Test
    void usesConfiguredIssuerAndExternalKeystore() {
        assertThat(jwtDecoder).isNotNull();
    }

    @Test
    void loadsSeededClientsThroughJdbcRegisteredClientRepository() {
        RegisteredClient webClient = registeredClientRepository.findByClientId("auth-web-public");
        assertThat(webClient).isNotNull();
        assertThat(webClient.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(webClient.getAuthorizationGrantTypes()).containsExactlyInAnyOrder(
                org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE,
                org.springframework.security.oauth2.core.AuthorizationGrantType.REFRESH_TOKEN);
        assertThat(webClient.getScopes()).containsExactlyInAnyOrder("openid", "profile", "weather:read");

        RegisteredClient machineClient = registeredClientRepository.findByClientId("auth-machine");
        assertThat(machineClient).isNotNull();
        assertThat(machineClient.getClientAuthenticationMethods())
                .containsExactly(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        assertThat(machineClient.getAuthorizationGrantTypes()).containsExactly(
                org.springframework.security.oauth2.core.AuthorizationGrantType.CLIENT_CREDENTIALS);
        assertThat(PasswordEncoderFactories.createDelegatingPasswordEncoder()
                .matches("auth-machine-secret", machineClient.getClientSecret())).isTrue();
    }

}
