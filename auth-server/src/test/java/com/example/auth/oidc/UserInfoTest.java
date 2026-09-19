package com.example.auth.oidc;

import com.example.auth.oauth.AuthorizationCodeFlow;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The OIDC UserInfo endpoint re-reads the user, the organization and the roles
 * from the read-only identity tables on every call.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Sql("/db/sys-identity-fixtures.sql")
class UserInfoTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AuthorizationCodeFlow flow;

    @BeforeEach
    void setUp() {
        this.flow = new AuthorizationCodeFlow(this.mockMvc);
    }

    @Test
    void returnsTheCurrentUserOrganizationAndItsRoles() throws Exception {
        String accessToken = accessToken(10L);

        this.mockMvc.perform(get("/userinfo").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sub").value("1"))
                .andExpect(jsonPath("$.preferred_username").value("alice"))
                .andExpect(jsonPath("$.name").value("Alice"))
                .andExpect(jsonPath("$.org_id").value("10"))
                .andExpect(jsonPath("$.org_name").value("Alpha"))
                .andExpect(jsonPath("$.roles").value(containsInAnyOrder("ADMIN", "VIEWER")));
    }

    @Test
    void requiresABearerToken() throws Exception {
        this.mockMvc.perform(get("/userinfo"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsATokenWhoseOrganizationRelationshipWasRevoked() throws Exception {
        String accessToken = accessToken(10L);
        this.jdbcTemplate.update("DELETE FROM sys_user_org_role WHERE user_id = 1 AND org_id = 10");

        this.mockMvc.perform(get("/userinfo").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    private String accessToken(long orgId) throws Exception {
        JsonNode tokens = this.flow.authorizeAndExchangeTokens("alice", "alice-password", orgId, "openid profile");
        return tokens.path("access_token").asText();
    }
}
