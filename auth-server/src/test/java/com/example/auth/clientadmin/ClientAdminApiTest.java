package com.example.auth.clientadmin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理 API 端到端：权限、CRUD、secret 只返回一次、启停与删除对协议端点的影响。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Sql("/db/sys-identity-fixtures.sql")
class ClientAdminApiTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void nonAdminsCannotCallTheApi() throws Exception {
        MockHttpSession session = new MockHttpSession();
        this.mockMvc.perform(post("/api/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"bob\",\"password\":\"bob-password\"}"))
                .andExpect(status().isOk());

        this.mockMvc.perform(get("/api/admin/clients").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("access_denied"));
    }

    @Test
    void createReturnsTheSecretOnceAndStoresOnlyTheHash() throws Exception {
        MockHttpSession session = adminSession();

        MvcResult created = this.mockMvc.perform(post("/api/admin/clients").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientId":"api-machine","clientName":"API Machine","type":"machine",
                                 "scopes":["weather:read"]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.client.clientId").value("api-machine"))
                .andExpect(jsonPath("$.clientSecret").isNotEmpty())
                .andReturn();
        String secret = JSON.readTree(created.getResponse().getContentAsString()).path("clientSecret").asText();

        String listBody = this.mockMvc.perform(get("/api/admin/clients").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listBody).doesNotContain(secret);

        String stored = this.jdbcTemplate.queryForObject(
                "SELECT client_secret FROM oauth2_registered_client WHERE client_id = ?",
                String.class, "api-machine");
        assertThat(stored).startsWith("{bcrypt}$2a$").doesNotContain(secret);
    }

    @Test
    void invalidMetadataReturnsFieldLevelDetails() throws Exception {
        this.mockMvc.perform(post("/api/admin/clients").session(adminSession()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientId":"Bad_ID","clientName":"Bad","type":"web","scopes":["openid"],
                                 "redirectUris":["https://example.com/callback"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_client_metadata"))
                .andExpect(jsonPath("$.details[0].field").value("clientId"));
    }

    @Test
    void updateCanDisablePkceForConfidentialClients() throws Exception {
        MockHttpSession session = adminSession();
        this.mockMvc.perform(post("/api/admin/clients").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientId":"pkce-api-client","clientName":"PKCE API","type":"web",
                                 "redirectUris":["https://example.com/callback"],"scopes":["openid"]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.client.requireProofKey").value(true));

        this.mockMvc.perform(put("/api/admin/clients/pkce-api-client").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requireProofKey\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requireProofKey").value(false));
    }

    @Test
    void createdMachineClientFetchesTokensAndRotationInvalidatesTheOldSecret() throws Exception {
        MockHttpSession session = adminSession();
        String secret = createMachineClient(session, "rotating-api-client");

        tokenRequest("rotating-api-client", secret).andExpect(status().isOk());

        MvcResult rotated = this.mockMvc.perform(post("/api/admin/clients/rotating-api-client/secret")
                        .session(session).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        String newSecret = JSON.readTree(rotated.getResponse().getContentAsString()).path("clientSecret").asText();
        assertThat(newSecret).isNotEqualTo(secret);

        tokenRequest("rotating-api-client", secret).andExpect(status().isUnauthorized());
        tokenRequest("rotating-api-client", newSecret).andExpect(status().isOk());
    }

    @Test
    void disablingStopsTokenIssuanceAndDeleteKeepsTheAudit() throws Exception {
        MockHttpSession session = adminSession();
        String secret = createMachineClient(session, "disabled-api-client");

        this.mockMvc.perform(post("/api/admin/clients/disabled-api-client/disable").session(session).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        tokenRequest("disabled-api-client", secret).andExpect(status().isUnauthorized());

        this.mockMvc.perform(delete("/api/admin/clients/disabled-api-client").session(session).with(csrf()))
                .andExpect(status().isNoContent());

        Integer remaining = this.jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oauth2_registered_client WHERE client_id = ?",
                Integer.class, "disabled-api-client");
        assertThat(remaining).isZero();
        Integer audits = this.jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM oauth2_registered_client_audit WHERE client_id = ?
                """, Integer.class, "disabled-api-client");
        assertThat(audits).isEqualTo(3);
    }

    @Test
    void unknownClientReturns404() throws Exception {
        this.mockMvc.perform(get("/api/admin/clients/missing-client").session(adminSession()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("client_not_found"));
    }

    private MockHttpSession adminSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        this.mockMvc.perform(post("/api/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"alice\",\"password\":\"alice-password\"}"))
                .andExpect(status().isOk());
        return session;
    }

    private String createMachineClient(MockHttpSession session, String clientId) throws Exception {
        MvcResult created = this.mockMvc.perform(post("/api/admin/clients").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"" + clientId + "\",\"clientName\":\"" + clientId
                                + "\",\"type\":\"machine\",\"scopes\":[\"weather:read\"]}"))
                .andExpect(status().isCreated())
                .andReturn();
        return JSON.readTree(created.getResponse().getContentAsString()).path("clientSecret").asText();
    }

    private ResultActions tokenRequest(String clientId, String secret) throws Exception {
        return this.mockMvc.perform(post("/oauth2/token")
                .with(httpBasic(clientId, secret))
                .param("grant_type", "client_credentials")
                .param("scope", "weather:read"));
    }
}
