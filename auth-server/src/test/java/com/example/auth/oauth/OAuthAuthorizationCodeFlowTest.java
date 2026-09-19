package com.example.auth.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.http.MediaType.TEXT_HTML_VALUE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorization Code + PKCE end to end, including the negative cases the design
 * requires (wrong verifier, replayed code, mismatched redirect URI, missing PKCE).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Sql("/db/sys-identity-fixtures.sql")
class OAuthAuthorizationCodeFlowTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtDecoder jwtDecoder;

    private AuthorizationCodeFlow flow;

    @BeforeEach
    void setUp() {
        this.flow = new AuthorizationCodeFlow(this.mockMvc);
    }

    @Test
    void exchangesTheCodeForAccessIdAndRefreshTokensWithUserClaims() throws Exception {
        JsonNode tokens = this.flow.authorizeAndExchangeTokens("alice", "alice-password", 10L, "openid profile");

        assertThat(tokens.path("token_type").asText()).isEqualTo("Bearer");
        // Ten minutes, minus the elapsed whole seconds of the request itself.
        assertThat(tokens.path("expires_in").asLong()).isBetween(595L, 600L);
        assertThat(tokens.path("scope").asText()).contains("openid", "profile");
        assertThat(tokens.path("refresh_token").asText()).isNotBlank();

        Jwt accessToken = this.jwtDecoder.decode(tokens.path("access_token").asText());
        assertThat(accessToken.getSubject()).isEqualTo("1");
        assertThat(accessToken.getClaimAsString("preferred_username")).isEqualTo("alice");
        assertThat(accessToken.getClaimAsString("name")).isEqualTo("Alice");
        assertThat(accessToken.getClaimAsString("org_id")).isEqualTo("10");
        assertThat(accessToken.getClaimAsString("org_name")).isEqualTo("Alpha");
        assertThat(accessToken.getClaimAsStringList("roles")).containsExactlyInAnyOrder("ADMIN", "VIEWER");
        assertThat(accessToken.getClaimAsStringList("scope")).contains("openid").contains("profile");

        Jwt idToken = this.jwtDecoder.decode(tokens.path("id_token").asText());
        assertThat(idToken.getSubject()).isEqualTo("1");
        assertThat(idToken.getClaimAsString("org_id")).isEqualTo("10");
    }

    @Test
    void rejectsAWrongCodeVerifier() throws Exception {
        String code = this.flow.authorize("alice", "alice-password", 10L, "openid");

        this.mockMvc.perform(AuthorizationCodeFlow.tokenExchange(
                        code, AuthorizationCodeFlow.REDIRECT_URI, "a-completely-different-verifier-1234567890"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void rejectsAReplayedAuthorizationCode() throws Exception {
        String code = this.flow.authorize("alice", "alice-password", 10L, "openid");
        String body = tokenRequest(code, AuthorizationCodeFlow.REDIRECT_URI, AuthorizationCodeFlow.VERIFIER);
        assertThat(body).contains("access_token");

        this.mockMvc.perform(AuthorizationCodeFlow.tokenExchange(
                        code, AuthorizationCodeFlow.REDIRECT_URI, AuthorizationCodeFlow.VERIFIER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void rejectsAMismatchedRedirectUri() throws Exception {
        String code = this.flow.authorize("alice", "alice-password", 10L, "openid");

        this.mockMvc.perform(AuthorizationCodeFlow.tokenExchange(
                        code, "http://127.0.0.1:9999/not-registered", AuthorizationCodeFlow.VERIFIER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void refusesAnAuthorizationRequestWithoutPkceAndReturnsTheErrorToTheClient() throws Exception {
        this.mockMvc.perform(get("/oauth2/authorize")
                        .header(ACCEPT, TEXT_HTML_VALUE)
                        .queryParam("response_type", "code")
                        .queryParam("client_id", AuthorizationCodeFlow.CLIENT_ID)
                        .queryParam("scope", "openid")
                        .queryParam("redirect_uri", AuthorizationCodeFlow.REDIRECT_URI)
                        .queryParam("state", AuthorizationCodeFlow.STATE))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrlPattern(AuthorizationCodeFlow.REDIRECT_URI
                                + "?error=invalid_request*state=test-state*"));
    }

    private String tokenRequest(String code, String redirectUri, String verifier) throws Exception {
        return this.mockMvc.perform(AuthorizationCodeFlow.tokenExchange(code, redirectUri, verifier))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
