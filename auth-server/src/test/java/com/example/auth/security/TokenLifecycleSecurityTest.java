package com.example.auth.security;

import com.example.auth.oauth.AuthorizationCodeFlow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Token lifecycle and security policy: configured lifetimes, refresh rotation,
 * revocation, re-validation of the organization relationship, client
 * authentication for introspection/revocation, and CSRF on the custom pages.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Sql("/db/sys-identity-fixtures.sql")
class TokenLifecycleSecurityTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AuthorizationCodeFlow flow;

    @BeforeEach
    void setUp() {
        this.flow = new AuthorizationCodeFlow(this.mockMvc);
    }

    @Test
    void accessTokenUsesTheConfiguredTenMinuteLifetime() throws Exception {
        Jwt accessToken = this.jwtDecoder.decode(tokens().path("access_token").asText());

        assertThat(accessToken.getIssuedAt()).isNotNull();
        assertThat(accessToken.getExpiresAt()).isNotNull();
        assertThat(Duration.between(accessToken.getIssuedAt(), accessToken.getExpiresAt()))
                .isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void rejectsAnExpiredAccessToken() throws Exception {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("http://localhost:8083")
                .subject("1")
                .audience(List.of(AuthorizationCodeFlow.CLIENT_ID))
                .issuedAt(now.minusSeconds(7200))
                .expiresAt(now.minusSeconds(3600))
                .build();
        String expired = this.jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();

        this.mockMvc.perform(get("/userinfo").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rotatesRefreshTokensAndRejectsReuse() throws Exception {
        String first = tokens().path("refresh_token").asText();

        String rotated = refresh(first).path("refresh_token").asText();
        assertThat(rotated).isNotBlank().isNotEqualTo(first);

        this.mockMvc.perform(refreshRequest(first))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void refusesToRefreshAfterTheOrganizationRelationshipIsRevoked() throws Exception {
        String refreshToken = tokens().path("refresh_token").asText();
        this.jdbcTemplate.update("DELETE FROM sys_user_org_role WHERE user_id = 1 AND org_id = 10");

        this.mockMvc.perform(refreshRequest(refreshToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void refreshedTokenCarriesTheCurrentRoles() throws Exception {
        String refreshToken = tokens().path("refresh_token").asText();
        this.jdbcTemplate.update(
                "DELETE FROM sys_user_org_role WHERE user_id = 1 AND org_id = 10 AND role_id = 101");

        Jwt refreshed = this.jwtDecoder.decode(refresh(refreshToken).path("access_token").asText());

        assertThat(refreshed.getClaimAsStringList("roles")).containsExactly("ADMIN");
    }

    @Test
    void revokedRefreshTokenCanNoLongerBeUsed() throws Exception {
        String refreshToken = tokens().path("refresh_token").asText();

        this.mockMvc.perform(AuthorizationCodeFlow.revoke(refreshToken))
                .andExpect(status().isOk());

        this.mockMvc.perform(refreshRequest(refreshToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void introspectionAndRevocationRequireClientAuthentication() throws Exception {
        this.mockMvc.perform(post("/oauth2/introspect").param("token", "whatever"))
                .andExpect(status().isUnauthorized());
        this.mockMvc.perform(post("/oauth2/revoke").param("token", "whatever"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customPagesRejectRequestsWithoutACsrfToken() throws Exception {
        this.mockMvc.perform(post("/login").param("username", "alice").param("password", "alice-password"))
                .andExpect(status().isForbidden());
    }

    private JsonNode tokens() throws Exception {
        return this.flow.authorizeAndExchangeTokens("alice", "alice-password", 10L, "openid profile");
    }

    private JsonNode refresh(String refreshToken) throws Exception {
        String body = this.mockMvc.perform(refreshRequest(refreshToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return OBJECT_MAPPER.readTree(body);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder refreshRequest(
            String refreshToken) {
        return AuthorizationCodeFlow.refresh(refreshToken);
    }
}
