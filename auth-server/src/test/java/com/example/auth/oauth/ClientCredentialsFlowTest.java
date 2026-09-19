package com.example.auth.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Client credentials tokens carry the client identity and its scopes only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class ClientCredentialsFlowTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Test
    void issuesAscopedTokenWithoutAnyUserClaims() throws Exception {
        String body = this.mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic("auth-machine", "auth-machine-secret"))
                        .param("grant_type", "client_credentials")
                        .param("scope", "weather:read"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String accessToken = OBJECT_MAPPER.readTree(body).path("access_token").asText();
        assertThat(accessToken).isNotBlank();

        Jwt token = this.jwtDecoder.decode(accessToken);
        assertThat(token.getSubject()).isEqualTo("auth-machine");
        assertThat(token.getClaimAsStringList("scope")).containsExactly("weather:read");
        assertThat(token.getClaims())
                .doesNotContainKeys("preferred_username", "name", "org_id", "org_name", "roles");
    }

    @Test
    void rejectsWrongClientCredentials() throws Exception {
        this.mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic("auth-machine", "wrong-secret"))
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsScopesTheClientIsNotRegisteredFor() throws Exception {
        this.mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic("auth-machine", "auth-machine-secret"))
                        .param("grant_type", "client_credentials")
                        .param("scope", "openid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_scope"));
    }
}
