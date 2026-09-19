package com.example.auth.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * consent 数据接口：客户端名称、请求的 scopes、当前组织与用户。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Sql("/db/sys-identity-fixtures.sql")
class ConsentApiControllerTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void consentPayloadSplitsFiltersAndDeduplicatesScopes() throws Exception {
        MockHttpSession session = boundSession("alice", "alice-password", 10L);

        MvcResult result = this.mockMvc.perform(get("/api/consent").session(session)
                        .param("client_id", "auth-web-public")
                        .param("scope", "openid profile")
                        .param("scope", "profile weather:read")
                        .param("state", "test-state"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode payload = this.objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(payload.path("clientId").asText()).isEqualTo("auth-web-public");
        assertThat(payload.path("clientName").asText()).isEqualTo("Auth Web Client");
        assertThat(payload.path("state").asText()).isEqualTo("test-state");
        assertThat(payload.path("organization").path("name").asText()).isEqualTo("Alpha");
        assertThat(payload.path("user").path("account").asText()).isEqualTo("alice");
        List<String> scopes = new ArrayList<>();
        payload.path("scopes").forEach((scope) -> scopes.add(scope.asText()));
        assertThat(scopes).containsExactly("profile", "weather:read");
    }

    @Test
    void unknownClientFallsBackToTheClientId() throws Exception {
        MockHttpSession session = boundSession("alice", "alice-password", 10L);

        MvcResult result = this.mockMvc.perform(get("/api/consent").session(session)
                        .param("client_id", "not-registered")
                        .param("scope", "profile"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode payload = this.objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(payload.path("clientName").asText()).isEqualTo("not-registered");
    }

    private MockHttpSession boundSession(String account, String password, long orgId) throws Exception {
        MvcResult authorize = this.mockMvc.perform(authorizeRequest())
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) authorize.getRequest().getSession(false);
        assertThat(session).isNotNull();
        this.mockMvc.perform(post("/api/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"" + account + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());
        this.mockMvc.perform(post("/api/organizations").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":" + orgId + "}"))
                .andExpect(status().isOk());
        return session;
    }

    private static MockHttpServletRequestBuilder authorizeRequest() {
        return get("/oauth2/authorize")
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE)
                .queryParam("response_type", "code")
                .queryParam("client_id", "auth-web-public")
                .queryParam("scope", "openid profile")
                .queryParam("redirect_uri", "http://127.0.0.1:8080/login/oauth2/code/auth-server")
                .queryParam("state", "test-state")
                .queryParam("code_challenge", "0123456789012345678901234567890123456789012")
                .queryParam("code_challenge_method", "S256");
    }
}
